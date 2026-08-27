package org.example

import org.example.lanalarm.AlarmSchedule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone/PC schedule merge.
 *
 * This lived inside AlarmService, unreachable from any test, while deciding
 * whether the user's alarm still existed and what time it rang at. It must
 * agree exactly with `alarm_manager.merge_alarms`; `tests/test_merge_parity.py`
 * asserts the same cases on the Python side.
 */
class MergeTest {

    private fun alarm(
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

    private fun timesById(root: JSONObject): Map<String, String> =
        AlarmSchedule.parse(root.toString()).associate { it.id to it.time }

    @Test
    fun `a newer remote edit wins`() {
        val merged = AlarmSchedule.merge(
            schedule(alarm("a", "07:00", updatedAt = 100)),
            schedule(alarm("a", "09:00", updatedAt = 200))
        )
        assertEquals(mapOf("a" to "09:00"), timesById(merged))
    }

    @Test
    fun `a newer local edit survives a stale copy from the PC`() {
        val merged = AlarmSchedule.merge(
            schedule(alarm("a", "09:00", updatedAt = 200)),
            schedule(alarm("a", "07:00", updatedAt = 100))
        )
        assertEquals(
            "the PC's stale copy overwrote an edit made on the phone",
            mapOf("a" to "09:00"), timesById(merged)
        )
    }

    @Test
    fun `a tie keeps the local copy, matching the PC's tie-break`() {
        val merged = AlarmSchedule.merge(
            schedule(alarm("a", "09:00", updatedAt = 100)),
            schedule(alarm("a", "07:00", updatedAt = 100))
        )
        assertEquals(mapOf("a" to "09:00"), timesById(merged))
    }

    @Test
    fun `an alarm created offline on either side is kept`() {
        val merged = AlarmSchedule.merge(
            schedule(alarm("phone", "07:00", updatedAt = 100)),
            schedule(alarm("pc", "08:00", updatedAt = 100))
        )
        assertEquals(
            mapOf("phone" to "07:00", "pc" to "08:00"), timesById(merged)
        )
    }

    @Test
    fun `a deletion propagates instead of being resurrected`() {
        // `now` is pinned so the tombstone is fresh rather than 1970-old, which
        // the pruning below would otherwise sweep away for the wrong reason.
        val merged = AlarmSchedule.merge(
            schedule(alarm("a", "07:00", updatedAt = 100)),
            schedule(alarm("a", "07:00", updatedAt = 200, enabled = false, deleted = true)),
            now = 300
        )
        val alarms = AlarmSchedule.parse(merged.toString())
        assertEquals(1, alarms.size)
        assertTrue("the tombstone was dropped, so the PC would sync it back", alarms[0].deleted)
    }

    @Test
    fun `a fresh tombstone is kept but an ancient one is pruned`() {
        val now = 40L * 86_400 * 1000
        val fresh = AlarmSchedule.merge(
            schedule(alarm("a", "07:00", updatedAt = now - 1000, deleted = true)),
            schedule(), now = now
        )
        assertEquals(1, AlarmSchedule.parse(fresh.toString()).size)

        val ancient = AlarmSchedule.merge(
            schedule(alarm("a", "07:00", updatedAt = 0, deleted = true)),
            schedule(), now = now
        )
        assertEquals(0, AlarmSchedule.parse(ancient.toString()).size)
    }

    @Test
    fun `merging is stable, so a sync round trip does not keep changing`() {
        val local = schedule(
            alarm("a", "07:00", updatedAt = 100),
            alarm("b", "08:00", updatedAt = 200)
        )
        val remote = schedule(alarm("a", "09:00", updatedAt = 300))

        val once = AlarmSchedule.merge(local, remote)
        val twice = AlarmSchedule.merge(once, remote)
        assertEquals(timesById(once), timesById(twice))
    }

    @Test
    fun `entries without an id are dropped rather than crashing the sync`() {
        val merged = AlarmSchedule.merge(
            schedule(alarm("a", "07:00", updatedAt = 100)),
            JSONObject().put("version", 2).put(
                "alarms",
                JSONArray().put(JSONObject().put("time", "09:00"))
            )
        )
        assertEquals(mapOf("a" to "07:00"), timesById(merged))
    }
}
