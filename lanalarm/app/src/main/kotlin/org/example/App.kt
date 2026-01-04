package com.example.lanalarm

import android.app.*

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        val channel = NotificationChannel(
            "alarm",
            "LAN Alarm",
            NotificationManager.IMPORTANCE_MIN
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
