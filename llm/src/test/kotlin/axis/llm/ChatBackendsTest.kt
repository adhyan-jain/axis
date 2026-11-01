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

class ChatBackendsTest {
    private val server = MockWebServer().apply { start() }
    private val base = server.url("/").toString()
    private fun key(p: Provider) = KeyEntry("k", p, Sensitive("sk-test-secret"), CostClass.FREE)

    @AfterTest fun tearDown() = server.shutdown()

    private fun enqueue(code: Int, body: String, headers: Map<String, String> = emptyMap()) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).also { r -> headers.forEach { (k, v) -> r.setHeader(k, v) } })

    private fun openAi(models: List<String> = listOf("m1")) = OpenAiCompatibleBackend(base, models)
    private fun callOpenAi(b: LlmBackend = openAi()) = runBlocking { b.generate(key(Provider.OPENAI), LlmRequest("hello")) }
    private fun callClaude(b: ClaudeBackend = ClaudeBackend(base, listOf("c1"))) = runBlocking { b.generate(key(Provider.CLAUDE), LlmRequest("hello")) }

    private val openAiOk = """{"choices":[{"message":{"content":"hi there"},"finish_reason":"stop"}]}"""
    private val claudeOk = """{"content":[{"type":"text","text":"hi "},{"type":"text","text":"there"}],"stop_reason":"end_turn"}"""

    @Test fun `openai compatible success uses bearer auth and not the url`() {
        enqueue(200, openAiOk)
        assertEquals(CallResult.Ok("hi there"), callOpenAi())
        val r = server.takeRequest()
        assertEquals("Bearer sk-test-secret", r.getHeader("Authorization"))
        assertFalse(r.requestUrl.toString().contains("sk-test"))
        assertTrue(r.path!!.endsWith("/chat/completions"))
        assertTrue(r.body.readUtf8().contains("\"model\":\"m1\""))
    }

    @Test fun `openai compatible maps statuses`() {
        enqueue(429, "{}", mapOf("Retry-After" to "5")); assertEquals(CallResult.RateLimited(5_000), callOpenAi())
        enqueue(401, "{}"); assertEquals(CallResult.AuthFailed, callOpenAi())
        enqueue(500, "{}"); assertEquals(CallResult.Transient, callOpenAi())
        enqueue(200, "garbage"); assertEquals(CallResult.Transient, callOpenAi())
        enqueue(200, """{"choices":[{"message":{"content":""},"finish_reason":"content_filter"}]}""")
        assertEquals(CallResult.Blocked("content_filter"), callOpenAi())
    }

    @Test fun `openai compatible falls back to the next model`() {
        enqueue(503, "{}"); enqueue(200, openAiOk)
        assertEquals(CallResult.Ok("hi there"), callOpenAi(openAi(listOf("m1", "m2"))))
    }

    @Test fun `claude success sends the key and version headers`() {
        enqueue(200, claudeOk)
        assertEquals(CallResult.Ok("hi there"), callClaude())
        val r = server.takeRequest()
        assertEquals("sk-test-secret", r.getHeader("x-api-key"))
        assertEquals("2023-06-01", r.getHeader("anthropic-version"))
        assertFalse(r.requestUrl.toString().contains("sk-test"))
        assertTrue(r.path!!.endsWith("/v1/messages"))
    }

    @Test fun `claude maps statuses including overloaded and refusals`() {
        enqueue(429, "{}", mapOf("Retry-After" to "2")); assertEquals(CallResult.RateLimited(2_000), callClaude())
        enqueue(401, "{}"); assertEquals(CallResult.AuthFailed, callClaude())
        enqueue(529, """{"type":"error"}"""); assertEquals(CallResult.Transient, callClaude())
        enqueue(200, """{"content":[],"stop_reason":"refusal"}"""); assertEquals(CallResult.Blocked("refusal"), callClaude())
    }

    @Test fun `network failure is transient for every adapter`() {
        server.shutdown()
        assertIs<CallResult.Transient>(callOpenAi())
        assertIs<CallResult.Transient>(callClaude())
    }

    @Test fun `the multi provider backend routes each key to its own adapter`() {
        val seen = mutableListOf<Provider>()
        val spy = { p: Provider -> LlmBackend { _, _ -> seen += p; CallResult.Ok(p.name) } }
        val multi = MultiProviderBackend(Provider.entries.associateWith(spy))
        Provider.entries.forEach { p -> assertEquals(CallResult.Ok(p.name), runBlocking { multi.generate(key(p), LlmRequest("x")) }) }
        assertEquals(Provider.entries.toList(), seen.toList())
        assertEquals(CallResult.Transient, runBlocking { MultiProviderBackend(emptyMap()).generate(key(Provider.GROQ), LlmRequest("x")) })
    }

    @Test fun `a pool cascades across different providers`() = runBlocking {
        val backend = LlmBackend { k, _ -> if (k.provider == Provider.GEMINI) CallResult.RateLimited(60_000) else CallResult.Ok("from ${k.provider}") }
        val pool = ProviderPool(
            listOf(KeyEntry("g", Provider.GEMINI, Sensitive("a"), CostClass.FREE, 0), KeyEntry("c", Provider.CLAUDE, Sensitive("b"), CostClass.FREE, 1)),
            backend, { 0L },
        )
        assertEquals("from CLAUDE", assertIs<PoolResult.Success>(pool.generate(LlmRequest("x"))).response.text)
    }
}
