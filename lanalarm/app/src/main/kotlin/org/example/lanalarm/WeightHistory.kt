package org.example.lanalarm

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The weigh-in history shown in the Weight tab.
 *
 * The phone's own store first, always, and the one the chart can be drawn from
 * with no network at all. This used to ask the PC daemon *instead*, which meant
 * the chart changed depending on whether a desktop happened to be awake.
 *
 * ontoplano is then folded in on top, because the phone's store only holds what
 * this phone measured: somebody who backfilled years of scale history, or
 * switched phones, saw a chart with two points on it and no sign of the rest.
 * Local wins on a collision — it has the impedance and the alarm name, which the
 * published point does not carry back.
 */
object WeightHistory {

    private const val TAG = "WeightHistory"

    /** Where an entry came from, and what the Weight tab labels it. */
    const val SOURCE_PHONE = "phone"
    const val SOURCE_ONTOPLANO = "ontoplano"

    data class Entry(
        val externalId: String,
        val atMillis: Long,
        val weightKg: Double,
        val impedance: Double?,
        val alarmName: String?,
        val source: String
    )

    data class Result(val entries: List<Entry>, val error: String?)

    /**
     * Both spellings of a UTC instant.
     *
     * The phone writes seconds precision; ontoplano answers whatever
     * `Date.toISOString()` produces, which carries milliseconds. Trying only one
     * of the two silently dropped every published point on the floor.
     */
    private val UTC_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    )

    private fun utcParser(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

    fun parseUtc(text: String): Long? = UTC_PATTERNS.firstNotNullOfOrNull { pattern ->
        runCatching { utcParser(pattern).parse(text)?.time }.getOrNull()
    }

    /**
     * Blocking — call from a background thread.
     *
     * A failure to reach ontoplano is not reported as an error: the history it
     * holds is an addition, and the entries from this phone are still the whole
     * truth about this phone. Nagging about a missing `streams:read` scope on a
     * screen somebody opened to look at their weight would be noise about a
     * feature they never asked for.
     */
    fun load(context: Context, limit: Int = 400): Result {
        val local = loadLocal(context, limit)
        return Result(merge(local, loadPublished(context, limit)), error = null)
    }

    /**
     * Local entries, plus the published ones local has never heard of.
     *
     * Keyed on `external_id`, which is derived from the reading itself — the
     * same property that makes a re-publish a duplicate rather than a second
     * point makes it the right key here.
     */
    internal fun merge(local: List<Entry>, published: List<Entry>): List<Entry> {
        val known = local.map { it.externalId }.toSet()
        return (local + published.filterNot { it.externalId in known })
            .sortedByDescending { it.atMillis }
    }

    private fun loadPublished(context: Context, limit: Int): List<Entry> {
        val client = AppSettings.ontoplanoClient(context) ?: return emptyList()
        return try {
            parsePoints(client.fetchPoints(limit))
        } catch (e: Exception) {
            // Expected on a token without `streams:read`, and on any flaky
            // minute. Logged, never surfaced.
            Log.i(TAG, "Published history unavailable: ${e.message}")
            emptyList()
        }
    }

    /** `{"points": [{"external_id", "at", "value", "meta"}], "count": n}`. */
    internal fun parsePoints(body: org.json.JSONObject): List<Entry> {
        val points = body.optJSONArray("points") ?: return emptyList()
        return (0 until points.length()).mapNotNull { i ->
            val point = points.optJSONObject(i) ?: return@mapNotNull null
            val externalId = point.optString("external_id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val at = parseUtc(point.optString("at")) ?: return@mapNotNull null
            val weight = point.optDouble("value", Double.NaN)
            if (weight.isNaN()) return@mapNotNull null
            val meta = point.optJSONObject("meta")
            Entry(
                externalId = externalId,
                atMillis = at,
                weightKg = weight,
                impedance = meta?.optDouble("impedance", Double.NaN)?.takeIf { !it.isNaN() },
                alarmName = meta?.optString("alarm_name")?.takeIf { it.isNotBlank() },
                source = SOURCE_ONTOPLANO
            )
        }
    }

    private fun loadLocal(context: Context, limit: Int): List<Entry> {
        val store = ReadingStore(context)
        return try {
            store.latest(limit).mapNotNull { reading ->
                val at = parseUtc(reading.capturedAtUtc) ?: return@mapNotNull null
                Entry(
                    externalId = reading.externalId,
                    atMillis = at,
                    weightKg = reading.weightKg,
                    impedance = reading.impedance,
                    alarmName = reading.alarmName,
                    source = SOURCE_PHONE
                )
            }
        } finally {
            store.close()
        }
    }

    /** "12 Aug, 08:30" in the phone's own timezone. */
    fun formatWhen(atMillis: Long): String =
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(atMillis))

    /**
     * Change across the window, as a signed string. Compares the oldest and
     * newest entries rather than a fitted trend: with a handful of points a
     * regression line says less than "you are 1.2 kg up on three weeks ago".
     */
    fun describeTrend(entries: List<Entry>): String {
        if (entries.size < 2) return ""
        val newest = entries.maxByOrNull { it.atMillis } ?: return ""
        val oldest = entries.minByOrNull { it.atMillis } ?: return ""
        val delta = newest.weightKg - oldest.weightKg
        val sign = if (delta >= 0) "+" else "−"
        return String.format(Locale.US, "%s%.1f kg", sign, kotlin.math.abs(delta))
    }
}
