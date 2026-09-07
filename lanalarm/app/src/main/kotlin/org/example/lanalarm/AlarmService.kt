package org.example.lanalarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Rings the alarm, listens to the scale, and then gets out of the way.
 *
 * This service used to run for the whole life of the phone. It held an HTTP
 * server open on port 8080 waiting for a desktop to send it commands, and a
 * websocket that retried every fifteen seconds forever whether or not that
 * desktop existed. Both were there to talk to the PC daemon, which is no longer
 * in the path at all: the phone holds its own schedule and reports to ontoplano
 * over the internet by itself.
 *
 * What is left runs only when there is something to do. It starts when an alarm
 * fires, when the user asks it to listen for the scale, or when there are
 * readings to ship, and it stops itself the moment none of those is true. An
 * alarm clock has no business being a background process for the other
 * twenty-three hours.
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val NOTIFICATION_SERVICE_ID = 1
        private const val NOTIFICATION_ALARM_ID = 2
        private const val NOTIFICATION_WEIGHT_ID = 4

        const val ACTION_ALARM_STOPPED = "org.example.lanalarm.ALARM_STOPPED"

        /**
         * The stored schedule changed without the UI asking. Anything showing
         * alarms has to redraw, or it keeps displaying what it read last.
         */
        const val ACTION_ALARMS_CHANGED = "org.example.lanalarm.ALARMS_CHANGED"

        /** A live reading came off the scale. Carries [EXTRA_WEIGHT_KG] and friends. */
        const val ACTION_SCALE_READING = "org.example.lanalarm.SCALE_READING"
        const val EXTRA_WEIGHT_KG = "weight_kg"
        const val EXTRA_STABILIZED = "stabilized"
        const val EXTRA_HAS_IMPEDANCE = "has_impedance"
        const val EXTRA_IMPEDANCE = "impedance"

        const val ACTION_START_ALARM = "org.example.lanalarm.START_ALARM"
        const val ACTION_LISTEN_SCALE = "org.example.lanalarm.LISTEN_SCALE"
        const val ACTION_STOP_LISTENING = "org.example.lanalarm.STOP_LISTENING"
        const val ACTION_SYNC = "org.example.lanalarm.SYNC"

        /** Hard alarms use the siren; soft ones should not wake the neighbours. */
        private const val ASSET_HARD_ALARM = "trombetas.mp3"
        private const val ASSET_SOFT_ALARM = "soft.mp3"

        /**
         * Give up scanning eventually, so the radio is never left running.
         *
         * Someone who is going to weigh in does it as soon as the alarm stops.
         * The manual test is shorter still: it exists to answer "can the phone
         * hear the scale at all", which takes seconds.
         */
        private const val ALARM_SCAN_TIMEOUT_MS = 6 * 60 * 1000L
        private const val MANUAL_SCAN_TIMEOUT_MS = 3 * 60 * 1000L

        @Volatile
        var instance: AlarmService? = null
            private set

        /**
         * What the dismiss screen needs to know: a hard alarm demands the scale,
         * a soft one offers a button.
         */
        @Volatile
        var activeAlarmIsHard: Boolean = false
            private set

        @Volatile
        var activeAlarmName: String = "Alarm"
            private set

        @Volatile
        var activeAlarmId: String = ""
            private set

        @Volatile
        var activeAlarmKind: String = AlarmSchedule.KIND_HARD
            private set

        /** Why a hard alarm was downgraded, for the dismiss screen to explain. */
        @Volatile
        var activeAlarmDowngradeReason: String? = null
            private set
    }

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var savedVolume: Int = -1
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeGuardRunning = false

    private lateinit var scaleScanner: ScaleScanner
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var scanTimeoutRunnable: Runnable? = null

    /** Non-zero while a sync is in flight, so the service does not stop under it. */
    @Volatile
    private var syncsInFlight = 0

    /**
     * Teardown runs stopAlarm() and stopScaleScan(), both of which normally ask
     * to stop the service. Asking again from inside onDestroy is at best noise.
     */
    @Volatile
    private var destroyed = false

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mediaPlayer != null) enforceMaxVolume()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)

        // Before going foreground, not after: buildServiceNotification() asks the
        // scanner what it is doing, and a lateinit read here takes the whole
        // service down on every single start — silently, inside a catch.
        scaleScanner = ScaleScanner(this)

        // Android gives a service started with startForegroundService about five
        // seconds to show a notification or be killed, and onStartCommand has not
        // run yet. Claim the plainest type now and refine it once we know what
        // this start is actually for.
        enterForeground(ServiceForegroundType.MEDIA_ONLY)
    }

    // ─── Foreground service type ─────────────────────────────────────

    private enum class ServiceForegroundType { MEDIA_ONLY, MEDIA_AND_DEVICE }

    /**
     * Go (or stay) foreground, claiming only the types we are currently entitled to.
     *
     * Android 14 validates foreground-service types at `startForeground()` time.
     * The two-argument form claims *every* type declared in the manifest, so
     * declaring `connectedDevice` there is enough to make the call throw
     * `SecurityException` whenever no Bluetooth permission is granted — which is
     * the state the app is in the first time it is ever opened.
     */
    private fun enterForeground(type: ServiceForegroundType) {
        val notification = buildServiceNotification()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_SERVICE_ID, notification)
            return
        }

        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (type == ServiceForegroundType.MEDIA_AND_DEVICE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }

        try {
            startForeground(NOTIFICATION_SERVICE_ID, notification, types)
        } catch (e: Exception) {
            // Never let a foreground-service technicality kill the alarm.
            Log.e(TAG, "startForeground($types) failed: ${e.message}")
            if (type != ServiceForegroundType.MEDIA_ONLY) {
                runCatching {
                    startForeground(
                        NOTIFICATION_SERVICE_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                }
            }
        }
    }

    private fun buildServiceNotification(): Notification {
        val text = when {
            mediaPlayer != null -> activeAlarmName
            scaleScanner.isScanning() -> "Listening for the scale"
            else -> "Working"
        }
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle("Massalarme")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refreshServiceNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_SERVICE_ID, buildServiceNotification())
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val id = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID).orEmpty()
                val name = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NAME) ?: "Alarm"
                val kind = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_KIND)
                    ?: AlarmSchedule.KIND_HARD
                Log.i(TAG, "Starting alarm '$name' ($kind)")
                mainHandler.post { startAlarm(id, name, kind) }
            }
            ACTION_LISTEN_SCALE -> mainHandler.post { startScaleScan(highPriority = false) }
            ACTION_STOP_LISTENING -> mainHandler.post { stopScaleScan() }
            ACTION_SYNC -> syncNow()
            else -> mainHandler.post { stopIfIdle() }
        }

        // Not sticky. A restarted-from-nothing service with no intent has no idea
        // what it was doing, and the alarm it might have been ringing is already
        // booked in AlarmManager, which survives the process dying.
        return START_NOT_STICKY
    }

    /**
     * Shut down once there is nothing left to do.
     *
     * Called after every state change that could be the last one. Getting this
     * wrong in the safe direction costs a stuck notification; getting it wrong
     * in the other direction silences an alarm, so every caller checks all three
     * conditions rather than assuming.
     */
    private fun stopIfIdle() {
        if (destroyed) return
        if (mediaPlayer != null) return
        if (scaleScanner.isScanning()) return
        if (syncsInFlight > 0) return
        Log.i(TAG, "Nothing left to do, stopping")
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        instance = null
        stopScaleScan()
        stopAlarm()
        background.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── ontoplano ───────────────────────────────────────────────────

    /**
     * Push what is queued and pull what is planned, then stand down.
     *
     * Safe to call often: the outbound queue is keyed by a derived id, so a
     * resend is a duplicate rather than a second point.
     */
    fun syncNow() {
        if (AppSettings.ontoplanoClient(this) == null) {
            mainHandler.post { stopIfIdle() }
            return
        }
        syncsInFlight++
        background.execute {
            try {
                val outcome = OntoplanoSync.run(this)
                if (outcome.ok) {
                    Log.i(TAG, "Synced: ${outcome.uploaded} up, ${outcome.pending} pending")
                    SyncRetryJob.cancel(this)
                } else {
                    Log.w(TAG, "Sync failed: ${outcome.error}")
                    // Let the system tell us when there is a network again,
                    // rather than waking up to find out there still isn't.
                    if (outcome.pending > 0) SyncRetryJob.schedule(this)
                }
            } finally {
                syncsInFlight--
                mainHandler.post { stopIfIdle() }
            }
        }
    }

    // ─── Scale ───────────────────────────────────────────────────────

    private val scaleListener = object : ScaleScanner.ScaleListener {
        override fun onLiveWeight(reading: ScaleCodec.ScaleReading) {
            // Straight through to whoever is looking. The dismiss screen shows
            // this climbing while the user steps on, which is the only feedback
            // that the phone is hearing the scale at all.
            sendBroadcast(
                Intent(ACTION_SCALE_READING).setPackage(packageName).apply {
                    putExtra(EXTRA_WEIGHT_KG, reading.weightKg)
                    putExtra(EXTRA_STABILIZED, reading.isStabilized)
                    putExtra(EXTRA_HAS_IMPEDANCE, reading.hasImpedance)
                    reading.impedance?.let { putExtra(EXTRA_IMPEDANCE, it) }
                }
            )
        }

        override fun onStableWeight(reading: ScaleCodec.ScaleReading) {
            // The alarm stops on the first reading that meets the user's stop
            // condition — no standing on the scale waiting for the session to
            // settle.
            mainHandler.post {
                if (mediaPlayer != null) {
                    Log.i(TAG, "Scale satisfied the alarm at %.2f kg".format(reading.weightKg))
                    stopAlarm()
                }
            }
        }

        override fun onWeighInComplete(
            reading: ScaleCodec.ScaleReading,
            distinctMeasurements: Int
        ) {
            recordWeighIn(reading, distinctMeasurements)
            mainHandler.post { stopScaleScan() }
        }
    }

    /**
     * @param highPriority full-duty scanning, for the seconds an alarm is
     *   ringing and the user is stood in front of the scale waiting for it to
     *   shut up. A quarter-duty scan finds the same scale a second or two later,
     *   which nobody notices and the battery does.
     */
    fun startScaleScan(highPriority: Boolean = false): Boolean {
        if (!scaleScanner.start(scaleListener, highPriority)) {
            mainHandler.post { stopIfIdle() }
            return false
        }

        // Scanning has actually begun, which means the Bluetooth permission is
        // held, which is exactly when we are allowed to claim this type.
        enterForeground(ServiceForegroundType.MEDIA_AND_DEVICE)

        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            Log.i(TAG, "Scale scan timed out")
            stopScaleScan()
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(
            timeout,
            if (highPriority) ALARM_SCAN_TIMEOUT_MS else MANUAL_SCAN_TIMEOUT_MS
        )
        return true
    }

    fun stopScaleScan() {
        val wasScanning = scaleScanner.isScanning()
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        scaleScanner.stop()

        // Give the type back once the justification for it is gone.
        if (wasScanning) enterForeground(ServiceForegroundType.MEDIA_ONLY)
        mainHandler.post { stopIfIdle() }
    }

    fun isScanningScale(): Boolean = scaleScanner.isScanning()

    /**
     * Record a finished weigh-in and try to ship it.
     *
     * Storing first and uploading second is the whole point: the reading is the
     * user's data the moment the scale reports it, whether or not anything else
     * is reachable.
     */
    private fun recordWeighIn(reading: ScaleCodec.ScaleReading, distinctMeasurements: Int) {
        val store = ReadingStore(this)
        try {
            val isNew = store.insert(
                ReadingStore.Reading(
                    externalId = ScaleCodec.externalId(
                        reading.capturedAtMillis, reading.rawValue, reading.weightKg
                    ),
                    capturedAtUtc = ScaleCodec.utcIso(reading.capturedAtMillis),
                    weightKg = reading.weightKg,
                    impedance = reading.impedance,
                    rawValue = reading.rawValue,
                    alarmName = activeAlarmName.takeIf { mediaPlayer != null },
                    measurements = distinctMeasurements
                )
            )
            if (!isNew) return
        } finally {
            store.close()
        }

        AppSettings.prefs(this).edit()
            .putFloat(AppSettings.KEY_LAST_WEIGHT, reading.weightKg.toFloat())
            .putString(
                AppSettings.KEY_LAST_WEIGHT_AT,
                ScaleCodec.utcIso(reading.capturedAtMillis)
            )
            .apply()

        showWeightNotification(reading.weightKg)
        syncNow()
    }

    private fun showWeightNotification(weightKg: Double) {
        val notification = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Weighed in")
            .setContentText("%.1f kg".format(weightKg))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_WEIGHT_ID, notification)
    }

    // ─── Alarm control ───────────────────────────────────────────────

    /**
     * Decide how this alarm behaves, then ring it.
     *
     * A hard alarm can only be silenced by standing on the scale. That is only a
     * fair demand at home, where the scale is — so away from the home network it
     * degrades to a soft alarm and says why.
     */
    private fun startAlarm(
        id: String = "",
        name: String = "Alarm",
        kind: String = AlarmSchedule.KIND_HARD
    ) {
        val wantsHard = kind != AlarmSchedule.KIND_SOFT
        var effectivelyHard = wantsHard
        var downgradeReason: String? = null

        if (wantsHard) {
            val home = HomeNetwork.status(this)
            if (!home.isHome) {
                effectivelyHard = false
                downgradeReason = "Away from home (${home.reason}) — dismiss without the scale"
                Log.i(TAG, "Hard alarm downgraded: ${home.reason}")
            } else if (!scaleScanner.hasPermission()) {
                effectivelyHard = false
                downgradeReason = "Bluetooth scan permission missing — cannot reach the scale"
                Log.w(TAG, "Hard alarm downgraded: no BLE permission")
            }
        }

        activeAlarmId = id
        activeAlarmName = name
        activeAlarmKind = kind
        activeAlarmIsHard = effectivelyHard
        activeAlarmDowngradeReason = downgradeReason

        startAlarmPlayback(if (effectivelyHard) ASSET_HARD_ALARM else ASSET_SOFT_ALARM)

        if (effectivelyHard && !startScaleScan(highPriority = true)) {
            // Could not start scanning after all. Rather than trap the user, fall
            // back to the soft path.
            Log.e(TAG, "Scale scan failed to start — falling back to soft dismissal")
            activeAlarmIsHard = false
            activeAlarmDowngradeReason = "Could not start Bluetooth scan"
        }
    }

    private fun startAlarmPlayback(assetName: String) {
        try {
            // Idempotency: if the alarm is already playing, do NOT restart.
            // Restarting kills AlarmDismissActivity and wipes the input.
            if (mediaPlayer != null) {
                Log.i(TAG, "startAlarm() called but alarm already playing — ignoring")
                return
            }

            val am = audioManager
            if (am == null) {
                Log.e(TAG, "AudioManager is null — cannot start alarm")
                return
            }
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0
            )

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            am.requestAudioFocus(audioFocusRequest!!)

            // Fall back to the siren if the softer tone was never added, rather
            // than silently failing to ring at all.
            val resolvedAsset = if (assetExists(assetName)) assetName else ASSET_HARD_ALARM
            val afd = assets.openFd(resolvedAsset)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = true
                prepare()
                start()
            }
            afd.close()
            Log.i(TAG, "MediaPlayer created and playing $resolvedAsset")

            startVolumeGuard()
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(volumeReceiver, filter)
            }

            refreshServiceNotification()
            showAlarmNotification()
            launchDismissActivity()
        } catch (e: Exception) {
            Log.e(TAG, "startAlarm() FAILED", e)
        }
    }

    private fun assetExists(name: String): Boolean =
        runCatching { assets.openFd(name).close() }.isSuccess

    private fun launchDismissActivity() {
        val intent = Intent(this, AlarmDismissActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Direct activity launch failed: ${it.message}") }
    }

    private fun showAlarmNotification() {
        val dismissIntent = Intent(this, AlarmDismissActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val fullScreenPi = PendingIntent.getActivity(
            this, 0, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, App.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("ALARM")
            .setContentText(activeAlarmName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ALARM_ID, notification)
    }

    fun dismissAlarmNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ALARM_ID)
    }

    /**
     * Book this alarm again a few minutes out and go quiet.
     *
     * Returns when it will ring, or null if snooze is switched off. The alarm is
     * silenced exactly as a dismissal silences it — the difference is only that
     * something is left in AlarmManager.
     */
    fun snoozeAlarm(): Long? {
        val minutes = AppSettings.snoozeMinutes(this)
        if (minutes <= 0) return null

        val at = AlarmScheduler.snooze(
            this,
            id = activeAlarmId,
            name = activeAlarmName,
            kind = activeAlarmKind,
            minutes = minutes
        )
        stopAlarm()
        return at
    }

    fun stopAlarm() {
        volumeGuardRunning = false
        runCatching { unregisterReceiver(volumeReceiver) }

        mediaPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }.onFailure { Log.w(TAG, "MediaPlayer release error: ${it.message}") }
        }
        mediaPlayer = null

        val am = audioManager
        if (am != null && savedVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
        audioFocusRequest?.let { am?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null

        dismissAlarmNotification()

        // Release the radio. A hard alarm turns the scanner on, and nothing else
        // turns it off unless the scale completes a weigh-in — so dismissing with
        // the passphrase used to leave a BLE scan running for the full timeout,
        // every single morning. An open session is the one exception: the scale
        // is still settling and its final reading is the one worth keeping, so
        // let commitSession() stop the scan when it lands.
        if (scaleScanner.isScanning() && !scaleScanner.hasOpenSession()) {
            stopScaleScan()
        }

        sendBroadcast(Intent(ACTION_ALARM_STOPPED).setPackage(packageName))
        refreshServiceNotification()
        mainHandler.post { stopIfIdle() }
    }

    private fun enforceMaxVolume() {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) != max) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
    }

    private fun startVolumeGuard() {
        volumeGuardRunning = true
        mainHandler.post(object : Runnable {
            override fun run() {
                if (!volumeGuardRunning) return
                enforceMaxVolume()
                // The VOLUME_CHANGED_ACTION receiver above does the real work;
                // this is only a backstop for changes that arrive without a
                // broadcast. Twenty main-thread wakeups a second was overkill.
                mainHandler.postDelayed(this, 500)
            }
        })
    }
}
