package axis.agent

import axis.core.Untrusted

/** One received message. Sender and app come from notification metadata; the text is someone else's words. */
data class IncomingMessage(
    val id: String,
    val app: String,
    val sender: String,
    val text: Untrusted<String>,
    val atMs: Long,
    val canReply: Boolean,
)

/** OTPs, bank alerts and similar are never stored, summarized or sent anywhere. */
object SensitiveMessages {
    private val patterns = listOf(
        Regex("""(?i)\b(otp|one[- ]time (password|pin)|verification code|passcode|cvv|security code)\b"""),
        Regex("""(?i)\b(debited|credited|a/c|account (no|number)|upi|txn|transaction (id|of)|available balance)\b"""),
        Regex("""(?i)\b(rs\.?|inr|₹)\s?\d[\d,]*"""),
    )
    fun looksSensitive(text: String) = patterns.any { it.containsMatchIn(text) }
}

/** Short-lived, in-memory inbox of allowed apps. Nothing here is written to disk. */
class MessageStore(
    private val clock: () -> Long,
    private val allowedApps: Set<String> = setOf("com.whatsapp", "com.instagram.android"),
    private val ttlMs: Long = 24 * 60 * 60 * 1000,
    private val maxItems: Int = 200,
) {
    private val items = ArrayDeque<IncomingMessage>()
    private val lock = Any()

    /** Returns false when the message was not stored (app not allowed, or it looks sensitive). */
    fun ingest(message: IncomingMessage): Boolean = synchronized(lock) {
        if (message.app !in allowedApps) return false
        if (SensitiveMessages.looksSensitive(message.text.reveal())) return false
        if (items.any { it.id == message.id }) return true
        items.addLast(message)
        while (items.size > maxItems) items.removeFirst()
        true
    }

    fun recent(app: String? = null, limit: Int = 20): List<IncomingMessage> = synchronized(lock) {
        prune()
        items.filter { app == null || it.app == app }.takeLast(limit)
    }

    /** Distinct senders matching a spoken name, newest message each. */
    fun sendersMatching(name: String): List<IncomingMessage> = synchronized(lock) {
        prune()
        val q = name.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        items.filter { it.canReply && it.sender.lowercase().split(' ', '-').any { part -> part == q } || it.sender.lowercase() == q }
            .groupBy { it.sender }.map { (_, msgs) -> msgs.last() }
    }

    fun clear() = synchronized(lock) { items.clear() }

    private fun prune() {
        val cutoff = clock() - ttlMs
        while (items.isNotEmpty() && items.first().atMs < cutoff) items.removeFirst()
    }
}

/** Facts the user asked Axis to remember. User-authored, so they may go into prompts (after redaction). */
fun interface FactStorage { fun load(): List<String> }

interface MutableFactStorage : FactStorage { fun save(facts: List<String>) }

class MemoryStore(private val storage: MutableFactStorage, private val maxFacts: Int = 100, private val maxLength: Int = 200) {
    private val facts = storage.load().toMutableList()

    fun all(): List<String> = facts.toList()

    /** Returns false if the fact was empty or already known. */
    fun remember(fact: String): Boolean {
        val clean = fact.trim().take(maxLength)
        if (clean.isEmpty() || facts.any { it.equals(clean, ignoreCase = true) }) return false
        facts += clean
        while (facts.size > maxFacts) facts.removeAt(0)
        storage.save(facts)
        return true
    }

    fun forgetAll() { facts.clear(); storage.save(facts) }
}
