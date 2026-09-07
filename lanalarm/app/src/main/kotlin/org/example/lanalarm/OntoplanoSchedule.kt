package org.example.lanalarm

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.Locale

/**
 * Turning ontoplano planner occurrences into massalarme alarms.
 *
 * "Put a task 'wake up' at 07:00 on Tuesday and that is when the alarm rings."
 *
 * ontoplano reports *what is scheduled* and nothing else — it knows nothing
 * about alarms, sirens, scales or wifi, and it should stay that way. Deciding
 * which occurrences deserve an alarm, and whether that alarm is hard or soft, is
 * massalarme's business, and now the phone's: this is a port of the PC's
 * `schedule_sync.py`, which used to be the only place it existed.
 *
 * Pure functions over JSON, deliberately free of Android imports, so the rules
 * can be unit-tested and checked against the Python implementation — the two
 * must not drift, or the same task becomes two different alarms depending on
 * which side last synced.
 */
object OntoplanoSchedule {

    const val ORIGIN = AlarmSchedule.ORIGIN_ONTOPLANO

    private val WEEKDAYS = AlarmSchedule.WEEKDAYS

    /**
     * One mapping from an occurrence to an alarm kind.
     *
     * A rule with no conditions matches everything. That is a real choice a user
     * can make ("every planned thing wakes me"), so it is allowed rather than
     * treated as a mistake.
     */
    data class Rule(
        val kind: String,
        val title: Regex? = null,
        val category: String? = null,
        val label: String? = null
    ) {
        fun matches(occurrence: JSONObject): Boolean {
            title?.let {
                if (!it.containsMatchIn(occurrence.optString("title"))) return false
            }
            category?.let {
                if (!occurrence.optString("category").equals(it, ignoreCase = true)) return false
            }
            label?.let {
                if (!occurrence.optString("label").equals(it, ignoreCase = true)) return false
            }
            return true
        }
    }

    /**
     * Build the rule list from the one rule the phone's settings screen offers.
     *
     * Case-insensitive by default: "acordar" should match "Acordar", and nobody
     * wants to remember to type `(?i)` to get there. An empty pattern means the
     * user has not opted in, so nothing matches — never everything.
     */
    fun rulesFrom(pattern: String, kind: String): List<Rule> {
        if (pattern.isBlank()) return emptyList()
        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
            ?: return emptyList()
        return listOf(Rule(kind = kind, title = regex))
    }

    /** The alarm kind for this occurrence, or null if it deserves no alarm. */
    fun classify(occurrence: JSONObject, rules: List<Rule>): String? =
        rules.firstOrNull { it.matches(occurrence) }?.kind

    // ─── Occurrence → alarm ──────────────────────────────────────────

    /** Stable per occurrence, so a re-sync updates rather than duplicates. */
    private fun alarmIdFor(occurrenceId: String) = "op-${occurrenceId.replace(':', '-')}"

    private fun weeklyAlarmId(title: String, time: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("${title.lowercase(Locale.US)}|$time".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(8)
        return "op-w-$digest"
    }

    /** `at_local` is naive wall-clock: "2026-09-08T07:00" or with seconds. */
    data class LocalMoment(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int)

    fun parseLocal(atLocal: String?): LocalMoment? {
        if (atLocal.isNullOrBlank()) return null
        val parts = atLocal.trim().split("T", " ")
        if (parts.size < 2) return null
        val date = parts[0].split("-")
        val time = parts[1].split(":")
        if (date.size != 3 || time.size < 2) return null
        return LocalMoment(
            year = date[0].toIntOrNull() ?: return null,
            month = date[1].toIntOrNull() ?: return null,
            day = date[2].toIntOrNull() ?: return null,
            hour = time[0].toIntOrNull() ?: return null,
            minute = time[1].substringBefore('.').toIntOrNull() ?: return null
        )
    }

    private fun LocalMoment.timeText() = "%02d:%02d".format(hour, minute)

    private fun LocalMoment.dateText() = "%02d-%02d-%04d".format(day, month, year)

    /** Monday = 0, matching [AlarmSchedule.WEEKDAYS]. */
    private fun LocalMoment.weekdayIndex(): Int {
        val calendar = Calendar.getInstance().apply { set(year, month - 1, day) }
        return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    /**
     * Map a whole `/schedule/upcoming` response into alarms.
     *
     * The same activity repeated across several days — which is what ontoplano's
     * grid editor produces — arrives as one occurrence per day. Emitting a dated
     * alarm for each turns "gym at 07:00 on Mon/Wed/Fri" into three separate
     * single-day alarms, which is not what the user set up and is miserable to
     * manage. So occurrences sharing a title, a time and a kind collapse into one
     * weekly alarm carrying all their weekdays. A title that genuinely happens
     * once in the window stays a dated alarm.
     */
    fun buildAlarms(schedule: JSONObject, rules: List<Rule>, nowMs: Long): List<JSONObject> {
        val occurrences = schedule.optJSONArray("occurrences") ?: JSONArray()

        data class Matched(val occurrence: JSONObject, val kind: String, val at: LocalMoment)

        val matched = mutableListOf<Matched>()
        for (i in 0 until occurrences.length()) {
            val occurrence = occurrences.optJSONObject(i) ?: continue
            val kind = classify(occurrence, rules) ?: continue
            val at = parseLocal(occurrence.optString("at_local")) ?: continue
            matched.add(Matched(occurrence, kind, at))
        }

        val groups = linkedMapOf<Triple<String, String, String>, MutableList<Matched>>()
        matched.forEach {
            val title = it.occurrence.optString("title").ifBlank { ORIGIN }
            val key = Triple(title.lowercase(Locale.US), it.at.timeText(), it.kind)
            groups.getOrPut(key) { mutableListOf() }.add(it)
        }

        val alarms = mutableListOf<JSONObject>()
        groups.forEach { (key, members) ->
            val (_, timeText, kind) = key
            val title = members.first().occurrence.optString("title").ifBlank { ORIGIN }
            val weekdays = members.map { it.at.weekdayIndex() }.distinct().sorted()
                .map { WEEKDAYS[it] }

            if (weekdays.size < 2) {
                // A one-off keeps its exact date; a weekly alarm would fire again
                // next week for something that only happens once.
                val first = members.first()
                val occurrenceId = first.occurrence.optString("id").trim()
                if (occurrenceId.isBlank()) return@forEach
                alarms.add(
                    JSONObject().apply {
                        put("id", alarmIdFor(occurrenceId))
                        put("name", title)
                        put("time", timeText)
                        put("date", first.at.dateText())
                        put("enabled", true)
                        put("kind", kind)
                        put("origin", ORIGIN)
                        put("origin_id", occurrenceId)
                        put("updated_at", nowMs)
                    }
                )
                return@forEach
            }

            alarms.add(
                JSONObject().apply {
                    put("id", weeklyAlarmId(title, timeText))
                    put("name", title)
                    put("time", timeText)
                    put("days", JSONArray(weekdays))
                    put("enabled", true)
                    put("kind", kind)
                    put("origin", ORIGIN)
                    // Every occurrence this alarm stands for, so a later sync can
                    // tell whether the set has changed.
                    put(
                        "origin_id",
                        members.map { it.occurrence.optString("id") }.sorted().joinToString(",")
                    )
                    put("updated_at", nowMs)
                }
            )
        }
        return alarms
    }

    // ─── Folding into the local schedule ─────────────────────────────

    /**
     * Fold ontoplano-derived alarms into the schedule the phone holds.
     *
     *  * Hand-made alarms are never touched. Only entries carrying
     *    `origin: ontoplano` are managed here.
     *  * A derived alarm that still exists upstream is updated in place, keeping
     *    its id, so nothing downstream sees a delete plus an insert.
     *  * A previously derived alarm that has vanished upstream is tombstoned —
     *    but only inside the window we actually asked about. An alarm derived for
     *    next month must not be deleted because we only fetched seven days.
     */
    fun mergeIntoSchedule(
        current: JSONObject,
        derived: List<JSONObject>,
        nowMs: Long,
        horizonDays: Int
    ): JSONObject {
        val existing = current.optJSONArray("alarms") ?: JSONArray()
        val derivedById = derived.associateBy { it.optString("id") }

        val merged = JSONArray()
        val seen = mutableSetOf<String>()

        for (i in 0 until existing.length()) {
            val alarm = existing.optJSONObject(i) ?: continue
            if (alarm.optString("origin") != ORIGIN) {
                merged.put(alarm)
                continue
            }

            val alarmId = alarm.optString("id")
            val replacement = derivedById[alarmId]
            if (replacement != null) {
                seen.add(alarmId)
                // Only bump updated_at when something actually changed, so a sync
                // every few minutes does not keep winning merge conflicts against
                // a genuine edit made on the phone.
                merged.put(if (sameIgnoringTimestamp(alarm, replacement)) alarm else replacement)
                continue
            }

            if (withinHorizon(alarm, nowMs, horizonDays)) {
                merged.put(
                    JSONObject(alarm.toString()).apply {
                        put("deleted", true)
                        put("updated_at", nowMs)
                    }
                )
            } else {
                merged.put(alarm)
            }
        }

        derivedById.forEach { (id, alarm) -> if (id !in seen) merged.put(alarm) }

        return JSONObject().apply {
            put("version", 2)
            put("alarms", merged)
        }
    }

    private fun sameIgnoringTimestamp(a: JSONObject, b: JSONObject): Boolean {
        fun comparable(source: JSONObject) = source.keys().asSequence()
            .filter { it != "updated_at" }
            .associateWith { source.opt(it)?.toString() ?: "null" }
            .toSortedMap()
        return comparable(a) == comparable(b)
    }

    /** Is this dated alarm inside the range we just asked ontoplano about? */
    private fun withinHorizon(alarm: JSONObject, nowMs: Long, horizonDays: Int): Boolean {
        val date = alarm.optString("date").takeIf { it.isNotBlank() } ?: return true
        val parts = date.split("-")
        if (parts.size != 3) return true
        val day = parts[0].toIntOrNull() ?: return true
        val month = parts[1].toIntOrNull() ?: return true
        val year = parts[2].toIntOrNull() ?: return true

        val moment = Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val today = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val deltaDays = ((moment.timeInMillis - today.timeInMillis) / 86_400_000L).toInt()
        return deltaDays in -1..horizonDays
    }
}
