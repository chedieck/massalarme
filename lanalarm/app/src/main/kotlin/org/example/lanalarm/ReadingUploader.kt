package org.example.lanalarm

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Ships queued weigh-ins to the PC, which republishes them to ontoplano.
 *
 * Retries are safe by construction: `external_id` is derived from the reading,
 * so the PC answers a resend with `duplicates`, not a second point. That means
 * this uploader can be as eager as it likes — on a new reading, on regaining
 * connectivity, on app foreground — without risking the user's chart.
 */
class ReadingUploader(private val context: Context) {

    companion object {
        private const val TAG = "ReadingUploader"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        data class Delivered(val count: Int, val remaining: Int) : Result()
        object NothingToDo : Result()
        data class NotConfigured(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Drain the queue. Blocking — call from a background thread.
     */
    fun upload(): Result {
        val store = ReadingStore(context)
        try {
            val secret = AppSettings.secret(context)
                ?: return Result.NotConfigured("no shared secret — scan the QR code")
            val baseUrl = AppSettings.pcBaseUrl(context)
                ?: return Result.NotConfigured("PC address unknown — rescan the QR code")

            var delivered = 0
            while (true) {
                val batch = store.pending(ReadingStore.MAX_BATCH)
                if (batch.isEmpty()) break

                val before = store.pendingCount()
                val outcome = postBatch(store, baseUrl, secret, batch)
                if (outcome is Result.Failed) {
                    recordError(outcome.reason)
                    return if (delivered > 0) {
                        Result.Delivered(delivered, store.pendingCount())
                    } else {
                        outcome
                    }
                }
                delivered += (outcome as Result.Delivered).count

                // If nothing left the queue this pass, the next one would fetch
                // the same rows forever. Stop and let the next trigger retry.
                if (store.pendingCount() >= before) {
                    return Result.Failed("PC resolved none of ${batch.size} reading(s)")
                }
            }

            if (delivered == 0) return Result.NothingToDo

            recordSuccess()
            return Result.Delivered(delivered, store.pendingCount())
        } finally {
            store.close()
        }
    }

    private fun postBatch(
        store: ReadingStore,
        baseUrl: String,
        secret: String,
        batch: List<ReadingStore.Reading>
    ): Result {
        val payload = JSONObject().apply {
            put("readings", JSONArray().apply {
                batch.forEach { reading ->
                    put(JSONObject().apply {
                        put("external_id", reading.externalId)
                        put("captured_at", reading.capturedAtUtc)
                        put("weight_kg", reading.weightKg)
                        reading.impedance?.let { put("impedance", it) }
                        reading.rawValue?.let { put("raw_value", it) }
                        reading.alarmName?.let { put("alarm_name", it) }
                        put("measurements", reading.measurements)
                    })
                }
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/readings?key=$secret")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val ids = batch.map { it.externalId }

        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    store.recordAttempt(ids, "PC rejected the shared secret")
                    return Result.Failed("PC rejected the shared secret — rescan the QR code")
                }
                if (!response.isSuccessful) {
                    store.recordAttempt(ids, "HTTP ${response.code}")
                    return Result.Failed("PC returned HTTP ${response.code}")
                }

                val body = JSONObject(response.body?.string().orEmpty())
                val rejected = body.optJSONArray("rejected") ?: JSONArray()
                val rejectedIds = mutableSetOf<String>()
                for (i in 0 until rejected.length()) {
                    val entry = rejected.optJSONObject(i) ?: continue
                    val externalId = entry.optString("external_id")
                    val reason = entry.optString("reason", "unspecified")
                    if (externalId.isNotBlank()) {
                        rejectedIds.add(externalId)
                        // Malformed readings never become valid. Stop resending.
                        store.markRejected(externalId, reason)
                    }
                }

                // Anything not explicitly rejected was stored, whether it counted
                // as accepted or duplicate. Both mean the PC has it.
                val stored = ids.filterNot { rejectedIds.contains(it) }
                store.markSynced(stored)

                Log.i(
                    TAG,
                    "Uploaded ${stored.size} reading(s), ${rejectedIds.size} rejected"
                )
                Result.Delivered(stored.size, store.pendingCount())
            }
        } catch (e: IOException) {
            store.recordAttempt(ids, e.message ?: "network error")
            Result.Failed("PC unreachable: ${e.message}")
        } catch (e: org.json.JSONException) {
            store.recordAttempt(ids, "malformed response")
            Result.Failed("PC returned a malformed response")
        }
    }

    private fun recordSuccess() {
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_LAST_UPLOAD_OK, ScaleCodec.utcIso(System.currentTimeMillis()))
            .putString(AppSettings.KEY_LAST_UPLOAD_ERROR, null)
            .apply()
    }

    private fun recordError(reason: String) {
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_LAST_UPLOAD_ERROR, reason)
            .apply()
        Log.w(TAG, "Upload failed: $reason")
    }
}
