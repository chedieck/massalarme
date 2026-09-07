package org.example.lanalarm

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * One pass of everything the phone and ontoplano owe each other.
 *
 * Three jobs, in this order, because each one only makes sense if the last
 * worked: say what this plugin is, ship the weigh-ins it has captured, then read
 * back which planned tasks should ring.
 *
 * Nothing here is required for the alarm to work. massalarme is the source of
 * truth for its own readings and its own schedule; ontoplano is a projection and
 * a source of extra alarms. With sync switched off the app behaves exactly as it
 * does with the phone in flight mode — which is the whole reason the PC daemon
 * stopped being a dependency.
 *
 * Blocking. Callers run it on a background executor.
 */
object OntoplanoSync {

    private const val TAG = "OntoplanoSync"

    /** How far ahead to ask for planner occurrences. */
    const val HORIZON_DAYS = 7

    data class Outcome(
        val uploaded: Int,
        val pending: Int,
        val alarmsDerived: Int,
        val error: String?
    ) {
        val ok: Boolean get() = error == null
    }

    fun run(context: Context): Outcome {
        val client = AppSettings.ontoplanoClient(context)
            ?: return Outcome(0, pendingCount(context), 0, null).also {
                Log.d(TAG, "ontoplano sync is off or unconfigured")
            }

        var uploaded = 0
        var derived = 0

        try {
            // Idempotent, and cheap next to the round trips below. Declaring
            // every time is what makes a restored-from-backup server work.
            client.declarePlugin()
            client.declareStream()

            uploaded = ReadingUploader(context, client).upload()
            derived = syncSchedule(context, client)
        } catch (e: Ontoplano.Failure) {
            recordError(context, describe(e))
            return Outcome(uploaded, pendingCount(context), derived, describe(e))
        } catch (e: Exception) {
            recordError(context, e.message ?: "unexpected failure")
            return Outcome(uploaded, pendingCount(context), derived, e.message)
        }

        recordSuccess(context)
        return Outcome(uploaded, pendingCount(context), derived, null)
    }

    /** Just the token check, for the settings screen's "who am I" line. */
    fun whoami(context: Context): JSONObject? {
        val client = AppSettings.ontoplanoClient(context) ?: return null
        return runCatching { client.whoami() }
            .onFailure { Log.w(TAG, "whoami failed: ${it.message}") }
            .getOrNull()
    }

    /**
     * Pull the planner and fold the matching occurrences into the schedule.
     *
     * Returns how many alarms ontoplano currently accounts for. Writing the
     * schedule announces itself the same way any other background write does —
     * an open Alarms tab that is not told simply keeps showing yesterday.
     */
    private fun syncSchedule(context: Context, client: Ontoplano): Int {
        val rules = OntoplanoSchedule.rulesFrom(
            AppSettings.ontoplanoPattern(context),
            AppSettings.ontoplanoKind(context)
        )
        if (rules.isEmpty()) {
            Log.d(TAG, "No ontoplano alarm rule set — not reading the schedule")
            return 0
        }

        val schedule = client.fetchSchedule(HORIZON_DAYS)
        val now = System.currentTimeMillis()
        val derived = OntoplanoSchedule.buildAlarms(schedule, rules, now)

        val prefs = AppSettings.prefs(context)
        val current = prefs.getString(AppSettings.KEY_ALARMS, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().apply {
                put("version", 2)
                put("alarms", JSONArray())
            }

        val merged = OntoplanoSchedule.mergeIntoSchedule(current, derived, now, HORIZON_DAYS)
        if (merged.toString() == current.toString()) return derived.size

        prefs.edit()
            .putString(AppSettings.KEY_ALARMS, merged.toString())
            .putLong(AppSettings.KEY_LAST_SYNC, now)
            .apply()

        AlarmScheduler.rescheduleNext(context)
        context.sendBroadcast(
            Intent(AlarmService.ACTION_ALARMS_CHANGED).setPackage(context.packageName)
        )
        Log.i(TAG, "${derived.size} alarm(s) from ontoplano")
        return derived.size
    }

    /**
     * A message worth putting on a settings screen.
     *
     * "HTTP 403" tells the user nothing they can act on; "the token is missing
     * streams:write" tells them exactly which box to tick when they reissue it.
     */
    fun describe(failure: Ontoplano.Failure): String = when (failure) {
        is Ontoplano.AuthFailure -> "ontoplano rejected the token — paste a new one"
        is Ontoplano.ScopeFailure ->
            "Token is missing a scope. Needs ${Ontoplano.REQUIRED_SCOPES.joinToString(", ")}"
        is Ontoplano.PlanLimitFailure -> "ontoplano plan limit reached: ${failure.message}"
        is Ontoplano.RateLimitedFailure -> "Rate limited — will retry"
        else -> failure.message ?: "sync failed"
    }

    private fun pendingCount(context: Context): Int {
        val store = ReadingStore(context)
        return try {
            store.pendingCount()
        } finally {
            store.close()
        }
    }

    private fun recordSuccess(context: Context) {
        AppSettings.prefs(context).edit()
            .putString(
                AppSettings.KEY_LAST_UPLOAD_OK,
                ScaleCodec.utcIso(System.currentTimeMillis())
            )
            .remove(AppSettings.KEY_LAST_UPLOAD_ERROR)
            .apply()
    }

    private fun recordError(context: Context, reason: String) {
        Log.w(TAG, "Sync failed: $reason")
        AppSettings.prefs(context).edit()
            .putString(AppSettings.KEY_LAST_UPLOAD_ERROR, reason)
            .apply()
    }
}
