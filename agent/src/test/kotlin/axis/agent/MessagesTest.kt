package axis.agent

import axis.core.Untrusted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagesTest {
    private var now = 1_000_000L
    private val store = MessageStore({ now })
    private fun msg(id: String, text: String, sender: String = "Atharva Rao", app: String = "com.whatsapp", at: Long = now) =
        IncomingMessage(id, app, sender, Untrusted(text), at, canReply = true)

    @Test fun `ordinary messages are stored and returned newest last`() {
        assertTrue(store.ingest(msg("1", "lunch?")))
        assertTrue(store.ingest(msg("2", "see you at 2")))
        assertEquals(listOf("1", "2"), store.recent().map { it.id })
    }

    @Test fun `otps bank alerts and amounts are never stored`() {
        listOf("Your OTP is 123456", "Rs. 5,000 debited from a/c XX1234", "verification code 998877", "UPI txn of ₹200 done")
            .forEachIndexed { i, t -> assertFalse(store.ingest(msg("s$i", t)), t) }
        assertTrue(store.recent().isEmpty())
    }

    @Test fun `only allowed apps are stored`() {
        assertFalse(store.ingest(msg("1", "hi", app = "com.android.chrome")))
        assertFalse(store.ingest(msg("2", "hi", app = "com.google.android.gm")))
    }

    @Test fun `duplicates are ignored and old messages expire`() {
        store.ingest(msg("1", "hi")); store.ingest(msg("1", "hi"))
        assertEquals(1, store.recent().size)
        now += 25 * 60 * 60 * 1000
        assertTrue(store.recent().isEmpty())
    }

    @Test fun `the store is capped`() {
        val small = MessageStore({ now }, maxItems = 3)
        (1..5).forEach { small.ingest(msg("$it", "m$it")) }
        assertEquals(listOf("3", "4", "5"), small.recent().map { it.id })
    }

    @Test fun `sender lookup matches by first name and groups by person`() {
        store.ingest(msg("1", "a", sender = "Atharva Rao")); store.ingest(msg("2", "b", sender = "Atharva Rao"))
        store.ingest(msg("3", "c", sender = "Priya Shah"))
        assertEquals(listOf("2"), store.sendersMatching("atharva").map { it.id })
        assertEquals(1, store.sendersMatching("Priya").size)
        assertTrue(store.sendersMatching("nobody").isEmpty())
        assertTrue(store.sendersMatching("  ").isEmpty())
    }

    @Test fun `untrusted text never prints itself`() {
        assertFalse(msg("1", "secret words").toString().contains("secret words"))
    }

    @Test fun `memory stores trimmed unique facts and survives reload`() {
        var saved = listOf<String>()
        val storage = object : MutableFactStorage { override fun load() = saved; override fun save(facts: List<String>) { saved = facts.toList() } }
        val m = MemoryStore(storage)
        assertTrue(m.remember("  my brother is Rahul "))
        assertFalse(m.remember("MY BROTHER IS RAHUL"))
        assertFalse(m.remember("   "))
        assertEquals(listOf("my brother is Rahul"), MemoryStore(storage).all())
        m.forgetAll()
        assertTrue(MemoryStore(storage).all().isEmpty())
    }

    @Test fun `memory caps the number and length of facts`() {
        val storage = object : MutableFactStorage { override fun load() = emptyList<String>(); override fun save(facts: List<String>) {} }
        val m = MemoryStore(storage, maxFacts = 2, maxLength = 5)
        m.remember("abcdefgh"); m.remember("two"); m.remember("three")
        assertEquals(listOf("two", "three"), m.all())
    }

    @Test fun `new actions carry the right tiers and fingerprints`() {
        val c = ContactBook(listOf(Triple("1", "Mom", "+9111")), mapOf("1" to "mom@example.com")).resolve("Mom")
        val mom = (c as Resolution.Unique).contact
        assertEquals("mom@example.com", mom.email)
        assertEquals(Tier.T1_REVERSIBLE, SetAlarm(7, 30, "gym").tier)
        assertEquals(Tier.T2_OUTBOUND, DialContact(mom).tier)
        assertEquals(Tier.T2_OUTBOUND, ComposeEmail(mom, "s", "b").tier)
        assertEquals(Tier.T2_OUTBOUND, ReplyToMessage(ConversationRef("c", "com.whatsapp", "Mom"), "ok").tier)
        assertTrue(PolicyContext.DEFAULT_FORBIDDEN.none { it == "com.whatsapp" })
    }
}
