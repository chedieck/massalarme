package org.example.lanalarm

import android.app.*
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

class AlarmService : Service() {

    private var httpServer: MyHttpServer? = null
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, createNotification())

        httpServer = MyHttpServer(8080)
        httpServer?.start()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "alarm_channel",
                "LAN Alarm Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the alarm listener running"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "alarm_channel")
            .setContentTitle("LAN Alarm running")
            .setContentText("Prepare a balança – waiting for trigger")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private inner class MyHttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            return when (session.uri) {
                "/alarm" -> {
                    ringAlarm()
                    newFixedLengthResponse("Alarm triggered!")
                }
                "/stop" -> {
                    stopAlarm()
                    newFixedLengthResponse("Alarm stopped")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
            }
        }
    }

    private fun ringAlarm() {
        stopAlarm()

        val afd: AssetFileDescriptor = assets.openFd("trombetas.mp3")

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            )
            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            isLooping = true
            prepare()
            start()
        }

        afd.close()
    }

    private fun stopAlarm() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY // Restart if killed by system
    }

    override fun onDestroy() {
        httpServer?.stop()
        stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
