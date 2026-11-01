package axis.llm

import axis.core.Sensitive
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GeminiBackendTest {
    private val server = MockWebServer().apply { start() }
    private val backend = GeminiBackend(baseUrl = server.url("/").toString(), models = listOf("test-model"))
    private val key = KeyEntry("k1", Provider.GEMINI, Sensitive("AIza-test-secret"))

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun call(): CallResult = runBlocking { backend.generate(key, LlmRequest("hello")) }

    private fun enqueue(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })

    @Test
    fun `success returns the joined text parts`() {
        enqueue(200, """{"candidates":[{"content":{"parts":[{"text":"Hel"},{"text":"lo"}]},"finishReason":"STOP"}]}""")
        assertEquals(CallResult.Ok("Hello"), call())
    }

    @Test
    fun `key is sent in a header and never in the url`() {
        enqueue(200, """{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}""")
        call()
        val recorded = server.takeRequest()
        assertEquals("AIza-test-secret", recorded.getHeader("x-goog-api-key"))
        assertFalse(recorded.requestUrl.toString().contains("AIza"))
        assertTrue(recorded.requestUrl.toString().contains("test-model:generateContent"))
        assertTrue(recorded.body.readUtf8().contains("hello"))
    }

    @Test
    fun `429 uses the Retry-After header`() {
        enqueue(429, "{}", mapOf("Retry-After" to "7"))
        assertEquals(CallResult.RateLimited(7_000), call())
    }

    @Test
    fun `429 falls back to the retryDelay detail`() {
        enqueue(429, """{"error":{"code":429,"details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"13s"}]}}""")
        assertEquals(CallResult.RateLimited(13_000), call())
    }

    @Test
    fun `429 without any hint has no delay so the pool backs off`() {
        enqueue(429, """{"error":{"code":429,"message":"quota"}}""")
        assertEquals(CallResult.RateLimited(null), call())
    }

    @Test
    fun `401 403 and invalid key 400 are auth failures`() {
        enqueue(401, "{}"); assertEquals(CallResult.AuthFailed, call())
        enqueue(403, "{}"); assertEquals(CallResult.AuthFailed, call())
        enqueue(400, """{"error":{"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}""")
        assertEquals(CallResult.AuthFailed, call())
    }

    @Test
    fun `5xx and malformed bodies are transient`() {
        enqueue(503, "{}"); assertEquals(CallResult.Transient, call())
        enqueue(200, "not json"); assertEquals(CallResult.Transient, call())
        enqueue(200, """{"candidates":[]}"""); assertEquals(CallResult.Transient, call())
    }

    @Test
    fun `safety blocks surface as Blocked`() {
        enqueue(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        assertEquals(CallResult.Blocked("SAFETY"), call())
        enqueue(200, """{"candidates":[{"finishReason":"SAFETY"}]}""")
        assertEquals(CallResult.Blocked("SAFETY"), call())
    }

    @Test
    fun `network failure is transient`() {
        server.shutdown()
        assertIs<CallResult.Transient>(call())
    }

    private fun ladder() = GeminiBackend(baseUrl = server.url("/").toString(), models = listOf("m1", "m2"))
    private fun callLadder(): CallResult = runBlocking { ladder().generate(key, LlmRequest("hello")) }
    private val okBody = """{"candidates":[{"content":{"parts":[{"text":"fine"}]}}]}"""

    @Test
    fun `an overloaded or retired model falls back to the next model`() {
        enqueue(503, "{}"); enqueue(200, okBody)
        assertEquals(CallResult.Ok("fine"), callLadder())
        server.takeRequest().let { assertTrue(it.requestUrl.toString().contains("m1:")) }
        server.takeRequest().let { assertTrue(it.requestUrl.toString().contains("m2:")) }

        enqueue(404, "{}"); enqueue(200, okBody)
        assertEquals(CallResult.Ok("fine"), callLadder())
    }

    @Test
    fun `key is rate limited only when every model is, using the soonest retry`() {
        enqueue(429, "{}", mapOf("Retry-After" to "9")); enqueue(429, "{}", mapOf("Retry-After" to "4"))
        assertEquals(CallResult.RateLimited(4_000), callLadder())
    }

    @Test
    fun `a model rate limit does not stop a working model`() {
        enqueue(429, "{}", mapOf("Retry-After" to "9")); enqueue(200, okBody)
        assertEquals(CallResult.Ok("fine"), callLadder())
    }

    @Test
    fun `auth failure stops the ladder immediately`() {
        enqueue(401, "{}")
        assertEquals(CallResult.AuthFailed, callLadder())
        assertEquals(1, server.requestCount)
    }
}
