package axis.agent

import axis.core.Sensitive
import axis.llm.CallResult
import axis.llm.CostClass
import axis.llm.KeyEntry
import axis.llm.LlmBackend
import axis.llm.Provider
import axis.llm.ProviderPool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatControllerTest {
    private val book = ContactBook(listOf(Triple("1", "Atharva Rao", "+919800000002")))
    private val ran = mutableListOf<Action>()
    private var locked = false

    private fun controller(modelSays: CallResult): ChatController {
        val pool = ProviderPool(
            listOf(KeyEntry("k", Provider.GEMINI, Sensitive("x"), CostClass.FREE)),
            LlmBackend { _, _ -> modelSays }, { 0L },
        )
        return ChatController(Planner(pool, book), { a -> ran += a; RunResult.Done("Done: ${a.canonical()}") }, { locked })
    }

    private val sendJson = CallResult.Ok("""{"action":"send_message","recipient_name":"Atharva","body":"hello"}""")

    @Test fun `a reversible action runs immediately`() = runBlocking {
        val c = controller(CallResult.Ok("""{"action":"set_timer","seconds":60}"""))
        c.submit("timer 1 minute")
        assertEquals(listOf<Action>(SetTimer(60)), ran)
        assertNull(c.state.value.pending)
    }

    @Test fun `a send waits for approval and does not run until approved`() = runBlocking {
        val c = controller(sendJson)
        c.submit("message Atharva hello")

        val pending = assertNotNull(c.state.value.pending)
        assertTrue(pending.shown.contains("+919800000002") && pending.shown.contains("hello"))
        assertTrue(ran.isEmpty())

        c.approve(ApprovalMethod.TAP)
        assertEquals(1, ran.size)
        assertNull(c.state.value.pending)
    }

    @Test fun `cancel runs nothing`() = runBlocking {
        val c = controller(sendJson)
        c.submit("message Atharva hello")
        c.cancel()
        assertTrue(ran.isEmpty())
        assertNull(c.state.value.pending)
        assertTrue(c.state.value.lines.last().text.contains("Nothing"))
    }

    @Test fun `approving with no pending action does nothing`() {
        val c = controller(sendJson)
        c.approve(ApprovalMethod.TAP)
        assertTrue(ran.isEmpty())
    }

    @Test fun `a locked phone refuses to send`() = runBlocking {
        locked = true
        val c = controller(sendJson)
        c.submit("message Atharva hello")
        assertNull(c.state.value.pending)
        assertTrue(ran.isEmpty())
        assertTrue(c.state.value.lines.last().text.contains("Unlock"))
    }

    @Test fun `the phone locking after the sheet appeared still blocks execution`() = runBlocking {
        val c = controller(sendJson)
        c.submit("message Atharva hello")
        locked = true
        c.approve(ApprovalMethod.TAP)
        assertTrue(ran.isEmpty())
        assertTrue(c.state.value.lines.last().text.startsWith("Blocked"))
    }

    @Test fun `a new request replaces a pending approval so only one action can run`() = runBlocking {
        val c = controller(sendJson)
        c.submit("message Atharva hello")
        c.submit("message Atharva hello again")
        assertNotNull(c.state.value.pending)

        c.approve(ApprovalMethod.TAP)
        assertEquals(1, ran.size)
        c.approve(ApprovalMethod.TAP)          // a second tap has nothing left to approve
        assertEquals(1, ran.size)
    }

    @Test fun `unavailable AI and unknown contacts become plain messages`() = runBlocking {
        val busy = controller(CallResult.RateLimited(60_000))
        busy.submit("hi")
        assertTrue(busy.state.value.lines.last().text.contains("busy"))

        val nobody = controller(CallResult.Ok("""{"action":"send_message","recipient_name":"Zed","body":"x"}"""))
        nobody.submit("message Zed x")
        assertTrue(nobody.state.value.lines.last().text.contains("couldn't find"))
        assertTrue(ran.isEmpty())
    }

    @Test fun `blank input and overlapping submits are ignored`() = runBlocking {
        val c = controller(sendJson)
        c.submit("   ")
        assertTrue(c.state.value.lines.isEmpty())
        assertIs<ChatState>(c.state.value)
    }

    @Test fun `busy is cleared even when planning fails`() = runBlocking {
        val c = controller(CallResult.Transient)
        c.submit("hi")
        assertEquals(false, c.state.value.busy)
    }
}
