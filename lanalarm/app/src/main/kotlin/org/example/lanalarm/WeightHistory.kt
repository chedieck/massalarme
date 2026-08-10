package org.example.lanalarm

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * The weigh-in history shown in the Weight tab.
 *
 * The PC is asked first because it holds the whole record, including everything
 * captured back when it was the thing listening to the scale. The phone's own
 * store is the fallback — it only contains what this phone measured, but it
 * works with the PC off, which is the point of the phone-first design.
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

    data class Result(val entries: List<Entry>, val fromPc: Boolean, val error: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun utcParser(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    fun parseUtc(text: String): Long? =
        runCatching { utcParser().parse(text)?.time }.getOrNull()

    /** Blocking — call from a background thread. */
    fun load(context: Context, limit: Int = 400): Result {
        val fromPc = loadFromPc(context, limit)
        if (fromPc != null) return Result(fromPc, fromPc = true, error = null)

        val local = loadLocal(context, limit)
        return Result(
            local,
            fromPc = false,
            error = if (local.isEmpty()) null else "Showing this phone's readings — PC unreachable"
        )
    }

    private fun loadFromPc(context: Context, limit: Int): List<Entry>? {
        val secret = AppSettings.secret(context) ?: return null
        val baseUrl = AppSettings.pcBaseUrl(context) ?: return null

        val request = Request.Builder()
            .url("$baseUrl/weigh-ins?key=$secret&limit=$limit")
            .get()
            .build()

        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "PC returned HTTP ${response.code}")
                    return null
                }
                val body = JSONObject(response.body?.string().orEmpty())
                val array = body.optJSONArray("weigh_ins") ?: return emptyList()
                val entries = mutableListOf<Entry>()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val at = parseUtc(item.optString("captured_at")) ?: continue
                    entries.add(
                        Entry(
                            externalId = item.optString("external_id"),
                            atMillis = at,
                            weightKg = item.optDouble("weight_kg", 0.0),
                            impedance = item.optDouble("impedance").takeIf { !it.isNaN() && it > 0 },
                            alarmName = item.optString("alarm_name").takeIf { it.isNotBlank() },
                            source = item.optString("source", "pc")
                        )
                    )
                }
                entries.sortedByDescending { it.atMillis }
            }
        } catch (e: Exception) {
            Log.d(TAG, "PC history unavailable: ${e.message}")
            null
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
