package com.example.lanalarm

import android.app.*
import android.content.*
import android.media.MediaPlayer
import android.provider.Settings
import fi.iki.elonen.NanoHTTPD

class AlarmService : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
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

    private fun ring() {
        val mp = MediaPlayer.create(
            this,
            Settings.System.DEFAULT_ALARM_ALERT_URI
        )
        mp.isLooping = true
        mp.start()
    }

    override fun onBind(intent: Intent?) = null

    private fun notification(): Notification =
        Notification.Builder(this, "alarm")
            .setContentTitle("LAN Alarm running")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .build()
}
