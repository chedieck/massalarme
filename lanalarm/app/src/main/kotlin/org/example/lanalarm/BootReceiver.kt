package org.example.lanalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "Boot/update received (${intent.action})")

        // Book the alarm first, and without going through the service.
        //
        // Android drops every registered alarm on boot *and* on app update, so
        // this receiver is the only thing standing between a reinstall and a
        // morning with no alarm. It used to rely on starting the foreground
        // service to do the rescheduling, which is a background FGS start:
        // BOOT_COMPLETED is exempt from that restriction but MY_PACKAGE_REPLACED
        // is not, so on Android 12+ every app update threw, got swallowed by the
        // catch below, and left nothing scheduled until the app was next opened.
        // AlarmScheduler needs a Context and nothing else, so ask it directly.
        runCatching { AlarmScheduler.rescheduleNext(context) }
            .onSuccess { Log.i(TAG, "Next alarm rebooked after ${intent.action}: $it") }
            .onFailure { Log.e(TAG, "Could not rebook the next alarm: ${it.message}") }

        val serviceIntent = Intent(context, AlarmService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            // The alarm is already booked above, so this is a degraded state
            // (no scale listening until the app is opened), not a missed alarm.
            Log.e(TAG, "Failed to start service on ${intent.action}: ${e.message}")
        }
    }
}
