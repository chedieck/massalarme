package org.example.lanalarm

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Drains the outbound weigh-in queue into ontoplano.
 *
 * Retries are safe by construction: `external_id` is derived from the reading
 * itself, so a resend comes back as a duplicate rather than a second point on
 * the user's chart. That is what lets this be as eager as it likes — after a
 * weigh-in, on app foreground, whenever the network comes back — without any
 * bookkeeping about what might already have landed.
 *
 * This used to POST to the PC daemon, which republished upwards. The daemon is
 * no longer in the path.
 */
class ReadingUploader(private val context: Context, private val client: Ontoplano) {

    companion object {
        private const val TAG = "ReadingUploader"

        /** Small enough that a flaky connection loses little, well under the server cap. */
        const val BATCH = 100
    }

    /**
     * Ship everything pending and return how many rows left the queue.
     *
     * Throws [Ontoplano.Failure] on a failure the caller should report; a
     * non-retryable one has already been recorded against the offending rows,
     * so nothing is lost by giving up here.
     */
    fun upload(): Int {
        val store = ReadingStore(context)
        try {
            var delivered = 0
            while (true) {
                val batch = store.pending(BATCH)
                if (batch.isEmpty()) return delivered

                val before = store.pendingCount()
                val points = batch.map { reading ->
                    Ontoplano.Point(
                        externalId = reading.externalId,
                        at = reading.capturedAtUtc,
                        value = reading.weightKg,
                        meta = JSONObject().apply {
                            reading.impedance?.let { put("impedance", it) }
                            reading.alarmName?.let { put("alarm_name", it) }
                            reading.rawValue?.let { put("raw_value", it) }
                            put("measurements", reading.measurements)
                        }
                    )
                }

                val ids = batch.map { it.externalId }
                val result = try {
                    client.pushPoints(points)
                } catch (e: Ontoplano.Failure) {
                    if (e.retryable) {
                        // Hold the rows; the reading is the user's data and a
                        // server having a bad minute is not a reason to lose it.
                        store.recordAttempt(ids, e.message)
                    } else {
                        // Never going to be accepted. Recording the reason stops
                        // the queue retrying it forever and leaves a trail.
                        ids.forEach { store.markRejected(it, e.message ?: "rejected") }
                    }
                    throw e
                }

                result.rejected.forEach { (id, reason) -> store.markRejected(id, reason) }
                store.markSynced(result.delivered)
                delivered += result.delivered.size

                Log.i(
                    TAG,
                    "Pushed ${result.delivered.size} reading(s), " +
                        "${result.duplicates} already known, ${result.rejected.size} rejected"
                )

                // If nothing left the queue this pass, the next one would fetch
                // the same rows forever.
                if (store.pendingCount() >= before) {
                    throw Ontoplano.Failure(
                        "ontoplano resolved none of ${batch.size} reading(s)",
                        null,
                        retryable = true
                    )
                }
            }
        } finally {
            store.close()
        }
    }
}
