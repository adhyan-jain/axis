package axis.llm

import axis.core.Sensitive
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun key(id: String, provider: Provider = Provider.GEMINI, cost: CostClass = CostClass.FREE, order: Int = 0) =
    KeyEntry(id, provider, Sensitive("secret-$id"), cost, order)

/** Scripted backend: each key id plays back its queued results, then repeats the last one. */
private class ScriptedBackend(private val script: Map<String, List<CallResult>>) : LlmBackend {
    val calls = mutableListOf<String>()
    private val cursor = mutableMapOf<String, Int>()

    override suspend fun generate(key: KeyEntry, request: LlmRequest): CallResult {
        calls += key.id
        val results = script.getValue(key.id)
        val i = cursor.getOrDefault(key.id, 0)
        cursor[key.id] = i + 1
        return results[minOf(i, results.lastIndex)]
    }
}

class ProviderPoolTest {
    private var now = 1_000L
    private val clock = { now }
    private val req = LlmRequest("hi")

    @Test
    fun `rate limited key cascades to the next key`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "a" to listOf(CallResult.RateLimited(60_000)),
            "b" to listOf(CallResult.Ok("from b")),
        ))
        val pool = ProviderPool(listOf(key("a", order = 0), key("b", order = 1)), backend, clock)

        val result = pool.generate(req)

        assertEquals("from b", assertIs<PoolResult.Success>(result).response.text)
        assertEquals(listOf("a", "b"), backend.calls)
    }

    @Test
    fun `cooling key is skipped until its cooldown passes`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "a" to listOf(CallResult.RateLimited(60_000), CallResult.Ok("a again")),
            "b" to listOf(CallResult.Ok("from b")),
        ))
        val pool = ProviderPool(listOf(key("a", order = 0), key("b", order = 1)), backend, clock)

        pool.generate(req)                 // a rate-limited -> b answers
        backend.calls.clear()
        pool.generate(req)                 // a still cooling -> straight to b
        assertEquals(listOf("b"), backend.calls)

        now += 60_001
        backend.calls.clear()
        val result = pool.generate(req)    // cooldown over -> a is first again
        assertEquals("a again", assertIs<PoolResult.Success>(result).response.text)
        assertEquals(listOf("a"), backend.calls)
    }

    @Test
    fun `auth failure marks the key invalid permanently`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "a" to listOf(CallResult.AuthFailed),
            "b" to listOf(CallResult.Ok("from b")),
        ))
        val pool = ProviderPool(listOf(key("a", order = 0), key("b", order = 1)), backend, clock)

        pool.generate(req)
        now += 10 * 60_000
        backend.calls.clear()
        pool.generate(req)

        assertEquals(listOf("b"), backend.calls)
        assertEquals(KeyHealth.INVALID, pool.status().first { it.id == "a" }.health)
    }

    @Test
    fun `transient failure falls through without a cooldown`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "a" to listOf(CallResult.Transient, CallResult.Ok("a recovered")),
            "b" to listOf(CallResult.Ok("from b")),
        ))
        val pool = ProviderPool(listOf(key("a", order = 0), key("b", order = 1)), backend, clock)

        pool.generate(req)                 // a transient -> b
        backend.calls.clear()
        val result = pool.generate(req)    // a is tried again right away

        assertEquals("a recovered", assertIs<PoolResult.Success>(result).response.text)
        assertEquals(listOf("a"), backend.calls)
    }

    @Test
    fun `free keys run before paid keys and paid keys need opt in`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "free" to listOf(CallResult.RateLimited(60_000)),
            "paid" to listOf(CallResult.Ok("from paid")),
        ))
        val keys = listOf(key("paid", cost = CostClass.PAID, order = 0), key("free", order = 1))

        val blocked = ProviderPool(keys, backend, clock, allowPaid = false).generate(req)
        assertIs<PoolResult.Exhausted>(blocked)
        assertEquals(listOf("free"), backend.calls)

        backend.calls.clear()
        val allowed = ProviderPool(keys, backend, clock, allowPaid = true).generate(req)
        assertEquals("from paid", assertIs<PoolResult.Success>(allowed).response.text)
        assertEquals(listOf("free", "paid"), backend.calls)
    }

    @Test
    fun `all keys cooling reports the earliest retry time`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "a" to listOf(CallResult.RateLimited(30_000)),
            "b" to listOf(CallResult.RateLimited(90_000)),
        ))
        val pool = ProviderPool(listOf(key("a", order = 0), key("b", order = 1)), backend, clock)

        val result = pool.generate(req)

        assertEquals(1_000L + 30_000L, assertIs<PoolResult.Exhausted>(result).retryAtMs)
    }

    @Test
    fun `no keys or only invalid keys reports NoUsableKeys`() = runBlocking {
        assertIs<PoolResult.NoUsableKeys>(ProviderPool(emptyList(), ScriptedBackend(emptyMap()), clock).generate(req))

        val backend = ScriptedBackend(mapOf("a" to listOf(CallResult.AuthFailed)))
        val pool = ProviderPool(listOf(key("a")), backend, clock)
        pool.generate(req)
        assertIs<PoolResult.NoUsableKeys>(pool.generate(req))
    }

    @Test
    fun `repeated rate limits without a hint back off exponentially`() = runBlocking {
        val backend = ScriptedBackend(mapOf("a" to listOf(CallResult.RateLimited(null))))
        val pool = ProviderPool(listOf(key("a")), backend, clock)

        val first = assertIs<PoolResult.Exhausted>(pool.generate(req)).retryAtMs - now
        now += first
        val second = assertIs<PoolResult.Exhausted>(pool.generate(req)).retryAtMs - now

        assertEquals(first * 2, second)
    }

    @Test
    fun `status never exposes key material`() = runBlocking {
        val pool = ProviderPool(listOf(key("a")), ScriptedBackend(emptyMap()), clock)
        assertEquals(false, pool.status().toString().contains("secret-a"))
    }

    @Test
    fun `a safety block stops the cascade instead of shopping other keys`() = runBlocking {
        val backend = ScriptedBackend(mapOf(
            "a" to listOf(CallResult.Blocked("SAFETY")),
            "b" to listOf(CallResult.Ok("should not be asked")),
        ))
        val pool = ProviderPool(listOf(key("a", order = 0), key("b", order = 1)), backend, clock)

        assertEquals(PoolResult.Blocked("SAFETY"), pool.generate(req))
        assertEquals(listOf("a"), backend.calls)
    }
}
