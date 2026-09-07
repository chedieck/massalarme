package org.example

import org.example.lanalarm.AlarmSchedule
import org.example.lanalarm.OntoplanoSchedule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Turning planner occurrences into alarms.
 *
 * This is a port of `schedule_sync.py`, and every case below has a counterpart
 * in `tests/test_schedule_sync.py` under the same name. That is the point: the
 * daemon is no longer in the path, but it remains the reference implementation,
 * and two implementations of "which task rings tomorrow" that quietly disagree
 * would be worse than one.
 */
class OntoplanoScheduleTest {

    companion object {
        /** 9 August 2026, midday. Fixed so "next Monday" never moves. */
        private val NOW_MS: Long = Calendar.getInstance()
            .apply {
                set(2026, Calendar.AUGUST, 9, 12, 0, 0)
                set(Calendar.MILLISECOND, 0)
            }
            .timeInMillis
    }

    private fun occurrence(
        id: String = "slot:1423",
        title: String = "Wake up",
        atLocal: String = "2026-08-11T07:00:00",
        category: String = "duty",
        label: String = ""
    ) = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("at_local", atLocal)
        put("category", category)
        put("label", label)
    }

    private fun schedule(vararg occurrences: JSONObject) = JSONObject().apply {
        put("occurrences", JSONArray().apply { occurrences.forEach { put(it) } })
    }

    private val rules = listOf(
        OntoplanoSchedule.Rule(kind = "hard", title = Regex("wake up", RegexOption.IGNORE_CASE)),
        OntoplanoSchedule.Rule(kind = "soft", category = "duty")
    )

    private fun JSONObject.alarms(): List<JSONObject> {
        val array = getJSONArray("alarms")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    // ─── Classification ──────────────────────────────────────────────

    @Test
    fun `a wake up task becomes a hard alarm`() {
        assertEquals("hard", OntoplanoSchedule.classify(occurrence(), rules))
    }

    @Test
    fun `first matching rule wins`() {
        // "Wake up" is also category=duty, but the hard rule comes first.
        assertEquals("hard", OntoplanoSchedule.classify(occurrence(title = "Wake up"), rules))
        assertEquals("soft", OntoplanoSchedule.classify(occurrence(title = "Dentist"), rules))
    }

    @Test
    fun `an occurrence matching nothing gets no alarm`() {
        assertNull(
            OntoplanoSchedule.classify(
                occurrence(title = "Dentist", category = "personal"), rules
            )
        )
    }

    @Test
    fun `title matching ignores case without needing a flag`() {
        // Nobody should have to remember to type (?i) to make "acordar" match
        // "Acordar" — that is how this silently matched nothing once already.
        val built = OntoplanoSchedule.rulesFrom("acordar", "hard")
        assertEquals("hard", OntoplanoSchedule.classify(occurrence(title = "Acordar"), built))
    }

    @Test
    fun `an empty pattern matches nothing rather than everything`() {
        // The failure mode this guards is turning every planned task in the
        // user's week into a siren at 07:00.
        assertTrue(OntoplanoSchedule.rulesFrom("", "hard").isEmpty())
        assertTrue(OntoplanoSchedule.rulesFrom("   ", "hard").isEmpty())
    }

    @Test
    fun `a bad regex is skipped rather than crashing`() {
        assertTrue(OntoplanoSchedule.rulesFrom("(unclosed", "hard").isEmpty())
    }

    @Test
    fun `label matching`() {
        val labelled = listOf(OntoplanoSchedule.Rule(kind = "soft", label = "morning"))
        assertEquals(
            "soft",
            OntoplanoSchedule.classify(occurrence(label = "Morning"), labelled)
        )
        assertNull(OntoplanoSchedule.classify(occurrence(label = "evening"), labelled))
    }

    // ─── Occurrence → alarm ──────────────────────────────────────────

    @Test
    fun `at_local is used as wall-clock without timezone conversion`() {
        // The alarm rings at the time the user wrote down. A UTC round trip here
        // is how an alarm ends up an hour out twice a year.
        val alarms = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        assertEquals("07:00", alarms.single().getString("time"))
        assertEquals("11-08-2026", alarms.single().getString("date"))
    }

    @Test
    fun `the alarm id is stable across syncs`() {
        val first = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val second = OntoplanoSchedule.buildAlarms(
            schedule(occurrence()), rules, NOW_MS + 60_000
        )
        assertEquals(first.single().getString("id"), second.single().getString("id"))
    }

    @Test
    fun `occurrences missing required fields are skipped`() {
        val alarms = OntoplanoSchedule.buildAlarms(
            schedule(
                occurrence(id = "", title = "Wake up"),
                occurrence(id = "slot:9", atLocal = "")
            ),
            rules,
            NOW_MS
        )
        assertTrue(alarms.isEmpty())
    }

    @Test
    fun `an empty schedule is not an error`() {
        assertTrue(OntoplanoSchedule.buildAlarms(schedule(), rules, NOW_MS).isEmpty())
        assertTrue(OntoplanoSchedule.buildAlarms(JSONObject(), rules, NOW_MS).isEmpty())
    }

    @Test
    fun `a derived alarm carries its origin so the UI can mark it read-only`() {
        val alarm = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS).single()
        assertEquals(AlarmSchedule.ORIGIN_ONTOPLANO, alarm.getString("origin"))
        assertEquals("slot:1423", alarm.getString("origin_id"))
    }

    // ─── Repeated activities ─────────────────────────────────────────
    //
    // ontoplano's grid editor duplicates an activity across days, which arrives
    // as one occurrence per day. Emitting a dated alarm for each turned one
    // habit into several unrelated single-day alarms.

    private fun gym(slot: Int, day: Int, time: String = "07:00", title: String = "Gym") =
        occurrence(
            id = "slot:$slot",
            title = title,
            atLocal = "2026-08-%02dT%s:00".format(day, time)
        )

    private val gymRules =
        listOf(OntoplanoSchedule.Rule(kind = "hard", title = Regex("gym", RegexOption.IGNORE_CASE)))

    @Test
    fun `an activity repeated across days becomes one weekly alarm`() {
        // 10, 12 and 14 August 2026 are Monday, Wednesday and Friday.
        val alarms = OntoplanoSchedule.buildAlarms(
            schedule(gym(1, 10), gym(2, 12), gym(3, 14)), gymRules, NOW_MS
        )

        val alarm = alarms.single()
        val days = alarm.getJSONArray("days")
        assertEquals(
            listOf("monday", "wednesday", "friday"),
            (0 until days.length()).map { days.getString(it) }
        )
        assertEquals("07:00", alarm.getString("time"))
        assertFalse("a weekly alarm must not also claim a single date", alarm.has("date"))
    }

    @Test
    fun `a one-off activity keeps its date`() {
        val alarm = OntoplanoSchedule.buildAlarms(schedule(gym(9, 11)), gymRules, NOW_MS).single()
        assertEquals("11-08-2026", alarm.getString("date"))
        assertFalse(
            "a weekly alarm would ring again next week for a one-off",
            alarm.has("days")
        )
    }

    @Test
    fun `the same activity at different times stays separate`() {
        // 07:00 gym and 19:00 gym are two different habits.
        val alarms = OntoplanoSchedule.buildAlarms(
            schedule(gym(1, 10), gym(2, 12), gym(3, 10, "19:00"), gym(4, 12, "19:00")),
            gymRules,
            NOW_MS
        )
        assertEquals(listOf("07:00", "19:00"), alarms.map { it.getString("time") }.sorted())
    }

    @Test
    fun `different activities at the same time stay separate`() {
        val rules = listOf(
            OntoplanoSchedule.Rule(kind = "hard", title = Regex("gym|swim", RegexOption.IGNORE_CASE))
        )
        val alarms = OntoplanoSchedule.buildAlarms(
            schedule(
                gym(1, 10), gym(2, 12),
                gym(3, 10, title = "Swim"), gym(4, 12, title = "Swim")
            ),
            rules,
            NOW_MS
        )
        assertEquals(listOf("Gym", "Swim"), alarms.map { it.getString("name") }.sorted())
    }

    @Test
    fun `the weekly id is stable across syncs`() {
        // Otherwise every sync would tombstone the alarm and add a new one.
        val first = OntoplanoSchedule.buildAlarms(
            schedule(gym(1, 10), gym(2, 12)), gymRules, NOW_MS
        )
        // Same activity next week: different occurrence ids, same habit.
        val later = OntoplanoSchedule.buildAlarms(
            schedule(gym(77, 17), gym(78, 19)), gymRules, NOW_MS + 604_800_000
        )
        assertEquals(first.single().getString("id"), later.single().getString("id"))
    }

    // ─── Folding into the local schedule ─────────────────────────────

    private fun localSchedule(vararg alarms: JSONObject) = JSONObject().apply {
        put("version", 2)
        put("alarms", JSONArray().apply { alarms.forEach { put(it) } })
    }

    private fun handMade(id: String = "local-1") = JSONObject().apply {
        put("id", id)
        put("name", "My own alarm")
        put("time", "06:30")
        put("enabled", true)
        put("kind", "hard")
        put("updated_at", NOW_MS - 1000)
    }

    @Test
    fun `hand-made alarms are never touched`() {
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val merged = OntoplanoSchedule.mergeIntoSchedule(
            localSchedule(handMade()), derived, NOW_MS, 7
        )

        val mine = merged.alarms().single { it.getString("id") == "local-1" }
        assertEquals("06:30", mine.getString("time"))
        assertFalse(mine.optBoolean("deleted", false))
    }

    @Test
    fun `resyncing updates in place instead of duplicating`() {
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), derived, NOW_MS, 7)
        val twice = OntoplanoSchedule.mergeIntoSchedule(once, derived, NOW_MS, 7)

        assertEquals(1, twice.alarms().count { !it.optBoolean("deleted", false) })
    }

    @Test
    fun `an unchanged occurrence does not bump updated_at`() {
        // A sync every few minutes must not keep winning against a genuine edit
        // made on the phone.
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), derived, NOW_MS, 7)

        val later = OntoplanoSchedule.buildAlarms(
            schedule(occurrence()), rules, NOW_MS + 600_000
        )
        val twice = OntoplanoSchedule.mergeIntoSchedule(once, later, NOW_MS + 600_000, 7)

        assertEquals(NOW_MS, twice.alarms().single().getLong("updated_at"))
    }

    @Test
    fun `a changed time does bump updated_at`() {
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), derived, NOW_MS, 7)

        val moved = OntoplanoSchedule.buildAlarms(
            schedule(occurrence(atLocal = "2026-08-11T08:30:00")), rules, NOW_MS + 600_000
        )
        val twice = OntoplanoSchedule.mergeIntoSchedule(once, moved, NOW_MS + 600_000, 7)

        val alarm = twice.alarms().single()
        assertEquals("08:30", alarm.getString("time"))
        assertEquals(NOW_MS + 600_000, alarm.getLong("updated_at"))
    }

    @Test
    fun `an occurrence deleted upstream is tombstoned`() {
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), derived, NOW_MS, 7)
        val gone = OntoplanoSchedule.mergeIntoSchedule(once, emptyList(), NOW_MS, 7)

        assertTrue(gone.alarms().single().getBoolean("deleted"))
    }

    @Test
    fun `alarms beyond the fetched window are not deleted`() {
        // We only asked about seven days. An alarm derived for next month must
        // not vanish because it was not in the answer.
        val nextMonth = OntoplanoSchedule.buildAlarms(
            schedule(occurrence(id = "slot:99", atLocal = "2026-09-20T07:00:00")),
            rules,
            NOW_MS
        )
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), nextMonth, NOW_MS, 7)
        val stillThere = OntoplanoSchedule.mergeIntoSchedule(once, emptyList(), NOW_MS, 7)

        assertFalse(stillThere.alarms().single().optBoolean("deleted", false))
    }

    @Test
    fun `an ancient tombstone is pruned so the schedule does not grow forever`() {
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), derived, NOW_MS, 7)
        val gone = OntoplanoSchedule.mergeIntoSchedule(once, emptyList(), NOW_MS, 7)
        assertEquals(1, gone.alarms().size)

        // Two months later the tombstone has done its job.
        val muchLater = NOW_MS + 60L * 86_400_000
        val pruned = AlarmSchedule.pruneTombstones(gone, muchLater)
        assertTrue(pruned.alarms().isEmpty())
    }

    @Test
    fun `a fresh tombstone is kept`() {
        val derived = OntoplanoSchedule.buildAlarms(schedule(occurrence()), rules, NOW_MS)
        val once = OntoplanoSchedule.mergeIntoSchedule(localSchedule(), derived, NOW_MS, 7)
        val gone = OntoplanoSchedule.mergeIntoSchedule(once, emptyList(), NOW_MS, 7)

        // A day later, a re-sync must still see the deletion rather than
        // resurrecting the alarm.
        val pruned = AlarmSchedule.pruneTombstones(gone, NOW_MS + 86_400_000)
        assertEquals(1, pruned.alarms().size)
    }

    @Test
    fun `parsing at_local tolerates a missing seconds field`() {
        assertEquals(7, OntoplanoSchedule.parseLocal("2026-08-11T07:00")!!.hour)
        assertEquals(30, OntoplanoSchedule.parseLocal("2026-08-11T07:30:00.123")!!.minute)
        assertNull(OntoplanoSchedule.parseLocal("not a date"))
        assertNull(OntoplanoSchedule.parseLocal(null))
    }
}
