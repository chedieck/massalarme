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
 * massalarme's business.
 *
 * The decision is read off the block's **attributes** — the user-defined
 * key/value pairs ontoplano stores on a task and never interprets, exactly so
 * that a plugin can define its own vocabulary. Marking the block that should
 * wake you is a property of that block, so that is where it lives. It used to be
 * a regex matched against the title, which meant the alarm depended on spelling:
 * rename "Acordar" to "Levantar" and the alarm silently stopped happening.
 *
 * Pure functions over JSON, deliberately free of Android imports, so the rules
 * can be unit-tested. The PC daemon's `schedule_sync.py` still has the old
 * YAML-configured title rules; nothing on the phone reads them, and the daemon
 * no longer drives the phone, so the two are no longer two implementations of
 * one thing.
 */
object OntoplanoSchedule {

    const val ORIGIN = AlarmSchedule.ORIGIN_ONTOPLANO

    private val WEEKDAYS = AlarmSchedule.WEEKDAYS

    /**
     * The attribute vocabulary, derived from the source name so there is one
     * place it is spelled. These three keys are declared to ontoplano in
     * [Ontoplano.manifest], which is what makes them show up as this app's
     * words rather than as three anonymous strings on a task.
     */
    /** Rings gently, dismissed with one tap. */
    val ATTR_SOFT = "soft_${Ontoplano.SOURCE}"

    /** Rings the siren and wants the scale. Beats [ATTR_SOFT] when both are set. */
    val ATTR_HARD = "hard_${Ontoplano.SOURCE}"

    /** The short spelling of [ATTR_SOFT], and the one to reach for. */
    val ATTR_RING = Ontoplano.SOURCE

    /**
     * What counts as yes.
     *
     * Attribute values are strings, so this is where "true" becomes true. The
     * list is closed rather than "anything that is not false": an alarm clock
     * that rings at 05:00 because a value was misspelt is worse than one that
     * stays quiet and can be looked at over breakfast.
     */
    private val TRUE_VALUES = setOf("true", "1", "yes", "y", "on")

    /**
     * The block's attributes, under either name.
     *
     * ontoplano answers the same object twice — as `attributes`, and as `meta`
     * for plugins written before the rename. Reading both means this works
     * against an older instance without a second code path.
     */
    private fun attributesOf(occurrence: JSONObject): JSONObject? =
        occurrence.optJSONObject("attributes") ?: occurrence.optJSONObject("meta")

    private fun isTrue(attributes: JSONObject, key: String): Boolean =
        attributes.optString(key).trim().lowercase(Locale.US) in TRUE_VALUES

    /**
     * The alarm kind for this occurrence, or null if it deserves no alarm.
     *
     * Hard wins: somebody who has said both things about one block has asked
     * for the stricter of the two, and guessing the other way lets a block that
     * is supposed to need the scale be dismissed with a tap.
     */
    fun classify(occurrence: JSONObject): String? {
        val attributes = attributesOf(occurrence) ?: return null
        if (isTrue(attributes, ATTR_HARD)) return AlarmSchedule.KIND_HARD
        if (isTrue(attributes, ATTR_RING) || isTrue(attributes, ATTR_SOFT)) {
            return AlarmSchedule.KIND_SOFT
        }
        return null
    }

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
    fun buildAlarms(schedule: JSONObject, nowMs: Long): List<JSONObject> {
        val occurrences = schedule.optJSONArray("occurrences") ?: JSONArray()

        data class Matched(val occurrence: JSONObject, val kind: String, val at: LocalMoment)

        val matched = mutableListOf<Matched>()
        for (i in 0 until occurrences.length()) {
            val occurrence = occurrences.optJSONObject(i) ?: continue
            val kind = classify(occurrence) ?: continue
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

        return AlarmSchedule.pruneTombstones(
            JSONObject().apply {
                put("version", 2)
                put("alarms", merged)
            },
            nowMs
        )
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
