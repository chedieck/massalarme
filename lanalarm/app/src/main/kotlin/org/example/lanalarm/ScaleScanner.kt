package org.example.lanalarm

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Listens to the Xiaomi body-composition scale directly from the phone.
 *
 * This used to live in the PC daemon. Moving it here is what lets the alarm work
 * with the PC asleep — the phone senses, rings, and only reports afterwards.
 *
 * Two jobs, deliberately separate:
 *
 *  - [ScaleListener.onStableWeight] fires on the *first* stable advertisement.
 *    That is what silences a hard alarm; the user should not stand there waiting.
 *  - [ScaleListener.onWeighInComplete] fires once the scale has gone quiet for
 *    [AppSettings.sessionGapSeconds]. That is the reading worth recording: the scale
 *    keeps adjusting for a few seconds as the user settles, and the last value is
 *    the true one.
 *
 * The advertisement payload is the same one the Python daemon decoded, so the
 * byte offsets below match `alarm_manager.wait_for_weight` exactly.
 */
class ScaleScanner(private val context: Context) {

    companion object {
        private const val TAG = "ScaleScanner"

        /** Body Composition service, 0x181B. */
        private val BODY_COMPOSITION_UUID: ParcelUuid =
            ParcelUuid.fromString("0000181b-0000-1000-8000-00805f9b34fb")
    }

    interface ScaleListener {
        /** First stable reading of a session — silences a hard alarm. */
        fun onStableWeight(reading: ScaleCodec.ScaleReading)

        /** The session settled. This is the reading that gets recorded. */
        fun onWeighInComplete(reading: ScaleCodec.ScaleReading, distinctMeasurements: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var listener: ScaleListener? = null
    private var scanning = false

    // Open session state.
    private var sessionReadings = mutableListOf<ScaleCodec.ScaleReading>()
    private var sessionAnnounced = false

    private val commitRunnable = Runnable { commitSession() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getServiceData(BODY_COMPOSITION_UUID) ?: return
            handlePayload(payload)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            scanning = false
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    fun hasPermission(): Boolean {
        val needed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        return needed.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isScanning(): Boolean = scanning

    /**
     * Is the user mid-weigh-in? The session is only worth the radio while the
     * scale is still talking, and it must not be cut short at the moment the
     * alarm goes quiet — the first stable reading is not the final one.
     */
    fun hasOpenSession(): Boolean = sessionReadings.isNotEmpty()

    /**
     * @param highPriority scan at full duty cycle. Worth it while an alarm is
     *   ringing and the user is waiting for it to stop; ruinous for the battery
     *   the rest of the time, where a quarter-duty scan finds the same scale a
     *   second or two later and nobody notices.
     */
    fun start(listener: ScaleListener, highPriority: Boolean): Boolean {
        if (scanning) {
            this.listener = listener
            return true
        }
        if (!hasPermission()) {
            Log.w(TAG, "Cannot scan: Bluetooth scan permission not granted")
            return false
        }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "Cannot scan: Bluetooth is off")
            return false
        }

        this.listener = listener
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "No BLE scanner available")
            return false
        }

        // Scan unfiltered and match on service data in the callback, exactly as
        // the Python daemon did. A ScanFilter on service data with a null value
        // behaves inconsistently across Android versions (some builds NPE inside
        // matchesPartialData), and scanning is bounded to alarm windows anyway.
        val filters = emptyList<ScanFilter>()
        val settings = ScanSettings.Builder()
            .setScanMode(
                if (highPriority) ScanSettings.SCAN_MODE_LOW_LATENCY
                else ScanSettings.SCAN_MODE_BALANCED
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        return try {
            scanner?.startScan(filters, settings, scanCallback)
            scanning = true
            Log.i(TAG, "BLE scan started (highPriority=$highPriority)")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan denied: ${e.message}")
            false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "startScan failed: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!scanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopScan failed: ${e.message}")
        }
        scanning = false
        Log.i(TAG, "BLE scan stopped")

        // Do not discard an in-flight session: the user stepped on the scale,
        // that reading is real, and dropping it loses data massalarme owns.
        handler.removeCallbacks(commitRunnable)
        commitSession()
    }

    // ─── Session assembly ────────────────────────────────────────────

    private fun handlePayload(payload: ByteArray) {
        val reading = ScaleCodec.decode(payload, System.currentTimeMillis()) ?: return
        val stableFlag = AppSettings.stableFlag(context)
        val minWeight = AppSettings.minWeightKg(context)

        if (reading.flag != stableFlag || reading.weightKg <= minWeight) {
            return
        }

        // Identical payloads are the same measurement rebroadcast — the scale's
        // own clock is embedded, so equal bytes never mean two distinct readings.
        val alreadySeen = sessionReadings.any { it.rawValue == reading.rawValue }
        if (!alreadySeen) {
            sessionReadings.add(reading)
        }

        if (!sessionAnnounced) {
            sessionAnnounced = true
            Log.i(TAG, "Stable weight: %.2f kg".format(reading.weightKg))
            listener?.onStableWeight(reading)
        }

        // Restart the quiet timer: the session ends when the scale stops talking.
        handler.removeCallbacks(commitRunnable)
        handler.postDelayed(commitRunnable, AppSettings.sessionGapSeconds(context) * 1000L)
    }

    private fun commitSession() {
        if (sessionReadings.isEmpty()) {
            sessionAnnounced = false
            return
        }

        // The scale is still settling until the last distinct payload, so that is
        // the reading the user actually cares about.
        val canonical = sessionReadings.last()
        val distinct = sessionReadings.size

        sessionReadings = mutableListOf()
        sessionAnnounced = false

        Log.i(
            TAG,
            "Weigh-in complete: %.2f kg from %d measurement(s)".format(
                canonical.weightKg, distinct
            )
        )
        listener?.onWeighInComplete(canonical, distinct)
    }
}
