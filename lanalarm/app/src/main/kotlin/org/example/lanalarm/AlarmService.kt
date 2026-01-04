package org.example.lanalarm

import android.os.Build
import androidx.core.app.NotificationCompat
import android.app.*
import android.content.*
import android.media.MediaPlayer
import android.provider.Settings
import fi.iki.elonen.NanoHTTPD

class AlarmService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        Thread { startServer() }.start()
    }

    private fun startServer() {
        val server = object : NanoHTTPD(8080) {
            override fun serve(session: IHTTPSession): Response {
                ring()
                return newFixedLengthResponse("OK")
            }
        }
        server.start()
    }

    private fun createNotification(): Notification {
        val channelId = "alarm_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Alarm",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("LAN Alarm running")
            .setContentText("Prepare a balança")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }


    private fun ring() {
        val mp = MediaPlayer.create(
            this,
            Settings.System.DEFAULT_ALARM_ALERT_URI
        )
        mp.isLooping = true
        mp.start()
    }

    override fun onBind(intent: Intent?) = null
}
