package org.example.lanalarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
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
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val NOTIFICATION_SERVICE_ID = 1
        private const val NOTIFICATION_ALARM_ID = 2
        private const val NOTIFICATION_WS_ID = 3
        private const val NOTIFICATION_WEIGHT_ID = 4
        const val PREFS_NAME = "massalarme_prefs"
        const val KEY_SECRET = "shared_secret"
        const val KEY_ALARMS = "alarms_json"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_PC_IP = "pc_ip"
        const val KEY_PC_PORT = "pc_port"
        const val ACTION_ALARM_STOPPED = "org.example.lanalarm.ALARM_STOPPED"
        private const val DEFAULT_PC_PORT = 8888
        private const val WS_RECONNECT_MS = 15_000L
        private const val WS_PING_INTERVAL_MS = 30_000L

        @Volatile
        var instance: AlarmService? = null
            private set

        @Volatile
        var wsConnected: Boolean = false
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
        .build()
    private var wsReconnectScheduled = false

    private val wsPingRunnable = object : Runnable {
        override fun run() {
            val ws = wsClient
            if (ws != null && wsConnected) {
                try {
                    // OkHttp uses pong frames internally; sending empty string tests the pipe
                    val ok = ws.send("")
                    if (!ok) {
                        Log.w(TAG, "WS: ping send failed, forcing reconnect")
                        wsConnected = false
                        notifyWsStatus(false)
                        ws.cancel()
                        wsClient = null
                        scheduleReconnect()
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WS: ping exception: ${e.message}, forcing reconnect")
                    wsConnected = false
                    notifyWsStatus(false)
                    ws.cancel()
                    wsClient = null
                    scheduleReconnect()
                    return
                }
            }
            mainHandler.postDelayed(this, WS_PING_INTERVAL_MS)
        }
    }

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

        startForeground(NOTIFICATION_SERVICE_ID, buildServiceNotification())

        httpServer = AlarmHttpServer(8080)
        httpServer?.start()
        Log.i(TAG, "HTTP server started on port 8080")

        connectWebSocket()
    }

    // ─── WebSocket client ────────────────────────────────────────────

    private fun connectWebSocket() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pcIp = prefs.getString(KEY_PC_IP, null)
        val secret = prefs.getString(KEY_SECRET, null)

        if (pcIp.isNullOrBlank() || secret.isNullOrBlank()) {
            Log.d(TAG, "WS: no PC IP or secret yet, will retry in ${WS_RECONNECT_MS / 1000}s")
            scheduleReconnect()
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
                notifyWsStatus(true)
                mainHandler.removeCallbacks(wsPingRunnable)
                mainHandler.postDelayed(wsPingRunnable, WS_PING_INTERVAL_MS)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WS: server closing ($code: $reason)")
                wsConnected = false
                mainHandler.removeCallbacks(wsPingRunnable)
                notifyWsStatus(false)
                webSocket.close(1000, null)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS: connection failed: ${t.message}")
                wsConnected = false
                mainHandler.removeCallbacks(wsPingRunnable)
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
                    val data = json.optJSONObject("data")?.toString() ?: return
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_ALARMS, data)
                        .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                        .apply()
                    Log.i(TAG, "WS: alarms updated from PC")
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

    private fun scheduleReconnect() {
        if (wsReconnectScheduled) return
        wsReconnectScheduled = true
        mainHandler.postDelayed(wsReconnectRunnable, WS_RECONNECT_MS)
    }

    private val wsReconnectRunnable = Runnable {
        wsReconnectScheduled = false
        connectWebSocket()
    }

    fun reconnectWebSocketNow() {
        wsClient?.cancel()
        wsClient = null
        mainHandler.removeCallbacks(wsReconnectRunnable)
        mainHandler.removeCallbacks(wsPingRunnable)
        wsReconnectScheduled = false
        connectWebSocket()
    }

    private fun notifyWsStatus(connected: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        if (connected) {
            val notification = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Massalarme")
                .setContentText("Connected to PC")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_WS_ID, notification)
        } else {
            val notification = NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Massalarme")
                .setContentText("Disconnected from PC — retrying...")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOngoing(true)
                .build()
            nm.notify(NOTIFICATION_WS_ID, notification)
        }
    }

    // ─── HTTP server (for PC → phone commands) ──────────────────────

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle("Massalarme running")
            .setContentText("Waiting for alarm trigger")
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
                    runOnMainAndWait { startAlarm() }
                    newFixedLengthResponse("Alarm triggered!")
                }
                "/stop" -> {
                    runOnMainAndWait { stopAlarm() }
                    newFixedLengthResponse("Alarm stopped")
                }
                "/sync-alarms" -> {
                    val files = HashMap<String, String>()
                    try {
                        session.parseBody(files)
                        val body = files["postData"] ?: ""
                        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_ALARMS, body)
                            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                            .apply()
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

    private fun startAlarm() {
        try {
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

            val afd = assets.openFd("trombetas.mp3")
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = true
                prepare()
                start()
            }
            afd.close()
            Log.i(TAG, "MediaPlayer created and playing trombetas.mp3")

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
                mainHandler.postDelayed(this, 50)
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        wsConnected = false
        mainHandler.removeCallbacks(wsPingRunnable)
        mainHandler.removeCallbacks(wsReconnectRunnable)
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_WS_ID)
        wsClient?.cancel()
        wsClient = null
        httpServer?.stop()
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
