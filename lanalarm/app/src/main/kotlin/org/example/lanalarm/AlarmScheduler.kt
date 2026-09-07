package org.example.lanalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject

/**
 * The phone's own alarm clock.
 *
 * The PC used to watch the clock and push `/alarm` over the LAN at the right
 * moment, which meant no PC — or no wifi — was the same as no alarm. The
 * schedule is data the phone holds and `AlarmManager` fires locally.
 *
 * Two registrations exist at a time, deliberately kept apart:
 *
 *  - the *next scheduled* alarm, replaced whenever the schedule changes;
 *  - a *snooze*, which is a one-off the user just asked for.
 *
 * They use different request codes so booking one never silently cancels the
 * other. Sharing a code would mean snoozing at 07:00 quietly discarded the 07:30
 * alarm, which is the sort of thing you only find out about the morning it
 * matters.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 0x4D41 // 'MA'
    private const val SNOOZE_REQUEST_CODE = 0x4D43

    const val ACTION_FIRE = "org.example.lanalarm.FIRE_ALARM"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_ALARM_NAME = "alarm_name"
    const val EXTRA_ALARM_KIND = "alarm_kind"
    const val EXTRA_SNOOZED = "snoozed"

    private const val KEY_SNOOZE_AT = "snooze_at"
    private const val KEY_SNOOZE_ALARM_ID = "snooze_alarm_id"

    /**
     * Register the next upcoming alarm, replacing any previously registered one.
     * Returns its trigger time, or null when nothing is scheduled.
     */
    fun rescheduleNext(context: Context): Long? {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return null
        val alarms = AlarmSchedule.parse(
            AppSettings.prefs(context).getString(AppSettings.KEY_ALARMS, null)
        )

        dropStaleSnooze(context, alarms)

        val next = AlarmSchedule.nextAlarm(alarms)
        if (next == null) {
            cancel(context)
            Log.i(TAG, "No upcoming alarms")
            return null
        }

        val (alarm, triggerAt) = next

        if (!canScheduleExact(alarmManager)) {
            // Without exact alarms the OS may delay us by minutes, which for an
            // alarm clock is the same as being broken. Say so loudly rather than
            // silently drifting.
            Log.e(TAG, "Exact alarms not permitted — alarm may fire late")
        }

        // setAlarmClock is the strongest guarantee Android offers: it survives
        // Doze, and the system shows it as a real alarm in the status bar.
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showIntent(context)),
            firePendingIntent(context, REQUEST_CODE, alarm.id, alarm.name, alarm.kind, false)
        )

        Log.i(TAG, "Next alarm '${alarm.name}' (${alarm.kind}) at ${ScaleCodec.utcIso(triggerAt)}")
        return triggerAt
    }

    fun cancel(context: Context) {
        cancelPending(context, REQUEST_CODE)
    }

    // ─── Snooze ──────────────────────────────────────────────────────

    /**
     * Ring this same alarm again in [minutes]. Returns when it will go off.
     *
     * The snooze keeps the alarm's id, name and kind, so a snoozed hard alarm
     * comes back hard: snoozing is asking for a few more minutes, not talking
     * the app out of its job.
     */
    fun snooze(context: Context, id: String, name: String, kind: String, minutes: Int): Long {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val at = System.currentTimeMillis() + minutes * 60_000L

        alarmManager?.setAlarmClock(
            AlarmManager.AlarmClockInfo(at, showIntent(context)),
            firePendingIntent(context, SNOOZE_REQUEST_CODE, id, name, kind, true)
        )

        AppSettings.prefs(context).edit()
            .putLong(KEY_SNOOZE_AT, at)
            .putString(KEY_SNOOZE_ALARM_ID, id)
            .apply()

        Log.i(TAG, "Snoozed '$name' for ${minutes}m")
        return at
    }

    /** When a snooze is due, or null. Past times are treated as gone. */
    fun pendingSnooze(context: Context): Long? =
        AppSettings.prefs(context).getLong(KEY_SNOOZE_AT, 0L)
            .takeIf { it > System.currentTimeMillis() }

    fun cancelSnooze(context: Context) {
        cancelPending(context, SNOOZE_REQUEST_CODE)
        clearSnoozeRecord(context)
    }

    fun clearSnoozeRecord(context: Context) {
        AppSettings.prefs(context).edit()
            .remove(KEY_SNOOZE_AT)
            .remove(KEY_SNOOZE_ALARM_ID)
            .apply()
    }

    /**
     * Drop a snooze whose alarm the user has since turned off or deleted.
     *
     * Without this, disabling an alarm you had just snoozed leaves it booked and
     * it rings anyway — which reads as the switch not working.
     */
    private fun dropStaleSnooze(context: Context, alarms: List<AlarmSchedule.Alarm>) {
        val prefs = AppSettings.prefs(context)
        if (prefs.getLong(KEY_SNOOZE_AT, 0L) <= 0L) return

        val snoozedId = prefs.getString(KEY_SNOOZE_ALARM_ID, null)
        // A snooze with no id came from a source we cannot re-check; leave it.
        if (snoozedId.isNullOrBlank()) return

        val alarm = alarms.firstOrNull { it.id == snoozedId }
        if (alarm == null || !alarm.isActive) {
            Log.i(TAG, "Snoozed alarm $snoozedId is gone — cancelling the snooze")
            cancelSnooze(context)
        }
    }

    // ─── Description for the UI ──────────────────────────────────────

    /** The headline: what rings next and how far away it is. */
    fun nextAlarmDescription(context: Context): String {
        val snoozeAt = pendingSnooze(context)
        val next = nextAlarm(context)

        // A snooze is usually sooner than anything on the schedule, and a
        // headline that ignores it is simply wrong about when the phone will
        // next make a noise.
        if (snoozeAt != null && (next == null || snoozeAt < next.second)) {
            return "Snoozed ${countdown(snoozeAt)}"
        }
        if (next == null) return "No upcoming alarms"
        return "${next.first.name} ${countdown(next.second)}"
    }

    /** The supporting line: when exactly, and what kind. */
    fun nextAlarmDetail(context: Context): String {
        val snoozeAt = pendingSnooze(context)
        val next = nextAlarm(context)

        val at = when {
            snoozeAt != null && (next == null || snoozeAt < next.second) -> snoozeAt
            next != null -> next.second
            else -> return ""
        }
        val when_ = java.text.SimpleDateFormat("EEEE d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(at))

        if (snoozeAt == at) return "$when_ · snoozed"
        val alarm = next?.first ?: return when_
        return "$when_ · " + if (alarm.isHard) "hard — needs the scale" else "soft — one tap"
    }

    private fun nextAlarm(context: Context): Pair<AlarmSchedule.Alarm, Long>? =
        AlarmSchedule.nextAlarm(
            AlarmSchedule.parse(AppSettings.prefs(context).getString(AppSettings.KEY_ALARMS, null))
        )

    private fun countdown(triggerAt: Long): String {
        val minutes = (triggerAt - System.currentTimeMillis()) / 60_000
        return when {
            minutes < 1 -> "now"
            minutes < 60 -> "in ${minutes}m"
            minutes < 1440 -> "in ${minutes / 60}h ${minutes % 60}m"
            else -> "in ${minutes / 1440}d ${(minutes % 1440) / 60}h"
        }
    }

    // ─── PendingIntent plumbing ──────────────────────────────────────

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun firePendingIntent(
        context: Context,
        requestCode: Int,
        id: String,
        name: String,
        kind: String,
        snoozed: Boolean
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, id)
            putExtra(EXTRA_ALARM_NAME, name)
            putExtra(EXTRA_ALARM_KIND, kind)
            putExtra(EXTRA_SNOOZED, snoozed)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPending(context: Context, requestCode: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
        PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun showIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(
            context, REQUEST_CODE + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Fires the alarm and immediately books the next one.
 *
 * Rescheduling here rather than in the service matters: if starting the service
 * fails for any reason, tomorrow's alarm is still registered.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AlarmScheduler.ACTION_FIRE -> {
                val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID).orEmpty()
                val name = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NAME) ?: "Alarm"
                val kind = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_KIND)
                    ?: AlarmSchedule.KIND_HARD
                val snoozed = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZED, false)

                Log.i(TAG, "Alarm '$name' ($kind) fired${if (snoozed) " from a snooze" else ""}")
                if (snoozed) {
                    AlarmScheduler.clearSnoozeRecord(context)
                } else {
                    retireOneShot(context, alarmId)
                }

                val serviceIntent = Intent(context, AlarmService::class.java).apply {
                    action = AlarmService.ACTION_START_ALARM
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_ALARM_NAME, name)
                    putExtra(AlarmScheduler.EXTRA_ALARM_KIND, kind)
                }
                context.startForegroundService(serviceIntent)

                AlarmScheduler.rescheduleNext(context)
            }

            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED -> {
                // Registered alarms do not survive any of these.
                Log.i(TAG, "Rescheduling after ${intent.action}")
                AlarmScheduler.rescheduleNext(context)
            }
        }
    }

    /** A "next" alarm fires once; disable it so it does not come back tomorrow. */
    private fun retireOneShot(context: Context, alarmId: String) {
        if (alarmId.isBlank()) return
        val prefs = AppSettings.prefs(context)
        val raw = prefs.getString(AppSettings.KEY_ALARMS, null) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return

        if (AlarmSchedule.disableOneShot(root, alarmId)) {
            prefs.edit().putString(AppSettings.KEY_ALARMS, root.toString()).apply()
            Log.i(TAG, "One-shot alarm $alarmId retired")
            // The schedule just changed without the UI asking.
            context.sendBroadcast(
                Intent(AlarmService.ACTION_ALARMS_CHANGED).setPackage(context.packageName)
            )
        }
    }
}
