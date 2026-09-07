package org.example

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.example.lanalarm.Ontoplano
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The one network path left in the app.
 *
 * Every test here is about a decision the client makes on the user's data: is
 * this reading safe to drop, or must it be held and retried? Getting that wrong
 * in one direction loses a weigh-in for good; in the other it wedges the queue
 * on a payload the server will never accept. A real local server is the only
 * honest way to check it, so these run against one.
 */
class OntoplanoClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client() = Ontoplano(
        Ontoplano.Config(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "onto_test_token",
            timeoutSeconds = 5
        )
    )

    private fun enqueue(code: Int, body: String = "{}", vararg headers: Pair<String, String>) {
        val response = MockResponse().setResponseCode(code).setBody(body)
        headers.forEach { (name, value) -> response.addHeader(name, value) }
        server.enqueue(response)
    }

    private fun point(id: String, value: Double = 73.8) =
        Ontoplano.Point(externalId = id, at = "2026-09-07T07:12:03Z", value = value)

    // ─── Auth and shape ──────────────────────────────────────────────

    @Test
    fun `every request carries the bearer token`() {
        enqueue(200, """{"accepted":1,"duplicates":0,"rejected":[]}""")
        client().pushPoints(listOf(point("a")))

        val request = server.takeRequest()
        assertEquals("Bearer onto_test_token", request.getHeader("Authorization"))
    }

    @Test
    fun `points go to the declared stream slug`() {
        enqueue(200, """{"accepted":1,"duplicates":0,"rejected":[]}""")
        client().pushPoints(listOf(point("a")))

        assertEquals(
            "/api/v1/streams/${Ontoplano.STREAM_SLUG}/points",
            server.takeRequest().path
        )
    }

    @Test
    fun `the plugin manifest is a PUT naming the meta keys this app writes`() {
        enqueue(200)
        client().declarePlugin()

        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/api/v1/plugin", request.path)

        val body = JSONObject(request.body.readUtf8())
        assertEquals(Ontoplano.SOURCE, body.getString("source"))
        val keys = body.getJSONArray("metaKeys")
        val declared = (0 until keys.length()).map { keys.getString(it) }
        assertTrue(
            "impedance is written on every bare-feet weigh-in and has to be declared",
            "impedance" in declared
        )
    }

    @Test
    fun `an already-declared stream is not an error`() {
        // 409 is the server saying the stream is exactly as we wanted it.
        enqueue(409, """{"error":{"code":"already_exists"}}""")
        client().declareStream()
    }

    @Test
    fun `the schedule request is bounded to a sane window`() {
        enqueue(200, """{"occurrences":[]}""")
        client().fetchSchedule(days = 900)

        assertTrue(
            "asking for three years of planner is a bug, not a request",
            server.takeRequest().path!!.endsWith("days=31")
        )
    }

    // ─── Error mapping ───────────────────────────────────────────────

    @Test
    fun `401 is permanent and says to reconnect`() {
        enqueue(401, """{"error":{"message":"token revoked"}}""")
        val failure = failureFrom { client().whoami() }

        assertTrue(failure is Ontoplano.AuthFailure)
        assertFalse("retrying a revoked token forever helps nobody", failure.retryable)
    }

    @Test
    fun `403 is kept distinct from 401`() {
        // The fix for a scope error is a wider token, not a new one, and a user
        // told "reconnect" will reconnect the same too-narrow token all morning.
        enqueue(403, """{"error":{"message":"missing scope streams:write"}}""")
        val failure = failureFrom { client().pushPoints(listOf(point("a"))) }

        assertTrue(failure is Ontoplano.ScopeFailure)
        assertFalse(failure.retryable)
    }

    @Test
    fun `422 is permanent, so a bad reading does not wedge the queue`() {
        enqueue(422, """{"error":{"message":"value out of range"}}""")
        val failure = failureFrom { client().pushPoints(listOf(point("a", value = -1.0))) }

        assertFalse(failure.retryable)
        assertEquals(422, failure.status)
    }

    @Test
    fun `429 is retryable and carries Retry-After`() {
        enqueue(429, "{}", "Retry-After" to "120")
        val failure = failureFrom { client().pushPoints(listOf(point("a"))) }

        assertTrue(failure is Ontoplano.RateLimitedFailure)
        assertTrue(failure.retryable)
        assertEquals(120L, (failure as Ontoplano.RateLimitedFailure).retryAfterSeconds)
    }

    @Test
    fun `a 5xx holds the reading rather than dropping it`() {
        enqueue(503, """{"error":{"message":"upstream down"}}""")
        val failure = failureFrom { client().pushPoints(listOf(point("a"))) }

        assertTrue("a server having a bad minute must not lose a weigh-in", failure.retryable)
    }

    @Test
    fun `a 404 means the stream is missing, which is fixable`() {
        enqueue(404, "{}")
        val failure = failureFrom { client().pushPoints(listOf(point("a"))) }

        assertTrue(failure is Ontoplano.StreamMissingFailure)
        assertTrue(failure.retryable)
    }

    @Test
    fun `an unreachable server is retryable, not a rejection`() {
        server.shutdown()
        val failure = failureFrom { client().whoami() }
        assertTrue(failure.retryable)
    }

    @Test
    fun `a non-JSON error body still produces a usable message`() {
        enqueue(500, "<html>502 Bad Gateway</html>")
        val failure = failureFrom { client().whoami() }
        assertTrue(failure.message!!.contains("Bad Gateway"))
    }

    // ─── Partial success ─────────────────────────────────────────────

    @Test
    fun `anything not explicitly rejected counts as stored`() {
        // The server reports accepted and duplicates as counts but only names
        // the rejects. Reading it any other way loses track of what landed.
        enqueue(
            200,
            """{"accepted":1,"duplicates":1,"rejected":[{"external_id":"c","reason":"bad value"}]}"""
        )

        val result = client().pushPoints(listOf(point("a"), point("b"), point("c")))

        assertEquals(listOf("a", "b"), result.delivered)
        assertEquals(listOf("c" to "bad value"), result.rejected)
    }

    @Test
    fun `a duplicate is delivered, not a failure`() {
        // Retries are the designed path: external_id is derived from the reading
        // so a resend is meant to come back as a duplicate.
        enqueue(200, """{"accepted":0,"duplicates":1,"rejected":[]}""")

        val result = client().pushPoints(listOf(point("a")))
        assertEquals(listOf("a"), result.delivered)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun `a duplicate count larger than the batch does not corrupt the result`() {
        enqueue(200, """{"accepted":0,"duplicates":99,"rejected":[]}""")

        val result = client().pushPoints(listOf(point("a")))
        assertEquals(listOf("a"), result.delivered)
        assertEquals(1, result.duplicates)
    }

    @Test
    fun `an empty push makes no request at all`() {
        client().pushPoints(emptyList())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `meta is omitted when there is nothing to say`() {
        enqueue(200, """{"accepted":1,"duplicates":0,"rejected":[]}""")
        client().pushPoints(listOf(point("a")))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        val sent = body.getJSONArray("points").getJSONObject(0)
        assertFalse("an empty meta object is noise on every point", sent.has("meta"))
        assertEquals("a", sent.getString("external_id"))
    }

    private inline fun failureFrom(block: () -> Unit): Ontoplano.Failure {
        try {
            block()
        } catch (e: Ontoplano.Failure) {
            return e
        }
        fail("expected an Ontoplano.Failure")
        throw IllegalStateException("unreachable")
    }
}
