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
 * Previously the PC watched the clock and pushed `/alarm` over the LAN at the
 * right moment, which meant no PC (or no wifi) was the same as no alarm. Now the
 * schedule is data the phone holds and `AlarmManager` fires locally. The PC is
 * only a peer to sync the schedule with.
 *
 * Only the *next* alarm is registered at a time. It reschedules after every
 * fire, on boot, on time changes, and whenever the schedule is edited.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 0x4D41 // 'MA'

    const val ACTION_FIRE = "org.example.lanalarm.FIRE_ALARM"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_ALARM_NAME = "alarm_name"
    const val EXTRA_ALARM_KIND = "alarm_kind"

    /**
     * Register the next upcoming alarm, replacing any previously registered one.
     * Returns its trigger time, or null when nothing is scheduled.
     */
    fun rescheduleNext(context: Context): Long? {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return null
        val alarms = AlarmSchedule.parse(
            AppSettings.prefs(context).getString(AppSettings.KEY_ALARMS, null)
        )

        val next = AlarmSchedule.nextAlarm(alarms)
        if (next == null) {
            cancel(context)
            Log.i(TAG, "No upcoming alarms")
            return null
        }

        val (alarm, triggerAt) = next
        val intent = firePendingIntent(context, alarm)

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
            intent
        )

        Log.i(
            TAG,
            "Next alarm '${alarm.name}' (${alarm.kind}) at ${ScaleCodec.utcIso(triggerAt)}"
        )
        return triggerAt
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    /** The headline: what rings next and how far away it is. */
    fun nextAlarmDescription(context: Context): String {
        val next = nextAlarm(context) ?: return "No upcoming alarms"
        val (alarm, triggerAt) = next
        return "${alarm.name} ${countdown(triggerAt)}"
    }

    /** The supporting line: when exactly, and what kind. */
    fun nextAlarmDetail(context: Context): String {
        val next = nextAlarm(context) ?: return ""
        val (alarm, triggerAt) = next
        val at = java.text.SimpleDateFormat("EEEE d MMM, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(triggerAt))
        val kind = if (alarm.isHard) "hard — needs the scale" else "soft — one tap"
        return "$at · $kind"
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

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    private fun firePendingIntent(context: Context, alarm: AlarmSchedule.Alarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra(EXTRA_ALARM_NAME, alarm.name)
            putExtra(EXTRA_ALARM_KIND, alarm.kind)
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

                Log.i(TAG, "Alarm '$name' ($kind) fired")
                retireOneShot(context, alarmId)

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
            AlarmService.instance?.pushAlarmsToPC(root.toString())
        }
    }
}
