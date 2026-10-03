package org.example.lanalarm

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The phone's own ontoplano client.
 *
 * This used to live only on the PC: the phone shipped readings to a daemon on
 * the LAN and the daemon republished them. That made an ordinary alarm clock
 * depend on a desktop being awake, and it is the reason the app held a
 * websocket and an HTTP server open all day. ontoplano is on the internet and
 * the phone has the internet, so the middleman is gone.
 *
 * Blocking by design — every call is made from a background executor. Async
 * would buy nothing here: there is one caller and it is already off the main
 * thread.
 *
 * `ontoplano.py` on the PC implements the same contract against the same
 * endpoints. Where the two must agree byte-for-byte (the derived `external_id`,
 * the reading of a partial-reject response) the rule is stated in both places.
 */
class Ontoplano(private val config: Config) {

    companion object {
        private const val TAG = "Ontoplano"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Matches `ontoplano.STREAM_SLUG`. */
        const val STREAM_SLUG = "massalarme.weight"

        const val SOURCE = "massalarme"

        /** The server caps a push at this many points. */
        const val MAX_POINTS_PER_REQUEST = 500

        /** Where the hosted ontoplano lives, so nobody has to type it. */
        const val DEFAULT_BASE_URL = "https://app.ontoplano.com"

        /**
         * Scopes without which a feature simply does not work:
         *
         *  - `streams:write`   publish weigh-ins. The whole point.
         *  - `schedule:read`   read planner occurrences, so a task can ring.
         *
         * `streams:read` is deliberately absent: massalarme is the source of
         * truth for its own readings and never needs them back.
         */
        val REQUIRED_SCOPES = listOf("streams:write", "schedule:read")

        /**
         * Scopes that only buy presentation. A token without these connects,
         * publishes and rings exactly the same; the stream is just rendered
         * from the server's defaults instead of from our manifest.
         */
        val OPTIONAL_SCOPES = listOf("plugin:declare")

        val ALL_SCOPES = REQUIRED_SCOPES + OPTIONAL_SCOPES

        /**
         * What this plugin publishes and which meta keys it sets, declared at
         * startup so ontoplano can render the stream without guessing.
         *
         * The shape here is unconfirmed against the hosted server, which
         * answers this body with `each attribute key must be an object` — it
         * wants a map of descriptors where we send a list of names. Until that
         * is pinned down, treat [declarePlugin] as best-effort: it decides
         * nothing about whether the connection works.
         */
        fun manifest(): JSONObject = JSONObject().apply {
            put("source", SOURCE)
            put("name", "Massalarme")
            put("description", "Alarm clock that will not stop until you weigh yourself.")
            put("metaKeys", JSONArray(listOf("impedance", "alarm_name", "measurements", "raw_value")))
        }

        fun streamDeclaration(): JSONObject = JSONObject().apply {
            put("slug", STREAM_SLUG)
            put("name", "Weight")
            put("source", SOURCE)
            put("kind", "measurement")
            put("unit", "kg")
            put("display", "line_chart")
        }
    }

    data class Config(
        val baseUrl: String,
        val token: String,
        val timeoutSeconds: Long = 15
    )

    /** One weigh-in, in the shape `/points` wants. */
    data class Point(
        val externalId: String,
        val at: String,
        val value: Double,
        val meta: JSONObject? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("external_id", externalId)
            put("at", at)
            put("value", value)
            meta?.let { if (it.length() > 0) put("meta", it) }
        }
    }

    /**
     * Outcome of a push.
     *
     * `delivered` folds accepted and duplicate together on purpose: a duplicate
     * means the server already holds the point, which is the designed result of
     * a retry, not a failure. Anything the server did not explicitly reject was
     * stored — that is the only reading that keeps the outbound queue correct
     * when a batch half-lands.
     */
    data class PushResult(
        val delivered: List<String>,
        val rejected: List<Pair<String, String>>,
        val duplicates: Int
    )

    // ─── Errors ──────────────────────────────────────────────────────

    /**
     * @param retryable whether holding the reading and trying later can ever
     *   work. A 422 is not retryable and a queue that keeps resending one is
     *   stuck forever; a 503 is, and dropping the reading loses the user's data.
     */
    open class Failure(
        message: String,
        val status: Int? = null,
        val retryable: Boolean = true
    ) : Exception(message)

    /** 401 — the token is wrong or revoked. Reconnecting is the fix. */
    class AuthFailure(message: String) : Failure(message, 401, retryable = false)

    /** 402 — plan limit. */
    class PlanLimitFailure(message: String) : Failure(message, 402, retryable = false)

    /**
     * 403 — the token is valid but too narrow. Distinct from 401 because the
     * fix is a wider token, not a new one.
     */
    class ScopeFailure(message: String) : Failure(message, 403, retryable = false)

    /** 404 — the stream is not declared yet. Declare once, then retry. */
    class StreamMissingFailure(message: String) : Failure(message, 404, retryable = true)

    /** 429 — back off, honouring Retry-After when the server sends one. */
    class RateLimitedFailure(message: String, val retryAfterSeconds: Long?) :
        Failure(message, 429, retryable = true)

    // ─── Transport ───────────────────────────────────────────────────

    private val http = OkHttpClient.Builder()
        .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
        .build()

    private fun url(path: String) = config.baseUrl.trimEnd('/') + path

    private fun request(path: String) = Request.Builder()
        .url(url(path))
        .header("Authorization", "Bearer ${config.token}")
        .header("Content-Type", "application/json")

    /**
     * Map a non-2xx onto a typed failure.
     *
     * The status is the whole message here: callers decide what to do purely on
     * the type, so a wrong mapping silently turns a permanent error into an
     * infinite retry.
     */
    private fun failureFor(response: Response, body: String): Failure {
        val message = runCatching {
            val error = JSONObject(body).optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: error?.optString("code")?.takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.take(300).ifBlank { "HTTP ${response.code}" }

        return when (response.code) {
            401 -> AuthFailure(message)
            402 -> PlanLimitFailure(message)
            403 -> ScopeFailure(message)
            404 -> StreamMissingFailure(message)
            429 -> RateLimitedFailure(
                message,
                response.header("Retry-After")?.toLongOrNull()
            )
            in 500..599 -> Failure(message, response.code, retryable = true)
            // 400 and 422: the payload is wrong and will never become right.
            else -> Failure(message, response.code, retryable = false)
        }
    }

    private fun execute(request: Request): String {
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failureFor(response, body)
                return body
            }
        } catch (e: IOException) {
            // No response at all: the network, not the payload. Always retryable.
            throw Failure("cannot reach ontoplano: ${e.message}", null, retryable = true)
        }
    }

    // ─── Calls ───────────────────────────────────────────────────────

    /**
     * Register what this plugin is and what meta keys it writes.
     *
     * Idempotent, so it runs at every startup rather than being remembered as
     * "done" — a server restored from a backup would otherwise never learn
     * about a plugin that declared itself once, months ago.
     *
     * Throws like any other call, but no caller should treat that as fatal:
     * see [manifest] for why. Cosmetic metadata is not a reason to tell
     * somebody their alarm clock is broken.
     */
    fun declarePlugin() {
        val body = manifest().toString().toRequestBody(JSON)
        execute(request("/api/v1/plugin").put(body).build())
        Log.i(TAG, "Plugin manifest declared")
    }

    /** Upsert the stream. An already-existing stream is not an error. */
    fun declareStream() {
        val body = streamDeclaration().toString().toRequestBody(JSON)
        try {
            execute(request("/api/v1/streams").post(body).build())
        } catch (e: Failure) {
            // 409 lands here as non-retryable; it means "already declared",
            // which is exactly the state we wanted.
            if (e.status == 409) {
                Log.d(TAG, "Stream $STREAM_SLUG already declared")
                return
            }
            throw e
        }
        Log.i(TAG, "Stream $STREAM_SLUG declared")
    }

    /** Token introspection. Needs no scope, so it is the honest setup check. */
    fun whoami(): JSONObject = JSONObject(execute(request("/api/v1/me").get().build()))

    fun pushPoints(points: List<Point>): PushResult {
        if (points.isEmpty()) return PushResult(emptyList(), emptyList(), 0)
        require(points.size <= MAX_POINTS_PER_REQUEST) {
            "batch of ${points.size} exceeds the $MAX_POINTS_PER_REQUEST-point limit"
        }

        val payload = JSONObject().apply {
            put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
        }
        val body = execute(
            request("/api/v1/streams/$STREAM_SLUG/points")
                .post(payload.toString().toRequestBody(JSON))
                .build()
        )
        return interpret(points, JSONObject(body))
    }

    /**
     * Upcoming planner occurrences — what alarms are derived from.
     *
     * `at_local` comes back as naive wall-clock with a separate `timezone`
     * field. It is deliberately not converted: the alarm rings at the wall-clock
     * time the user wrote down, and a UTC round trip is how an alarm ends up an
     * hour out twice a year.
     */
    fun fetchSchedule(days: Int = 7): JSONObject {
        val bounded = days.coerceIn(1, 31)
        return JSONObject(
            execute(request("/api/v1/schedule/upcoming?days=$bounded").get().build())
        )
    }

    /**
     * Turn the server's counts into per-point outcomes.
     *
     * The response reports accepted and duplicates as *counts* but only names
     * the rejects. So anything not named was stored, and the counts are useful
     * for reporting only.
     */
    private fun interpret(points: List<Point>, body: JSONObject): PushResult {
        val rejected = mutableListOf<Pair<String, String>>()
        val rejectedIds = mutableSetOf<String>()

        val array = body.optJSONArray("rejected") ?: JSONArray()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("external_id")
            if (id.isBlank()) continue
            rejectedIds.add(id)
            rejected.add(id to entry.optString("reason", "unspecified"))
        }

        val delivered = points.map { it.externalId }.filterNot { it in rejectedIds }
        val duplicates = body.optInt("duplicates", 0).coerceIn(0, delivered.size)
        return PushResult(delivered, rejected, duplicates)
    }
}
