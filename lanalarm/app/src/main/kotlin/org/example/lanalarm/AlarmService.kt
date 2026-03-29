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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AlarmService : Service() {

    companion object {
        private const val TAG = "AlarmService"
        private const val NOTIFICATION_SERVICE_ID = 1
        private const val NOTIFICATION_ALARM_ID = 2
        const val PREFS_NAME = "massalarme_prefs"
        const val KEY_SECRET = "shared_secret"
        const val KEY_ALARMS = "alarms_json"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_PC_IP = "pc_ip"
        const val KEY_PC_PORT = "pc_port"

        @Volatile
        var instance: AlarmService? = null
            private set
    }

    private var httpServer: AlarmHttpServer? = null
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioManager: AudioManager? = null
    private var savedVolume: Int = -1
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeGuardRunning = false

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
    }

    private fun buildServiceNotification(): Notification {
        return NotificationCompat.Builder(this, App.CHANNEL_SERVICE)
            .setContentTitle("MassAlarme running")
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
        override fun serve(session: IHTTPSession): Response {
            val storedSecret = getStoredSecret()
            if (storedSecret.isNullOrEmpty()) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    MIME_PLAINTEXT,
                    "Secret not configured"
                )
            }

            if (!validateKey(session)) {
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN,
                    MIME_PLAINTEXT,
                    "Invalid key"
                )
            }

            // Store the PC's IP so the phone can fetch alarms later
            val pcIp = session.remoteIpAddress?.removePrefix("/")
            if (!pcIp.isNullOrBlank()) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PC_IP, pcIp)
                    .apply()
                Log.d(TAG, "Stored PC IP: $pcIp")
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
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Failed to sync alarms"
                        )
                    }
                }
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "Not found"
                )
            }
        }
    }

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
            stopAlarm()

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

    fun stopAlarm() {
        Log.i(TAG, "stopAlarm() called (mediaPlayer=${mediaPlayer != null})")
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

        Log.i(TAG, "Alarm stopped cleanly")
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
        httpServer?.stop()
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
