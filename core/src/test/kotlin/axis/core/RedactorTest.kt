package axis.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {
    @Test fun `emails and phone numbers are replaced`() {
        val r = Redactor.redact("Mail rahul.k@example.com or call +91 98765 43210 today")
        assertFalse(r.text.contains("rahul") || r.text.contains("98765"))
        assertTrue(r.text.contains("[EMAIL_1]") && r.text.contains("[PHONE_1]"))
    }

    @Test fun `otp codes are redacted when labelled`() {
        val r = Redactor.redact("Your OTP is 482913. Do not share.")
        assertFalse(r.text.contains("482913"))
        assertTrue(r.text.contains("[CODE_1]"))
    }

    @Test fun `card and aadhaar style numbers are redacted`() {
        val r = Redactor.redact("Card 4111 1111 1111 1111 and id 1234 5678 9012")
        assertFalse(r.text.contains("4111") || r.text.contains("5678"))
    }

    @Test fun `the same value gets the same placeholder`() {
        val r = Redactor.redact("a@b.co wrote to a@b.co")
        assertEquals("[EMAIL_1] wrote to [EMAIL_1]", r.text)
    }

    @Test fun `ordinary text and short numbers are untouched`() {
        val s = "Meet at 5 pm in room 204, bring 3 copies."
        assertEquals(s, Redactor.redact(s).text)
    }

    @Test fun `restore puts originals back for display`() {
        val r = Redactor.redact("Call +91 98765 43210")
        assertEquals("Calling +91 98765 43210", r.restore("Calling [PHONE_1]"))
    }

    @Test fun `redacted objects never print their contents`() {
        assertFalse(Redactor.redact("a@b.co").toString().contains("a@b.co"))
    }
}
