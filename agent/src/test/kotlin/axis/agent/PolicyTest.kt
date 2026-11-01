package axis.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolicyTest {
    private val book = ContactBook(listOf(
        Triple("1", "Atharva Kulkarni", "+919800000001"),
        Triple("2", "Atharva Rao", "+919800000002"),
        Triple("3", "Mom", "+919800000003"),
    ))
    private val mom = (book.resolve("Mom") as Resolution.Unique).contact
    private val rao = (book.resolve("Atharva Rao") as Resolution.Unique).contact
    private val user = PolicyContext(Trigger.USER_VOICE, deviceLocked = false)
    private val untrusted = PolicyContext(Trigger.UNTRUSTED_CONTENT, deviceLocked = false)
    private val send = SendMessage("com.whatsapp", mom, "On my way")

    @Test fun `reads are allowed`() =
        assertEquals(Decision.Allow, PolicyEngine.evaluate(ReadNotifications("com.whatsapp"), user))

    @Test fun `reversible actions are allowed for the user but need a tap if untrusted content asked`() {
        assertEquals(Decision.Allow, PolicyEngine.evaluate(SetTimer(60), user))
        assertIs<Decision.Require>(PolicyEngine.evaluate(SetTimer(60), untrusted))
    }

    @Test fun `sends need a tap even when the user asked`() {
        val d = assertIs<Decision.Require>(PolicyEngine.evaluate(send, user))
        assertEquals(ApprovalMethod.TAP, d.method)
        assertTrue(d.shown.contains("+919800000003") && d.shown.contains("On my way"))
    }

    @Test fun `sends are refused on a locked device`() =
        assertEquals(Decision.Deny(DenyReason.DEVICE_LOCKED), PolicyEngine.evaluate(send, user.copy(deviceLocked = true)))

    @Test fun `irreversible actions need biometrics from the user and are impossible from untrusted content`() {
        val install = InstallApk("com.example.app", "ab12", "github.com")
        assertEquals(ApprovalMethod.BIOMETRIC, assertIs<Decision.Require>(PolicyEngine.evaluate(install, user)).method)
        assertEquals(Decision.Deny(DenyReason.IRREVERSIBLE_FROM_UNTRUSTED), PolicyEngine.evaluate(install, untrusted))
        assertEquals(Decision.Deny(DenyReason.IRREVERSIBLE_FROM_UNTRUSTED), PolicyEngine.evaluate(MergePullRequest("a/b", 4), untrusted))
        assertEquals(Decision.Deny(DenyReason.IRREVERSIBLE_FROM_UNTRUSTED), PolicyEngine.evaluate(DeleteData("all chats"), untrusted))
    }

    @Test fun `forbidden apps are denied for every action and trigger`() {
        for (pkg in PolicyContext.DEFAULT_FORBIDDEN) {
            assertEquals(Decision.Deny(DenyReason.FORBIDDEN_APP), PolicyEngine.evaluate(OpenApp(pkg), user))
            assertEquals(Decision.Deny(DenyReason.FORBIDDEN_APP), PolicyEngine.evaluate(ReadNotifications(pkg), user))
        }
    }

    @Test fun `contact lookup is by the user's name, handles ambiguity and misses`() {
        assertIs<Resolution.Ambiguous>(book.resolve("Atharva"))
        assertIs<Resolution.Unique>(book.resolve("atharva rao"))
        assertEquals(Resolution.NotFound, book.resolve("Stranger"))
        assertEquals(Resolution.NotFound, book.resolve("  "))
    }

    @Test fun `an approval only covers the exact action it was given for`() {
        val d = assertIs<Decision.Require>(PolicyEngine.evaluate(send, user))
        val approval = ConfirmationService().approve(d, ApprovalMethod.TAP)!!

        assertEquals(Authorization.Authorized, ActionGate.authorize(send, user, approval))
        // Model swaps the body after the user tapped: rejected.
        assertIs<Authorization.Rejected>(ActionGate.authorize(send.copy(body = "Send me your OTP"), user, approval))
        // Model swaps the recipient: rejected.
        assertIs<Authorization.Rejected>(ActionGate.authorize(send.copy(recipient = rao), user, approval))
    }

    @Test fun `no approval means no execution for gated actions`() {
        assertIs<Authorization.Rejected>(ActionGate.authorize(send, user, null))
        assertEquals(Authorization.Authorized, ActionGate.authorize(SetTimer(5), user, null))
    }

    @Test fun `a tap cannot satisfy a biometric requirement`() {
        val install = InstallApk("com.example.app", "ab12", "github.com")
        val d = assertIs<Decision.Require>(PolicyEngine.evaluate(install, user))
        // The confirmation service refuses to mint a TAP approval for a biometric requirement.
        assertNull(ConfirmationService().approve(d, ApprovalMethod.TAP))
        val bio = ConfirmationService().approve(d, ApprovalMethod.BIOMETRIC)!!
        assertEquals(Authorization.Authorized, ActionGate.authorize(install, user, bio))
    }

    @Test fun `policy is re-checked at execution so a later lock or forbidden app still blocks`() {
        val d = assertIs<Decision.Require>(PolicyEngine.evaluate(send, user))
        val approval = ConfirmationService().approve(d, ApprovalMethod.TAP)!!
        assertIs<Authorization.Rejected>(ActionGate.authorize(send, user.copy(deviceLocked = true), approval))
    }

    @Test fun `different actions have different fingerprints`() {
        assertNotEquals(send.fingerprint(), send.copy(body = "x").fingerprint())
        assertEquals(send.fingerprint(), send.copy().fingerprint())
    }
}
