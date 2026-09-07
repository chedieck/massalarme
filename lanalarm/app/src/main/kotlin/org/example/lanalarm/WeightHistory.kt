package org.example.lanalarm

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The weigh-in history shown in the Weight tab.
 *
 * The phone's own store, and nothing else. This used to ask the PC daemon first
 * and fall back to local, which meant the chart changed depending on whether a
 * desktop happened to be awake — and made an offline app feel broken. Everything
 * this phone has ever measured is in [ReadingStore]; ontoplano holds a copy for
 * charting elsewhere, but it is a projection, not the record.
 */
object WeightHistory {

    private const val TAG = "WeightHistory"

    data class Entry(
        val externalId: String,
        val atMillis: Long,
        val weightKg: Double,
        val impedance: Double?,
        val alarmName: String?,
        val source: String
    )

    data class Result(val entries: List<Entry>, val error: String?)

    private fun utcParser(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun parseUtc(text: String): Long? =
        runCatching { utcParser().parse(text)?.time }.getOrNull()

    /** Blocking — call from a background thread. */
    fun load(context: Context, limit: Int = 400): Result =
        Result(loadLocal(context, limit), error = null)

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
                    source = "phone"
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
