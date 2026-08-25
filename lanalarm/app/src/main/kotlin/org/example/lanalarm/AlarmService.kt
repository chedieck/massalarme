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
import android.net.ConnectivityManager
import android.net.Network
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
import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val NOTIFICATION_SERVICE_ID = 1
        private const val NOTIFICATION_ALARM_ID = 2
        private const val NOTIFICATION_WEIGHT_ID = 4
        const val PREFS_NAME = AppSettings.PREFS_NAME
        const val KEY_SECRET = AppSettings.KEY_SECRET
        const val KEY_ALARMS = AppSettings.KEY_ALARMS
        const val KEY_LAST_SYNC = AppSettings.KEY_LAST_SYNC
        const val KEY_PC_IP = AppSettings.KEY_PC_IP
        const val KEY_PC_PORT = AppSettings.KEY_PC_PORT
        const val ACTION_ALARM_STOPPED = "org.example.lanalarm.ALARM_STOPPED"
        const val ACTION_START_ALARM = "org.example.lanalarm.START_ALARM"
        const val ACTION_UPLOAD_READINGS = "org.example.lanalarm.UPLOAD_READINGS"
        private const val DEFAULT_PC_PORT = AppSettings.DEFAULT_PC_PORT

        /**
         * Reconnect backoff. The PC is a desktop that spends most of the day
         * asleep, so a failed connection is the normal case, not an incident.
         * A flat 15s retry meant ~5,700 pointless TCP connects a day, each one
         * waking the wifi radio and the CPU; it was the single largest thing
         * this app did with the user's battery. Back off to a quarter hour and
         * let the network callback below cut the wait short when wifi returns.
         */
        private const val WS_RECONNECT_MIN_MS = 15_000L
        private const val WS_RECONNECT_MAX_MS = 15 * 60 * 1000L

        /**
         * OkHttp's own keep-alive, which runs on its connection pool rather than
         * a handler chain on the main thread and drops the socket by itself when
         * a pong does not come back.
         */
        private const val WS_PING_INTERVAL_SEC = 60L

        /** Hard alarms use the siren; soft ones should not wake the neighbours. */
        private const val ASSET_HARD_ALARM = "trombetas.mp3"
        private const val ASSET_SOFT_ALARM = "soft.mp3"

        /**
         * Give up scanning for the scale eventually, so BLE is not left running.
         * Six minutes, not thirty: someone who is going to weigh in does it as
         * soon as the alarm stops, and the other twenty-four minutes were pure
         * radio burn.
         */
        private const val SCALE_SCAN_TIMEOUT_MS = 6 * 60 * 1000L

        @Volatile
        var instance: AlarmService? = null
            private set

        @Volatile
        var wsConnected: Boolean = false
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

        /** Why a hard alarm was downgraded, for the dismiss screen to explain. */
        @Volatile
        var activeAlarmDowngradeReason: String? = null
            private set
    }

    private var httpServer: AlarmHttpServer? = null
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var savedVolume: Int = -1
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeGuardRunning = false

    private var wsClient: WebSocket? = null
    private val okHttp = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(WS_PING_INTERVAL_SEC, TimeUnit.SECONDS)
        .build()
    private var wsReconnectScheduled = false
    private var wsBackoffMs = WS_RECONNECT_MIN_MS
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNotifiedWsState: Boolean? = null

    private lateinit var scaleScanner: ScaleScanner
    private val uploadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var scanTimeoutRunnable: Runnable? = null

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (mediaPlayer != null) {
                enforceMaxVolume()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        audioManager = getSystemService(AudioManager::class.java)

        // Media playback only, for now. The manifest also declares
        // `connectedDevice` for BLE scanning, but that type may only be claimed
        // while a Bluetooth permission is actually held — and on first launch it
        // is not. Claiming it here would throw and take the whole app down.
        enterForeground(ServiceForegroundType.MEDIA_ONLY)

        scaleScanner = ScaleScanner(this)

        httpServer = AlarmHttpServer(8080)
        httpServer?.start()
        Log.i(TAG, "HTTP server started on port 8080")

        registerNetworkCallback()
        connectWebSocket()

        // The phone owns the schedule now: register the next alarm as soon as
        // the service is alive, not when the PC gets round to telling us.
        AlarmScheduler.rescheduleNext(this)

        // Anything captured while the PC was unreachable goes out now.
        uploadReadings()
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
     * the state the app is in the first time it is ever opened. Passing the types
     * explicitly keeps the BLE justification available without making the service
     * impossible to create.
     */
    private fun enterForeground(type: ServiceForegroundType) {
        val notification = buildServiceNotification()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Types are neither accepted nor validated here.
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
            // Never let a foreground-service technicality kill the alarm. Fall
            // back to the plainest claim we know is allowed.
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

    // ─── Scale + reading upload ──────────────────────────────────────

    /** Drain the outbound queue on a background thread. Safe to call often. */
    fun uploadReadings() {
        uploadExecutor.execute {
            when (val result = ReadingUploader(this).upload()) {
                is ReadingUploader.Result.Delivered ->
                    Log.i(TAG, "Uploaded ${result.count} reading(s), ${result.remaining} pending")
                is ReadingUploader.Result.Failed ->
                    Log.w(TAG, "Upload failed: ${result.reason}")
                is ReadingUploader.Result.NotConfigured ->
                    Log.d(TAG, "Upload skipped: ${result.reason}")
                ReadingUploader.Result.NothingToDo -> Unit
            }
        }
    }

    /**
     * Record a finished weigh-in and try to ship it.
     *
     * Storing first and uploading second is the whole point: the reading is the
     * user's data the moment the scale reports it, whether or not the PC is up.
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
        uploadReadings()
    }

    private val scaleListener = object : ScaleScanner.ScaleListener {
        override fun onStableWeight(reading: ScaleCodec.ScaleReading) {
            // The alarm stops on the first stable reading — no standing on the
            // scale waiting for the session to settle.
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
     *   shut up. Manual listening uses the cheaper mode.
     */
    fun startScaleScan(highPriority: Boolean = false): Boolean {
        if (!scaleScanner.start(scaleListener, highPriority)) return false

        // Scanning has actually begun, which means the Bluetooth permission is
        // held, which is exactly when we are allowed to claim this type.
        enterForeground(ServiceForegroundType.MEDIA_AND_DEVICE)

        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            Log.i(TAG, "Scale scan timed out")
            stopScaleScan()
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCALE_SCAN_TIMEOUT_MS)
        return true
    }

    fun stopScaleScan() {
        val wasScanning = scaleScanner.isScanning()
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        scaleScanner.stop()

        // Give the type back once the justification for it is gone.
        if (wasScanning) enterForeground(ServiceForegroundType.MEDIA_ONLY)
    }

    fun isScanningScale(): Boolean = scaleScanner.isScanning()

    /**
     * Tell the PC which ontoplano tasks should become alarms.
     *
     * The rule is edited on the phone but enforced on the PC, because that is
     * the side holding the ontoplano token and doing the fetching.
     */
    fun pushOntoplanoRule(pattern: String, kind: String) {
        sendWsMessage(
            JSONObject().apply {
                put("type", "set_ontoplano_rule")
                put("pattern", pattern)
                put("kind", kind)
            }.toString()
        )
    }

    /** Push the local schedule to the PC so the two stay merged. */
    fun pushAlarmsToPC(alarmsJson: String) {
        sendWsMessage(
            JSONObject().apply {
                put("type", "update_alarms")
                put("data", JSONObject(alarmsJson))
            }.toString()
        )
    }

    // ─── WebSocket client ────────────────────────────────────────────

    private fun connectWebSocket() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pcIp = prefs.getString(KEY_PC_IP, null)
        val secret = prefs.getString(KEY_SECRET, null)

        // Unpaired is not a transient failure — there is nothing to retry until
        // the user scans a QR code, and that path calls reconnectWebSocketNow().
        if (pcIp.isNullOrBlank() || secret.isNullOrBlank()) {
            Log.d(TAG, "WS: not paired with a PC yet, staying idle")
            return
        }

        // The PC is a LAN peer. Off wifi there is no route to it, so a connect
        // attempt can only burn the mobile radio to reach a private address.
        // The network callback wakes us the moment wifi is back.
        if (!HomeNetwork.isWifiConnected(this)) {
            Log.d(TAG, "WS: no wifi, staying idle until the network returns")
            return
        }

        val pcPort = prefs.getInt(KEY_PC_PORT, DEFAULT_PC_PORT)
        val url = "ws://$pcIp:$pcPort/ws?key=$secret"
        Log.i(TAG, "WS: connecting to $url")

        val request = Request.Builder().url(url).build()
        wsClient = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WS: connected")
                wsConnected = true
                wsReconnectScheduled = false
                wsBackoffMs = WS_RECONNECT_MIN_MS
                notifyWsStatus(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS: server closing ($code: $reason)")
                wsConnected = false
                notifyWsStatus(false)
                webSocket.close(1000, null)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS: connection failed: ${t.message}")
                wsConnected = false
                notifyWsStatus(false)
                scheduleReconnect()
            }
        })
    }

    private fun handleWsMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "alarms" -> {
                    val remoteAlarms = json.optJSONObject("data") ?: return
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    val localAlarms = prefs.getString(KEY_ALARMS, null)
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?: JSONObject().apply {
                            put("version", 2)
                            put("alarms", JSONArray())
                        }
                    val mergedAlarms = mergeAlarms(localAlarms, remoteAlarms)

                    prefs.edit()
                        .putString(KEY_ALARMS, mergedAlarms.toString())
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .apply()

                    sendWsMessage(
                        JSONObject().apply {
                            put("type", "update_alarms")
                            put("data", mergedAlarms)
                        }.toString()
                    )
                    // The phone fires its own alarms, so a schedule change has to
                    // reach AlarmManager or the edit silently does nothing.
                    AlarmScheduler.rescheduleNext(this)
                    Log.i(TAG, "WS: alarms merged with PC")
                }
                "weight_update" -> {
                    val weightKg = json.optDouble("weight_kg", -1.0)
                    if (weightKg > 0) {
                        Log.i(TAG, "WS: weight update: %.1f kg".format(weightKg))
                        showWeightNotification(weightKg)
                    }
                }
                else -> Log.d(TAG, "WS: unknown message type: ${json.optString("type")}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS: failed to parse message: ${e.message}")
        }
    }

    private fun mergeAlarms(local: JSONObject, remote: JSONObject): JSONObject {
        val mergedById = linkedMapOf<String, JSONObject>()

        fun copyAlarm(alarm: JSONObject): JSONObject = JSONObject(alarm.toString())

        fun mergeFrom(source: JSONObject, replaceIfNewer: Boolean) {
            val alarms = source.optJSONArray("alarms") ?: JSONArray()
            for (i in 0 until alarms.length()) {
                val alarm = alarms.optJSONObject(i) ?: continue
                val id = alarm.optString("id")
                if (id.isBlank()) continue

                val candidate = copyAlarm(alarm)
                val existing = mergedById[id]
                if (existing == null) {
                    mergedById[id] = candidate
                    continue
                }

                if (replaceIfNewer) {
                    val existingUpdatedAt = existing.optLong("updated_at", Long.MIN_VALUE)
                    val candidateUpdatedAt = candidate.optLong("updated_at", Long.MIN_VALUE)
                    if (candidateUpdatedAt > existingUpdatedAt) {
                        mergedById[id] = candidate
                    }
                }
            }
        }

        mergeFrom(local, replaceIfNewer = false)
        mergeFrom(remote, replaceIfNewer = true)

        val cutoffMs = System.currentTimeMillis() - (30L * 86400 * 1000)
        val pruned = mergedById.values.filter { alarm ->
            !(alarm.optBoolean("deleted", false) && alarm.optLong("updated_at", Long.MAX_VALUE) < cutoffMs)
        }

        return JSONObject().apply {
            put("version", 2)
            put("alarms", JSONArray().apply {
                pruned.forEach { put(it) }
            })
        }
    }

    private fun showWeightNotification(weightKg: Double) {
        val nm = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Good morning!")
            .setContentText("%.1f kg".format(weightKg))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_WEIGHT_ID, notification)
    }

    /**
     * Book the next attempt, doubling the wait each time up to a quarter hour.
     *
     * Jitter keeps a phone that has just come back onto wifi from hammering the
     * PC in lockstep with whatever else woke up at the same moment.
     */
    private fun scheduleReconnect() {
        if (wsReconnectScheduled) return
        wsReconnectScheduled = true

        val jitter = (wsBackoffMs / 4).coerceAtLeast(1L)
        val delay = wsBackoffMs + (0 until jitter).random()
        wsBackoffMs = (wsBackoffMs * 2).coerceAtMost(WS_RECONNECT_MAX_MS)

        Log.d(TAG, "WS: next attempt in ${delay / 1000}s")
        mainHandler.postDelayed(wsReconnectRunnable, delay)
    }

    private val wsReconnectRunnable = Runnable {
        wsReconnectScheduled = false
        connectWebSocket()
    }

    fun reconnectWebSocketNow() {
        wsClient?.cancel()
        wsClient = null
        mainHandler.removeCallbacks(wsReconnectRunnable)
        wsReconnectScheduled = false
        wsBackoffMs = WS_RECONNECT_MIN_MS
        connectWebSocket()
    }

    /**
     * Watch for wifi coming and going.
     *
     * This is what makes the long backoff above safe: rather than polling for a
     * network that is not there, the service sleeps and lets the system say when
     * something changed. Reconnecting is then immediate instead of up to fifteen
     * minutes late.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (wsConnected) return@post
                    if (!HomeNetwork.isWifiConnected(this@AlarmService)) return@post
                    Log.i(TAG, "Wifi available, reconnecting WS")
                    reconnectWebSocketNow()
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost, dropping WS until it returns")
                mainHandler.post {
                    mainHandler.removeCallbacks(wsReconnectRunnable)
                    wsReconnectScheduled = false
                    wsConnected = false
                    wsClient?.cancel()
                    wsClient = null
                    notifyWsStatus(false)
                }
            }
        }
        networkCallback = callback
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure {
                Log.w(TAG, "Could not watch the network: ${it.message}")
                networkCallback = null
            }
    }

    private fun unregisterNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    /**
     * Reflect the link state in the one notification the service already owns.
     *
     * This used to post a second, ongoing notification on every single failure.
     * At a retry every fifteen seconds that was thousands of notification posts
     * a day, each waking SystemUI to re-render — which is the likeliest reason
     * the phone stuttered on unlock. Only real transitions are worth reporting.
     */
    private fun notifyWsStatus(connected: Boolean) {
        if (lastNotifiedWsState == connected) return
        lastNotifiedWsState = connected
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_SERVICE_ID, buildServiceNotification())
    }

    // ─── HTTP server (for PC → phone commands) ──────────────────────

    private fun buildServiceNotification(): Notification {
        val link = if (wsConnected) "Connected to PC" else "PC not reachable"
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle("Massalarme running")
            .setContentText("Waiting for alarm trigger · $link")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun getStoredSecret(): String? {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_SECRET, null)
    }

    private fun validateKey(session: NanoHTTPD.IHTTPSession): Boolean {
        val secret = getStoredSecret() ?: return false
        return session.parms?.get("key") == secret
    }

    private inner class AlarmHttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): NanoHTTPD.Response {
            val storedSecret = getStoredSecret()
            if (storedSecret.isNullOrEmpty()) {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Secret not configured"
                )
            }

            if (!validateKey(session)) {
                return newFixedLengthResponse(
                    NanoHTTPD.Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "Invalid key"
                )
            }

            val pcIp = session.remoteIpAddress?.removePrefix("/")
            if (!pcIp.isNullOrBlank()) {
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val oldIp = prefs.getString(KEY_PC_IP, null)
                prefs.edit().putString(KEY_PC_IP, pcIp).apply()
                if (oldIp != pcIp) {
                    Log.i(TAG, "PC IP updated: $oldIp -> $pcIp, reconnecting WS")
                    reconnectWebSocketNow()
                }
            }

            return when (session.uri) {
                "/alarm" -> {
                    val alreadyPlaying = mediaPlayer != null
                    runOnMainAndWait { startAlarm() }
                    if (alreadyPlaying) {
                        newFixedLengthResponse("Already playing")
                    } else {
                        newFixedLengthResponse("Alarm triggered!")
                    }
                }
                "/stop" -> {
                    runOnMainAndWait { stopAlarm() }
                    newFixedLengthResponse("Alarm stopped")
                }
                "/status" -> {
                    val json = JSONObject().apply {
                        put("alarm_active", mediaPlayer != null)
                    }
                    newFixedLengthResponse(
                        NanoHTTPD.Response.Status.OK,
                        "application/json",
                        json.toString()
                    )
                }
                "/sync-alarms" -> {
                    val files = HashMap<String, String>()
                    try {
                        session.parseBody(files)
                        val body = files["postData"] ?: ""
                        val remoteAlarms = JSONObject(body)
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        val localAlarms = prefs.getString(KEY_ALARMS, null)
                            ?.let { runCatching { JSONObject(it) }.getOrNull() }
                            ?: JSONObject().apply {
                                put("version", 2)
                                put("alarms", JSONArray())
                            }
                        val mergedAlarms = mergeAlarms(localAlarms, remoteAlarms)

                        prefs.edit()
                            .putString(KEY_ALARMS, mergedAlarms.toString())
                            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                            .apply()
                        AlarmScheduler.rescheduleNext(this@AlarmService)
                        newFixedLengthResponse("Alarms synced")
                    } catch (e: Exception) {
                        Log.w(TAG, "Alarm sync failed: ${e.message}")
                        newFixedLengthResponse(
                            NanoHTTPD.Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Failed to sync alarms"
                        )
                    }
                }
                else -> newFixedLengthResponse(
                    NanoHTTPD.Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found"
                )
            }
        }
    }

    // ─── Alarm control ───────────────────────────────────────────────

    private fun runOnMainAndWait(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                action()
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    /**
     * Decide how this alarm behaves, then ring it.
     *
     * A hard alarm can only be silenced by standing on the scale. That is only
     * a fair demand at home, where the scale is — so away from the home network
     * it degrades to a soft alarm and says why.
     */
    private fun startAlarm(
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

        activeAlarmName = name
        activeAlarmIsHard = effectivelyHard
        activeAlarmDowngradeReason = downgradeReason

        startAlarmPlayback(if (effectivelyHard) ASSET_HARD_ALARM else ASSET_SOFT_ALARM)

        if (effectivelyHard) {
            if (!startScaleScan(highPriority = true)) {
                // Could not start scanning after all. Rather than trap the user,
                // fall back to the soft path — the passphrase still works either way.
                Log.e(TAG, "Scale scan failed to start — falling back to soft dismissal")
                activeAlarmIsHard = false
                activeAlarmDowngradeReason = "Could not start Bluetooth scan"
            }
        }
    }

    private fun startAlarmPlayback(assetName: String) {
        try {
            // Idempotency: if alarm is already playing, do NOT restart.
            // Restarting would kill AlarmDismissActivity and wipe password input.
            if (mediaPlayer != null) {
                Log.i(TAG, "startAlarm() called but alarm already playing — ignoring")
                return
            }

            Log.i(TAG, "startAlarm() called — stopping any previous alarm first")
            stopAlarm(sendDismiss = false)

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
            Log.i(TAG, "Volume set to max (saved=$savedVolume, max=${am.getStreamMaxVolume(AudioManager.STREAM_ALARM)})")

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(false)
                .build()
            am.requestAudioFocus(audioFocusRequest!!)
            Log.d(TAG, "Audio focus acquired")

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
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(volumeReceiver, filter)
            }

            showAlarmNotification()
            launchDismissActivity()

            Log.i(TAG, "Alarm started successfully")
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
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Direct activity launch failed: ${e.message}")
        }
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
            .setContentText("Tap to dismiss")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(fullScreenPi)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ALARM_ID, notification)
    }

    fun dismissAlarmNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ALARM_ID)
    }

    fun stopAlarm(sendDismiss: Boolean = true) {
        Log.i(TAG, "stopAlarm() called (mediaPlayer=${mediaPlayer != null}, sendDismiss=$sendDismiss)")
        volumeGuardRunning = false

        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
        }

        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
                Log.d(TAG, "MediaPlayer stopped and released")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "MediaPlayer release error: ${e.message}")
            }
        }
        mediaPlayer = null

        val am = audioManager
        if (am != null && savedVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            Log.d(TAG, "Volume restored to $savedVolume")
            savedVolume = -1
        }
        audioFocusRequest?.let { am?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null

        dismissAlarmNotification()

        // Release the radio. A hard alarm turns the scanner on, and until now
        // nothing turned it off unless the scale itself completed a weigh-in —
        // so dismissing with the passphrase left an unfiltered BLE scan running
        // for the full timeout, every single morning. An open session is the one
        // exception: the scale is still settling and its final reading is the
        // one worth keeping, so let commitSession() stop the scan when it lands.
        if (scaleScanner.isScanning() && !scaleScanner.hasOpenSession()) {
            stopScaleScan()
        }

        if (sendDismiss) {
            sendWsMessage(JSONObject().apply { put("type", "alarm_dismissed") }.toString())
        }

        sendBroadcast(Intent(ACTION_ALARM_STOPPED).setPackage(packageName))

        Log.i(TAG, "Alarm stopped cleanly")
    }

    fun sendWsMessage(jsonStr: String) {
        wsClient?.send(jsonStr) ?: Log.w(TAG, "WS: cannot send, not connected")
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
                // this is only a backstop for volume changes that arrive without
                // a broadcast. Twenty main-thread wakeups a second was overkill.
                mainHandler.postDelayed(this, 500)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ALARM -> {
                val name = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NAME) ?: "Alarm"
                val kind = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_KIND)
                    ?: AlarmSchedule.KIND_HARD
                Log.i(TAG, "Starting alarm '$name' ($kind) from the phone's own schedule")
                runOnMainAndWait { startAlarm(name, kind) }
            }
            ACTION_UPLOAD_READINGS -> uploadReadings()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        wsConnected = false
        mainHandler.removeCallbacks(wsReconnectRunnable)
        unregisterNetworkCallback()
        wsClient?.cancel()
        wsClient = null
        httpServer?.stop()
        stopScaleScan()
        uploadExecutor.shutdown()
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
