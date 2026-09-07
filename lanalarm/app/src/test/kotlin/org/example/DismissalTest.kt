package org.example

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.example.lanalarm.AlarmSchedule
import org.example.lanalarm.AlarmScheduler
import org.example.lanalarm.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * Getting out of a ringing alarm.
 *
 * Three ways exist — the scale, the passphrase, and snooze — and two of them are
 * now the user's to configure. The tests that matter here are the ones about
 * what the configuration does *not* let you do: a snooze must not quietly cancel
 * tomorrow's alarm, and turning the passphrase off must actually remove it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DismissalSettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun `the passphrase defaults to the one that used to be compiled in`() {
        // Anyone upgrading keeps the phrase they had learned by heart.
        assertEquals(AppSettings.DEFAULT_PASSPHRASE, AppSettings.passphrase(context))
        assertTrue(AppSettings.passphraseEnabled(context))
    }

    @Test
    fun `a user-set passphrase replaces it`() {
        AppSettings.setPassphrase(context, "  eu nao quero acordar  ", enabled = true)
        assertEquals("eu nao quero acordar", AppSettings.passphrase(context))
    }

    @Test
    fun `clearing the passphrase falls back to the default rather than to nothing`() {
        // An empty stored phrase would mean an empty input field dismisses the
        // alarm — the exact opposite of what the setting is for.
        AppSettings.setPassphrase(context, "", enabled = true)
        assertEquals(AppSettings.DEFAULT_PASSPHRASE, AppSettings.passphrase(context))
    }

    @Test
    fun `the passphrase can be switched off entirely`() {
        AppSettings.setPassphrase(context, null, enabled = false)
        assertFalse(AppSettings.passphraseEnabled(context))
    }

    @Test
    fun `snooze defaults to nine minutes and can be turned off`() {
        assertEquals(AppSettings.DEFAULT_SNOOZE_MINUTES, AppSettings.snoozeMinutes(context))
        assertTrue(AppSettings.snoozeEnabled(context))

        AppSettings.setSnoozeMinutes(context, 0)
        assertFalse(AppSettings.snoozeEnabled(context))
    }

    @Test
    fun `a nonsense snooze length is clamped rather than stored`() {
        AppSettings.setSnoozeMinutes(context, 9999)
        assertEquals(60, AppSettings.snoozeMinutes(context))

        AppSettings.setSnoozeMinutes(context, -5)
        assertEquals(0, AppSettings.snoozeMinutes(context))
    }
}

/**
 * Snoozing, against the real AlarmManager.
 *
 * The failure this suite exists to prevent: a snooze and the next scheduled
 * alarm sharing a PendingIntent request code, so that snoozing at 07:00 quietly
 * discards the 07:30 alarm. That is the sort of thing you only find out about on
 * the morning it matters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SnoozeTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val alarmManager: AlarmManager
        get() = context.getSystemService(AlarmManager::class.java)

    private fun scheduledAt(): List<Long> =
        Shadows.shadowOf(alarmManager).scheduledAlarms.map { it.triggerAtMs }.sorted()

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    /** A daily alarm at a time that has not happened yet today. */
    private fun storeDailyAlarm(id: String = "a1", enabled: Boolean = true) {
        val soon = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.HOUR_OF_DAY, 3)
        }
        val time = "%02d:%02d".format(
            soon.get(java.util.Calendar.HOUR_OF_DAY),
            soon.get(java.util.Calendar.MINUTE)
        )
        val root = JSONObject().apply {
            put("version", 2)
            put("alarms", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", id)
                    put("name", "Wake up")
                    put("time", time)
                    put("enabled", enabled)
                    put("kind", AlarmSchedule.KIND_HARD)
                    put("updated_at", System.currentTimeMillis())
                })
            })
        }
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_ALARMS, root.toString()).commit()
    }

    @Test
    fun `a snooze books an extra alarm without cancelling the scheduled one`() {
        storeDailyAlarm()
        val scheduled = AlarmScheduler.rescheduleNext(context)!!
        assertEquals(listOf(scheduled), scheduledAt())

        AlarmScheduler.snooze(context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 9)

        assertEquals(
            "the scheduled alarm must survive a snooze, or tomorrow is silent",
            2, scheduledAt().size
        )
        assertTrue(scheduled in scheduledAt())
    }

    @Test
    fun `a snooze is recorded so the UI can say the phone will make a noise`() {
        storeDailyAlarm()
        val at = AlarmScheduler.snooze(
            context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 9
        )

        val pending = AlarmScheduler.pendingSnooze(context)
        assertNotNull(pending)
        assertEquals(at, pending)
        assertTrue(
            "a headline that ignores the snooze is simply wrong about what happens next",
            AlarmScheduler.nextAlarmDescription(context).startsWith("Snoozed")
        )
        assertTrue(AlarmScheduler.nextAlarmDetail(context).endsWith("snoozed"))
    }

    @Test
    fun `the scheduled alarm still wins the headline when it is sooner`() {
        storeDailyAlarm()
        AlarmScheduler.rescheduleNext(context)
        // An hour's snooze on an alarm three hours out: the schedule is not the
        // next thing to happen, the snooze is.
        AlarmScheduler.snooze(context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 60)
        assertTrue(AlarmScheduler.nextAlarmDescription(context).startsWith("Snoozed"))

        AlarmScheduler.cancelSnooze(context)
        assertTrue(AlarmScheduler.nextAlarmDescription(context).startsWith("Wake up"))
    }

    @Test
    fun `cancelling a snooze removes it from AlarmManager`() {
        storeDailyAlarm()
        AlarmScheduler.rescheduleNext(context)
        AlarmScheduler.snooze(context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 9)
        assertEquals(2, scheduledAt().size)

        AlarmScheduler.cancelSnooze(context)

        assertEquals(1, scheduledAt().size)
        assertNull(AlarmScheduler.pendingSnooze(context))
    }

    @Test
    fun `disabling the alarm drops the snooze it was hiding behind`() {
        // Without this, turning an alarm off after snoozing it leaves the snooze
        // booked and it rings anyway — which reads as the switch not working.
        storeDailyAlarm()
        AlarmScheduler.rescheduleNext(context)
        AlarmScheduler.snooze(context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 9)
        assertNotNull(AlarmScheduler.pendingSnooze(context))

        storeDailyAlarm(enabled = false)
        AlarmScheduler.rescheduleNext(context)

        assertNull(AlarmScheduler.pendingSnooze(context))
        assertTrue(scheduledAt().isEmpty())
    }

    @Test
    fun `a snooze for an alarm that still exists is left alone`() {
        storeDailyAlarm()
        AlarmScheduler.rescheduleNext(context)
        AlarmScheduler.snooze(context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 9)

        // An unrelated reschedule — the sort that happens on every edit.
        AlarmScheduler.rescheduleNext(context)

        assertNotNull(AlarmScheduler.pendingSnooze(context))
        assertEquals(2, scheduledAt().size)
    }

    @Test
    fun `a snooze in the past is not reported as pending`() {
        AppSettings.prefs(context).edit()
            .putLong("snooze_at", System.currentTimeMillis() - 60_000).commit()
        assertNull(AlarmScheduler.pendingSnooze(context))
    }

    @Test
    fun `a snoozed hard alarm comes back hard`() {
        // Snoozing is asking for a few more minutes, not talking the app out of
        // its job. The kind travels with the snooze.
        storeDailyAlarm()
        AlarmScheduler.snooze(context, "a1", "Wake up", AlarmSchedule.KIND_HARD, minutes = 9)

        val booked = Shadows.shadowOf(alarmManager).scheduledAlarms
            .maxByOrNull { it.triggerAtMs }!!
        val intent = Shadows.shadowOf(booked.operation).savedIntent

        assertEquals(
            AlarmSchedule.KIND_HARD,
            intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_KIND)
        )
        assertTrue(intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZED, false))
    }
}
