package org.example

import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.example.lanalarm.AlarmSchedule
import org.example.lanalarm.AlarmScheduler
import org.example.lanalarm.AlarmService
import org.example.lanalarm.AppSettings
import org.example.lanalarm.MainActivity
import org.example.lanalarm.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.Calendar

/**
 * What the app does when someone actually uses it.
 *
 * The schedule maths was already unit tested and was never wrong. Every bug
 * that reached the user lived one layer up — in the editor, in whether the list
 * redrew, and in whether the alarm the user created ever reached AlarmManager.
 * None of that was reachable from a plain JVM test, so none of it was covered.
 * These drive the real Activity, the real dialogs and the real AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AlarmFlowTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var controller: ActivityController<MainActivity>
    private val activity: MainActivity get() = controller.get()

    private val list: LinearLayout get() = activity.findViewById(R.id.alarms_list)
    private val headline: TextView get() = activity.findViewById(R.id.next_alarm_status)

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        ShadowLooper.idleMainLooper()
        activity.findViewById<ImageView>(R.id.tab_alarms).performClick()
        ShadowLooper.idleMainLooper()
    }

    // ─── Helpers ─────────────────────────────────────────────────────

    private fun rowTimes(): List<String> = (0 until list.childCount).map {
        list.getChildAt(it).findViewById<TextView>(R.id.alarm_time).text.toString()
    }

    private fun storedTimes(): List<String> = AlarmSchedule.parse(
        AppSettings.prefs(context).getString(AppSettings.KEY_ALARMS, null)
    ).map { it.time }

    /** Drive the editor dialog that is currently open: set a time, then Save. */
    private fun setTimeAndSave(hour: Int, minute: Int) {
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val timeView: TextView = dialog.findViewById(R.id.edit_time)!!
        timeView.performClick()
        val picker = ShadowDialog.getLatestDialog() as TimePickerDialog
        picker.updateTime(hour, minute)
        picker.getButton(TimePickerDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        ShadowLooper.idleMainLooper()
    }

    private fun createAlarm(hour: Int, minute: Int) {
        activity.findViewById<Button>(R.id.alarms_add).performClick()
        ShadowLooper.idleMainLooper()
        setTimeAndSave(hour, minute)
    }

    private fun editFirstAlarm(hour: Int, minute: Int) {
        list.getChildAt(0).performClick()
        ShadowLooper.idleMainLooper()
        setTimeAndSave(hour, minute)
    }

    private fun nextScheduledTrigger(): Long? =
        Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
            .scheduledAlarms.minByOrNull { it.triggerAtMs }?.triggerAtMs

    private fun alarmJson(
        id: String, time: String, updatedAt: Long,
        enabled: Boolean = true, deleted: Boolean = false
    ) = JSONObject()
        .put("id", id).put("name", "Weigh-in").put("time", time)
        .put("type", "next").put("enabled", enabled).put("kind", "hard")
        .put("updated_at", updatedAt)
        .also { if (deleted) it.put("deleted", true) }

    private fun schedule(vararg alarms: JSONObject) = JSONObject()
        .put("version", 2)
        .put("alarms", JSONArray().apply { alarms.forEach { put(it) } })

    // ─── Editing shows what you edited ───────────────────────────────

    @Test
    fun `editing an alarm updates the list immediately, not on the next edit`() {
        createAlarm(7, 30)
        assertEquals(listOf("07:30"), rowTimes())

        editFirstAlarm(9, 15)
        assertEquals("the list must show the time just saved", listOf("09:15"), rowTimes())
        assertEquals(listOf("09:15"), storedTimes())

        editFirstAlarm(11, 45)
        assertEquals("and not lag one edit behind", listOf("11:45"), rowTimes())
        assertEquals(listOf("11:45"), storedTimes())
    }

    @Test
    fun `the editor reopens on the stored time, not the one before it`() {
        createAlarm(7, 30)
        editFirstAlarm(9, 15)

        list.getChildAt(0).performClick()
        ShadowLooper.idleMainLooper()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("09:15", dialog.findViewById<TextView>(R.id.edit_time)!!.text.toString())
    }

    @Test
    fun `editing an alarm updates the next-alarm headline too`() {
        createAlarm(7, 30)
        val before = headline.text.toString()
        editFirstAlarm(9, 15)
        assertTrue(
            "headline still read '$before' after the edit",
            headline.text.toString() != before
        )
    }

    @Test
    fun `editing replaces the alarm rather than adding a second one`() {
        createAlarm(7, 30)
        editFirstAlarm(9, 15)
        assertEquals(1, list.childCount)
        assertEquals(1, storedTimes().size)
    }

    // ─── A schedule written behind the UI's back must redraw it ─────

    @Test
    fun `a schedule change from the service redraws the open list`() {
        createAlarm(7, 30)
        assertEquals(listOf("07:30"), rowTimes())

        // An ontoplano sync rewrites storage while the tab is open, exactly as
        // OntoplanoSync does — and as a one-shot retiring after it fires does.
        val stored = JSONObject(
            AppSettings.prefs(context).getString(AppSettings.KEY_ALARMS, null)!!
        )
        val id = AlarmSchedule.parse(stored.toString()).first().id
        AppSettings.prefs(context).edit()
            .putString(
                AppSettings.KEY_ALARMS,
                schedule(alarmJson(id, "06:00", updatedAt = System.currentTimeMillis()))
                    .toString()
            ).commit()

        context.sendBroadcast(
            Intent(AlarmService.ACTION_ALARMS_CHANGED).setPackage(context.packageName)
        )
        ShadowLooper.idleMainLooper()

        assertEquals(
            "the list kept rendering the schedule it read when the tab was drawn",
            listOf("06:00"), rowTimes()
        )
    }

    // ─── An alarm the user creates must actually be booked ───────────

    @Test
    fun `an alarm created for later today is booked with AlarmManager`() {
        val soon = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        createAlarm(soon.get(Calendar.HOUR_OF_DAY), soon.get(Calendar.MINUTE))

        val trigger = nextScheduledTrigger()
        assertNotNull("nothing reached AlarmManager, so nothing would ring", trigger)
        assertTrue("booked in the past", trigger!! > System.currentTimeMillis())
        assertTrue(
            "booked more than a day out for an alarm set two hours from now",
            trigger - System.currentTimeMillis() < 24 * 3600_000L
        )
    }

    @Test
    fun `re-timing an alarm rebooks AlarmManager instead of leaving the old slot`() {
        val soon = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        createAlarm(soon.get(Calendar.HOUR_OF_DAY), soon.get(Calendar.MINUTE))
        val first = nextScheduledTrigger()!!

        val later = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 4) }
        editFirstAlarm(later.get(Calendar.HOUR_OF_DAY), later.get(Calendar.MINUTE))

        val second = nextScheduledTrigger()!!
        assertTrue("AlarmManager still holds the pre-edit time", second != first)
    }

    @Test
    fun `disabling the last alarm cancels the booking`() {
        val soon = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        createAlarm(soon.get(Calendar.HOUR_OF_DAY), soon.get(Calendar.MINUTE))
        assertNotNull(nextScheduledTrigger())

        list.getChildAt(0).findViewById<android.widget.Switch>(R.id.alarm_enabled)
            .performClick()
        ShadowLooper.idleMainLooper()

        assertNull("a disabled alarm is still booked and will ring", nextScheduledTrigger())
    }

    @Test
    fun `a one-shot that has fired is retired and stops being rebooked`() {
        val soon = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        createAlarm(soon.get(Calendar.HOUR_OF_DAY), soon.get(Calendar.MINUTE))

        val root = JSONObject(
            AppSettings.prefs(context).getString(AppSettings.KEY_ALARMS, null)!!
        )
        val id = AlarmSchedule.parse(root.toString()).first().id

        assertTrue(AlarmSchedule.disableOneShot(root, id))
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_ALARMS, root.toString()).commit()

        assertNull(
            "a spent one-shot came back",
            AlarmScheduler.rescheduleNext(context)
        )
    }
}

/**
 * Survival across the events that wipe registered alarms.
 *
 * Android drops every alarm on boot and on app update. Nothing in the app is
 * more load-bearing than getting them back, and nothing was covered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RebookAfterRestartTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun storeAlarmForLaterToday() {
        val soon = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 2) }
        val time = String.format(
            "%02d:%02d", soon.get(Calendar.HOUR_OF_DAY), soon.get(Calendar.MINUTE)
        )
        val root = JSONObject().put("version", 2).put(
            "alarms",
            JSONArray().put(
                JSONObject()
                    .put("id", "a1").put("name", "Weigh-in").put("time", time)
                    .put("type", "next").put("enabled", true).put("kind", "hard")
                    .put("updated_at", System.currentTimeMillis())
            )
        )
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_ALARMS, root.toString()).commit()
    }

    private fun bookedTrigger(): Long? =
        Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
            .scheduledAlarms.minByOrNull { it.triggerAtMs }?.triggerAtMs

    @Before
    fun setUp() {
        AppSettings.prefs(context).edit().clear().commit()
    }

    @Test
    fun `an app update rebooks the alarm without needing the service to start`() {
        storeAlarmForLaterToday()

        // MY_PACKAGE_REPLACED is not exempt from the background foreground-service
        // restriction, so the service start may well fail here. The alarm must
        // survive that regardless.
        org.example.lanalarm.BootReceiver().onReceive(
            context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        )
        ShadowLooper.idleMainLooper()

        assertNotNull(
            "nothing was booked after an app update, so the next alarm is silent",
            bookedTrigger()
        )
    }

    @Test
    fun `a reboot rebooks the alarm`() {
        storeAlarmForLaterToday()

        org.example.lanalarm.BootReceiver().onReceive(
            context, Intent(Intent.ACTION_BOOT_COMPLETED)
        )
        ShadowLooper.idleMainLooper()

        assertNotNull("nothing was booked after a reboot", bookedTrigger())
    }

    @Test
    fun `an unrelated broadcast books nothing`() {
        storeAlarmForLaterToday()

        org.example.lanalarm.BootReceiver().onReceive(
            context, Intent(Intent.ACTION_SCREEN_ON)
        )
        ShadowLooper.idleMainLooper()

        assertNull(bookedTrigger())
    }

    @Test
    fun `firing an alarm books the following one`() {
        val root = JSONObject().put("version", 2).put(
            "alarms",
            JSONArray().put(
                JSONObject()
                    .put("id", "weekly").put("name", "Weigh-in").put("time", "07:00")
                    .put("days", JSONArray().put("monday").put("tuesday")
                        .put("wednesday").put("thursday").put("friday")
                        .put("saturday").put("sunday"))
                    .put("enabled", true).put("kind", "hard")
                    .put("updated_at", System.currentTimeMillis())
            )
        )
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_ALARMS, root.toString()).commit()

        val first = AlarmScheduler.rescheduleNext(context)
        assertNotNull(first)
        assertNotNull("a daily alarm stopped after one firing", bookedTrigger())
    }
}
