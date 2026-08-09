package org.example.lanalarm

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Alarm-schedule reading and next-occurrence maths.
 *
 * This mirrors `alarm_manager.get_next_alarm_time` on the PC. Both sides now
 * compute occurrences, because the phone schedules its own alarms and the PC
 * still logs what is coming up — they must agree, so the rules live here in one
 * readable place and are covered by unit tests.
 *
 * Schedule format (v2), as stored in `alarms.json` and synced over WebSocket:
 *
 *   weekly     -> "days": ["monday", ...]
 *   dated      -> "date": "DD-MM-YYYY"
 *   one-shot   -> "type": "next"
 *
 * plus `kind`: "hard" (default) or "soft" — see [Alarm.isHard].
 */
object AlarmSchedule {

    const val KIND_HARD = "hard"
    const val KIND_SOFT = "soft"

    val WEEKDAYS = listOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"
    )

    /** Calendar.MONDAY is 2; our list is 0-based from Monday. */
    private val CALENDAR_DAY = mapOf(
        "monday" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY
    )

    data class Alarm(
        val id: String,
        val name: String,
        val time: String,
        val days: List<String>,
        val date: String?,
        val type: String?,
        val enabled: Boolean,
        val deleted: Boolean,
        val kind: String,
        val updatedAt: Long
    ) {
        /**
         * A hard alarm can only be silenced by standing on the scale.
         *
         * Defaults to true when unset: every alarm that existed before this
         * setting was introduced behaved that way, and silently downgrading
         * someone's wake-up alarm to a dismiss button is the wrong direction to
         * fail in.
         */
        val isHard: Boolean get() = kind != KIND_SOFT

        val isActive: Boolean get() = enabled && !deleted
    }

    // ─── Parsing ─────────────────────────────────────────────────────

    fun parse(rawJson: String?): List<Alarm> {
        if (rawJson.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return emptyList()
        return parseRoot(root)
    }

    fun parseRoot(root: JSONObject): List<Alarm> {
        val array = root.optJSONArray("alarms") ?: JSONArray()
        val alarms = mutableListOf<Alarm>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("id")
            if (id.isBlank()) continue

            val days = mutableListOf<String>()
            entry.optJSONArray("days")?.let { raw ->
                for (j in 0 until raw.length()) {
                    val day = raw.optString(j).lowercase(Locale.US)
                    if (day in WEEKDAYS) days.add(day)
                }
            }

            alarms.add(
                Alarm(
                    id = id,
                    name = entry.optString("name", "Alarm"),
                    time = entry.optString("time", "07:00"),
                    days = days,
                    date = entry.optString("date").takeIf { it.isNotBlank() },
                    type = entry.optString("type").takeIf { it.isNotBlank() },
                    enabled = entry.optBoolean("enabled", true),
                    deleted = entry.optBoolean("deleted", false),
                    kind = entry.optString("kind", KIND_HARD).lowercase(Locale.US),
                    updatedAt = entry.optLong("updated_at", 0L)
                )
            )
        }
        return alarms
    }

    // ─── Occurrence maths ────────────────────────────────────────────

    /** Parses "HH:MM" or "HH:MM:SS". Returns null when malformed. */
    fun parseTime(time: String): Triple<Int, Int, Int>? {
        val parts = time.trim().split(":")
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        val second = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        return Triple(hour, minute, second)
    }

    /**
     * When this alarm next fires, in epoch millis, or null if never again.
     *
     * `now` is injectable so the rules can be tested without waiting for Tuesday.
     */
    fun nextOccurrence(alarm: Alarm, now: Long = System.currentTimeMillis()): Long? {
        if (!alarm.isActive) return null
        val (hour, minute, second) = parseTime(alarm.time) ?: return null

        alarm.date?.let { return datedOccurrence(it, hour, minute, second, now) }

        val candidate = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }

        // One-shot, and weekly alarms with no days selected: the next time this
        // clock time comes around.
        if (alarm.days.isEmpty()) {
            if (candidate.timeInMillis <= now) candidate.add(Calendar.DAY_OF_YEAR, 1)
            return candidate.timeInMillis
        }

        val wanted = alarm.days.mapNotNull { CALENDAR_DAY[it] }.toSet()
        if (wanted.isEmpty()) return null

        // Today counts only if the time has not passed; otherwise search forward.
        for (offset in 0..7) {
            val probe = (candidate.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
            }
            if (probe.timeInMillis <= now) continue
            if (probe.get(Calendar.DAY_OF_WEEK) in wanted) return probe.timeInMillis
        }
        return null
    }

    private fun datedOccurrence(
        date: String,
        hour: Int,
        minute: Int,
        second: Int,
        now: Long
    ): Long? {
        val parts = date.split("-")
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val year = parts[2].toIntOrNull() ?: return null

        val moment = Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }
        // A dated alarm in the past has simply been and gone.
        return if (moment.timeInMillis > now) moment.timeInMillis else null
    }

    /** The soonest upcoming alarm, or null when nothing is scheduled. */
    fun nextAlarm(alarms: List<Alarm>, now: Long = System.currentTimeMillis()): Pair<Alarm, Long>? =
        alarms.mapNotNull { alarm -> nextOccurrence(alarm, now)?.let { alarm to it } }
            .minByOrNull { it.second }

    /**
     * A one-shot alarm has done its job once it fires. Marks it disabled and
     * bumps `updated_at` so the merge on the PC keeps the change.
     */
    fun disableOneShot(root: JSONObject, alarmId: String): Boolean {
        val array = root.optJSONArray("alarms") ?: return false
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            if (entry.optString("id") != alarmId) continue
            if (entry.optString("type") != "next") return false
            entry.put("enabled", false)
            entry.put("updated_at", System.currentTimeMillis())
            return true
        }
        return false
    }
}
