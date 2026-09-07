package org.example.lanalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Puts the alarm back after a reboot or an app update.
 *
 * This receiver used to start the foreground service and rely on that to do the
 * rescheduling. It no longer starts anything: there is no background work to
 * resume, and starting a service here was both a battery cost and a bug —
 * `BOOT_COMPLETED` is exempt from Android 12's background foreground-service
 * restriction but `MY_PACKAGE_REPLACED` is not, so every app update threw, got
 * swallowed, and left nothing scheduled until the app was next opened.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // Android drops every registered alarm on boot *and* on app update, so
        // this is the only thing standing between a reinstall and a morning with
        // no alarm. AlarmScheduler needs a Context and nothing else.
        runCatching { AlarmScheduler.rescheduleNext(context) }
            .onSuccess { Log.i(TAG, "Next alarm rebooked after ${intent.action}: $it") }
            .onFailure { Log.e(TAG, "Could not rebook the next alarm: ${it.message}") }

        // Anything still queued for ontoplano gets picked up by the system when
        // it next has a network, rather than by us holding a process open.
        val store = ReadingStore(context)
        val pending = try {
            store.pendingCount()
        } finally {
            store.close()
        }
        if (pending > 0) SyncRetryJob.schedule(context)
    }
}
