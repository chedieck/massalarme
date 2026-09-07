package org.example.lanalarm

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * Alarm-schedule reading and next-occurrence maths.
 *
 * The phone is the only thing that computes occurrences now. The rules still
 * mirror `alarm_manager.get_next_alarm_time`, which is the reference the tests
 * were written against, and they live here in one readable place because
 * getting them subtly wrong means an alarm on the wrong day.
 *
 * Schedule format (v2):
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

    /** Matches `_prune_old_tombstones(max_age_days=30)` on the PC. */
    const val TOMBSTONE_MAX_AGE_MS = 30L * 86_400 * 1000

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

    val WEEKDAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Single letters for the day toggles, in the same order as [WEEKDAYS]. */
    val WEEKDAY_INITIALS = listOf("M", "T", "W", "T", "F", "S", "S")

    const val ORIGIN_ONTOPLANO = "ontoplano"

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
        val updatedAt: Long,
        /** Set when this alarm was derived from the ontoplano planner. */
        val origin: String? = null,
        val originId: String? = null
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

        /**
         * Owned by ontoplano, so not editable here. Local edits would be undone
         * by the next schedule sync anyway — better to say so than to let the
         * user make a change that silently reverts.
         */
        val isReadOnly: Boolean get() = origin == ORIGIN_ONTOPLANO

        /** What repeat this is, derived rather than stored separately. */
        val repeatKind: String
            get() = when {
                date != null -> "date"
                days.isEmpty() -> "once"
                else -> "weekly"
            }

        /** Human summary of when it repeats, for the list and the editor. */
        fun describeRepeat(): String = when {
            date != null -> weekdayOf(date)?.let { "$it $date" } ?: date
            days.isEmpty() -> "Once"
            days.size == 7 -> "Every day"
            days.size == 5 && WEEKDAYS.take(5).all { it in days } -> "Weekdays"
            days.size == 2 && WEEKDAYS.drop(5).all { it in days } -> "Weekends"
            else -> days.sortedBy { WEEKDAYS.indexOf(it) }
                .joinToString(" ") { WEEKDAY_LABELS[WEEKDAYS.indexOf(it)] }
        }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("time", time)
            put("enabled", enabled)
            put("kind", kind)
            put("updated_at", updatedAt)
            if (deleted) put("deleted", true)
            origin?.let { put("origin", it) }
            originId?.let { put("origin_id", it) }
            when {
                date != null -> put("date", date)
                days.isNotEmpty() -> put("days", JSONArray(days))
                else -> put("type", "next")
            }
        }
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
                    updatedAt = entry.optLong("updated_at", 0L),
                    origin = entry.optString("origin").takeIf { it.isNotBlank() },
                    originId = entry.optString("origin_id").takeIf { it.isNotBlank() }
                )
            )
        }
        return alarms
    }

    /**
     * Replace or append an alarm in the stored schedule and return the new root.
     *
     * Deleting is done by tombstoning rather than removing — see
     * [pruneTombstones] — so an ontoplano re-sync propagates the deletion
     * instead of quietly bringing the alarm back.
     */
    fun upsert(root: JSONObject, alarm: Alarm): JSONObject {
        val array = root.optJSONArray("alarms") ?: JSONArray()
        var replaced = false
        for (i in 0 until array.length()) {
            if (array.optJSONObject(i)?.optString("id") == alarm.id) {
                array.put(i, alarm.toJson())
                replaced = true
                break
            }
        }
        if (!replaced) array.put(alarm.toJson())
        return root.apply {
            put("version", 2)
            put("alarms", array)
        }
    }

    /**
     * Drop tombstones that have done their job.
     *
     * A deleted alarm is kept as `deleted: true` rather than removed, so an
     * ontoplano re-sync propagates the deletion instead of resurrecting the
     * alarm. That only has to hold for as long as a sync could plausibly still
     * be carrying the old copy; past that the entry is dead weight, and without
     * this the stored schedule grows for every alarm the user ever deleted.
     *
     * Matches `alarm_manager._prune_old_tombstones(max_age_days=30)`.
     */
    fun pruneTombstones(root: JSONObject, now: Long = System.currentTimeMillis()): JSONObject {
        val alarms = root.optJSONArray("alarms") ?: return root
        val cutoff = now - TOMBSTONE_MAX_AGE_MS

        val kept = JSONArray()
        for (i in 0 until alarms.length()) {
            val alarm = alarms.optJSONObject(i) ?: continue
            val isAncientTombstone = alarm.optBoolean("deleted", false) &&
                alarm.optLong("updated_at", Long.MAX_VALUE) < cutoff
            if (!isAncientTombstone) kept.put(alarm)
        }

        return root.apply {
            put("version", 2)
            put("alarms", kept)
        }
    }

    /** Sort for display: soonest first, disabled ones last. */
    fun forDisplay(alarms: List<Alarm>, now: Long = System.currentTimeMillis()): List<Alarm> =
        alarms.filterNot { it.deleted }
            .sortedWith(
                compareBy(
                    { !it.enabled },
                    { nextOccurrence(it.copy(enabled = true), now) ?: Long.MAX_VALUE }
                )
            )

    // ─── Occurrence maths ────────────────────────────────────────────

    /** "Tue" for a "DD-MM-YYYY" date, or null if it will not parse. */
    fun weekdayOf(date: String): String? {
        val parts = date.split("-")
        if (parts.size != 3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val year = parts[2].toIntOrNull() ?: return null
        val calendar = Calendar.getInstance().apply { set(year, month - 1, day) }
        val index = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Monday = 0
        return WEEKDAY_LABELS.getOrNull(index)
    }

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
