package axis.agent

import axis.core.Sensitive
import axis.llm.CallResult
import axis.llm.CostClass
import axis.llm.KeyEntry
import axis.llm.LlmBackend
import axis.llm.LlmRequest
import axis.llm.Provider
import axis.llm.ProviderPool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest {
    private val book = ContactBook(listOf(
        Triple("1", "Atharva Rao", "+919800000002"),
        Triple("2", "Priya Shah", "+919800000010"),
        Triple("3", "Priya Nair", "+919800000011"),
    ))
    private val key = KeyEntry("k", Provider.GEMINI, Sensitive("x"), CostClass.FREE)
    private val sent = mutableListOf<String>()

    private fun planner(modelSays: CallResult) = Planner(
        ProviderPool(listOf(key), LlmBackend { _, req: LlmRequest -> sent += req.prompt; modelSays }, { 0L }),
        book,
    )

    private fun plan(modelSays: String, request: String = "do it") =
        runBlocking { planner(CallResult.Ok(modelSays)).plan(request) }

    @Test fun `send to a known contact becomes a SendMessage with the resolved contact`() {
        val out = plan("""{"action":"send_message","app":"whatsapp","recipient_name":"Atharva","body":"hi"}""")
        val action = assertIs<SendMessage>(assertIs<PlanOutcome.Proposed>(out).action)
        assertEquals("+919800000002", action.recipient.number)
        assertEquals("com.whatsapp", action.appPackage)
    }

    @Test fun `ambiguous names ask which one`() {
        val out = plan("""{"action":"send_message","recipient_name":"Priya","body":"hi"}""")
        assertEquals(listOf("Priya Shah", "Priya Nair"), assertIs<PlanOutcome.Clarify>(out).choices)
    }

    @Test fun `the model cannot invent a recipient by giving a raw number`() {
        val out = plan("""{"action":"send_message","recipient_name":"+919999999999","body":"hi"}""")
        assertIs<PlanOutcome.Say>(out)
    }

    @Test fun `json inside a code fence is accepted`() {
        val out = plan("```json\n{\"action\":\"set_timer\",\"seconds\":90}\n```")
        assertEquals(SetTimer(90), assertIs<PlanOutcome.Proposed>(out).action)
    }

    @Test fun `timers outside 1 second to 24 hours are refused`() {
        assertIs<PlanOutcome.Say>(plan("""{"action":"set_timer","seconds":0}"""))
        assertIs<PlanOutcome.Say>(plan("""{"action":"set_timer","seconds":999999}"""))
    }

    @Test fun `unknown apps and garbage output fall back to a spoken reply`() {
        assertIs<PlanOutcome.Say>(plan("""{"action":"open_app","app":"nonexistent"}"""))
        assertIs<PlanOutcome.Say>(plan("sorry I cannot"))
        assertIs<PlanOutcome.Say>(plan("""{"action":"format_phone"}"""))
    }

    @Test fun `personal identifiers are redacted before the model sees them and restored after`() {
        val out = runBlocking { planner(CallResult.Ok("""{"action":"say","text":"Calling [PHONE_1]"}""")).plan("Call +91 98765 43210") }
        assertFalse(sent.single().contains("98765"))
        assertEquals("Calling +91 98765 43210", assertIs<PlanOutcome.Say>(out).text)
    }

    @Test fun `pool failures become user-facing unavailable states`() {
        val busy = runBlocking { planner(CallResult.RateLimited(60_000)).plan("hi") }
        assertTrue(assertIs<PlanOutcome.Unavailable>(busy).retryAtMs == 60_000L)
        assertIs<PlanOutcome.Unavailable>(runBlocking { planner(CallResult.Blocked("SAFETY")).plan("hi") })
    }

    @Test fun `a proposed send still goes through policy and needs a tap`() {
        val out = plan("""{"action":"send_message","recipient_name":"Atharva","body":"hi"}""")
        val action = assertIs<PlanOutcome.Proposed>(out).action
        assertIs<Decision.Require>(PolicyEngine.evaluate(action, PolicyContext(Trigger.USER_VOICE, deviceLocked = false)))
    }
}
