package org.example.lanalarm

import android.app.Notification
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
        const val PREFS_NAME = "massalarme_prefs"
        const val KEY_SECRET = "shared_secret"

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

        startForeground(1, buildServiceNotification())

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

            return when (session.uri) {
                "/alarm" -> {
                    runOnMainAndWait { startAlarm() }
                    newFixedLengthResponse("Alarm triggered!")
                }
                "/stop" -> {
                    runOnMainAndWait { stopAlarm() }
                    newFixedLengthResponse("Alarm stopped")
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
        stopAlarm()

        val am = audioManager ?: return
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

        val afd = assets.openFd("trombetas.mp3")
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(attrs)
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            isLooping = true
            prepare()
            start()
        }
        afd.close()

        startVolumeGuard()
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(volumeReceiver, filter)
        }

        val dismissIntent = Intent(this, AlarmDismissActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(dismissIntent)

        Log.i(TAG, "Alarm started")
    }

    fun stopAlarm() {
        volumeGuardRunning = false

        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }

        mediaPlayer?.let { mp ->
            try {
                if (mp.isPlaying) mp.stop()
                mp.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "MediaPlayer release error: ${e.message}")
            }
        }
        mediaPlayer = null

        val am = audioManager
        if (am != null && savedVolume >= 0) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            savedVolume = -1
        }
        audioFocusRequest?.let { am?.abandonAudioFocusRequest(it) }
        audioFocusRequest = null

        Log.i(TAG, "Alarm stopped")
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
