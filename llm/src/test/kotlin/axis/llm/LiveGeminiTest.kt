package axis.llm

import axis.core.Sensitive
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Hits the real Gemini API. Skips itself when GEMINI_KEY_1/2 are not set in the environment or .env. */
class LiveGeminiTest {
    private fun loadKeys(): List<KeyEntry> {
        val dotenv = File(".env").takeIf { it.exists() }?.readLines().orEmpty()
            .filter { '=' in it && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }
        return listOf("GEMINI_KEY_1", "GEMINI_KEY_2").mapIndexedNotNull { i, name ->
            val v = System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotenv[name]?.takeIf { it.isNotBlank() }
            v?.let { KeyEntry(name.lowercase(), Provider.GEMINI, Sensitive(it), CostClass.FREE, i) }
        }
    }

    @Test
    fun `a real key answers a trivial prompt`() = runBlocking {
        val keys = loadKeys()
        assumeTrue(keys.isNotEmpty(), "No GEMINI_KEY_* set; skipping live test")
        val pool = ProviderPool(keys, GeminiBackend(), System::currentTimeMillis)

        val result = pool.generate(LlmRequest("Reply with exactly the single word: pong"))

        val success = assertIs<PoolResult.Success>(result)
        assertTrue(success.response.text.contains("pong", ignoreCase = true), "got: ${success.response.text}")
    }

    @Test
    fun `two real keys cascade when the first is forced invalid`() = runBlocking {
        val keys = loadKeys()
        assumeTrue(keys.size == 2, "Need both keys; skipping")
        val bad = KeyEntry("bad", Provider.GEMINI, Sensitive("AIza-not-a-real-key"), CostClass.FREE, -1)
        val pool = ProviderPool(listOf(bad) + keys, GeminiBackend(), System::currentTimeMillis)

        val success = assertIs<PoolResult.Success>(pool.generate(LlmRequest("Reply with the single word: ok")))

        assertEquals(KeyHealth.INVALID, pool.status().first { it.id == "bad" }.health)
        assertTrue(success.response.keyId != "bad")
    }
}
