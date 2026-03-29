package org.example.lanalarm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes

class App : Application() {
    companion object {
        const val CHANNEL_SERVICE = "massalarme_service"
        const val CHANNEL_ALARM = "massalarme_alarm"
    }

    override fun onCreate() {
        super.onCreate()

        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Massalarme Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the alarm listener running"
            }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                "Massalarme Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm notifications that bypass Do Not Disturb"
                setBypassDnd(true)
                setSound(
                    null,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
    }
}
