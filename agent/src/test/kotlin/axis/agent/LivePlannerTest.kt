package axis.agent

import axis.core.Sensitive
import axis.llm.CostClass
import axis.llm.GeminiBackend
import axis.llm.KeyEntry
import axis.llm.Provider
import axis.llm.ProviderPool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real Gemini, real prompt. Skips when no key is configured. */
class LivePlannerTest {
    private fun keys(): List<KeyEntry> {
        val dotenv = File(".env").takeIf { it.exists() }?.readLines().orEmpty().filter { '=' in it && !it.startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim().trim('"') }
        return listOf("GEMINI_KEY_1", "GEMINI_KEY_2").mapIndexedNotNull { i, n ->
            (System.getenv(n)?.takeIf { it.isNotBlank() } ?: dotenv[n]?.takeIf { it.isNotBlank() })
                ?.let { KeyEntry(n.lowercase(), Provider.GEMINI, Sensitive(it), CostClass.FREE, i) }
        }
    }

    private fun planner(): Planner {
        val k = keys(); assumeTrue(k.isNotEmpty(), "No GEMINI_KEY_* set; skipping")
        val book = ContactBook(listOf(Triple("1", "Atharva Rao", "+919800000002"), Triple("2", "Mom", "+919800000003")))
        return Planner(ProviderPool(k, GeminiBackend(), System::currentTimeMillis), book)
    }

    @Test fun `a natural send request becomes a SendMessage to the right contact`() = runBlocking {
        val out = planner().plan("Send Atharva the link https://axis-site.vercel.app on WhatsApp")
        val action = assertIs<SendMessage>(assertIs<PlanOutcome.Proposed>(out).action)
        assertEquals("Atharva Rao", action.recipient.displayName)
        assert(action.body.contains("axis-site.vercel.app")) { "body was: ${action.body}" }
    }

    private fun fullPlanner(): Planner {
        val k = keys(); assumeTrue(k.isNotEmpty(), "No GEMINI_KEY_* set; skipping")
        val book = ContactBook(listOf(Triple("1", "Atharva Rao", "+919800000002"), Triple("2", "Mom", "+919800000003")), mapOf("1" to "atharva@example.com"))
        val store = MessageStore({ System.currentTimeMillis() })
        store.ingest(IncomingMessage("n1", "com.whatsapp", "Atharva Rao", axis.core.Untrusted("are we still on for tonight?"), System.currentTimeMillis(), true))
        val memory = MemoryStore(object : MutableFactStorage { override fun load() = emptyList<String>(); override fun save(facts: List<String>) {} })
        val gh = DeploymentSource { DeploymentResult.Found("https://axis-site.vercel.app") }
        return Planner(ProviderPool(k, GeminiBackend(), System::currentTimeMillis), book, store, memory, gh, { listOf("9:30 Standup") })
    }

    @Test fun `alarm call email reply remember and deployment requests map to the right actions`() = runBlocking {
        val p = fullPlanner()
        assertEquals(SetAlarm(7, 30, ""), (assertIs<PlanOutcome.Proposed>(p.plan("Wake me up at 7:30 am")).action as SetAlarm).copy(label = ""))
        assertIs<DialContact>(assertIs<PlanOutcome.Proposed>(p.plan("Call Mom")).action)
        assertIs<ComposeEmail>(assertIs<PlanOutcome.Proposed>(p.plan("Email Atharva saying the report is ready")).action)
        assertIs<ReplyToMessage>(assertIs<PlanOutcome.Proposed>(p.plan("Reply to Atharva yes I'm in")).action)
        assertIs<Remember>(assertIs<PlanOutcome.Proposed>(p.plan("Remember that my locker number is 42")).action)
        val link = assertIs<SendMessage>(assertIs<PlanOutcome.Proposed>(p.plan("Get the deployment link for me/axis-site from GitHub and send it to Atharva on WhatsApp")).action)
        assertEquals("https://axis-site.vercel.app", link.body)
    }

    @Test fun `reading messages and the briefing produce spoken text`() = runBlocking {
        val p = fullPlanner()
        assertTrue(assertIs<PlanOutcome.Say>(p.plan("What messages did I get?")).text.isNotBlank())
        assertTrue(assertIs<PlanOutcome.Say>(p.plan("Give me my morning briefing")).text.contains("Standup"))
    }

    @Test fun `a timer request becomes a SetTimer`() = runBlocking {
        val out = planner().plan("Set a timer for 5 minutes")
        assertEquals(SetTimer(300), assertIs<PlanOutcome.Proposed>(out).action)
    }
}
