package axis.agent

import java.security.MessageDigest

enum class Tier { T0_READ, T1_REVERSIBLE, T2_OUTBOUND, T3_IRREVERSIBLE }

/** Where the request came from. Only the first three are the user's own words or rules. */
enum class Trigger { USER_VOICE, USER_TEXT, USER_RECIPE, UNTRUSTED_CONTENT }

/** A person the user has in their contacts. Can only be produced by [ContactBook.resolve], never from model text. */
class ResolvedContact internal constructor(val id: String, val displayName: String, val number: String, val email: String? = null)

sealed interface Action {
    val tier: Tier
    /** Package the action reads from or acts on, checked against the forbidden list. */
    val targetPackage: String? get() = null
    /** Exactly what the user is shown and what the approval is bound to. */
    fun canonical(): String
}

data class ReadNotifications(val sourcePackage: String) : Action {
    override val tier = Tier.T0_READ
    override val targetPackage get() = sourcePackage
    override fun canonical() = "read-notifications|$sourcePackage"
}

data class SetTimer(val seconds: Int) : Action {
    override val tier = Tier.T1_REVERSIBLE
    override fun canonical() = "set-timer|$seconds"
}

data class OpenApp(val packageName: String) : Action {
    override val tier = Tier.T1_REVERSIBLE
    override val targetPackage get() = packageName
    override fun canonical() = "open-app|$packageName"
}

data class SendMessage(
    val appPackage: String,
    val recipient: ResolvedContact,
    val body: String,
) : Action {
    override val tier = Tier.T2_OUTBOUND
    override val targetPackage get() = appPackage
    override fun canonical() = "send-message|$appPackage|${recipient.id}|${recipient.number}|$body"
}

data class InstallApk(val packageName: String, val sha256: String, val sourceHost: String) : Action {
    override val tier = Tier.T3_IRREVERSIBLE
    override fun canonical() = "install-apk|$packageName|$sha256|$sourceHost"
}

data class MergePullRequest(val repo: String, val number: Int) : Action {
    override val tier = Tier.T3_IRREVERSIBLE
    override fun canonical() = "merge-pr|$repo|$number"
}

data class DeleteData(val description: String) : Action {
    override val tier = Tier.T3_IRREVERSIBLE
    override fun canonical() = "delete-data|$description"
}

data class SetAlarm(val hour: Int, val minute: Int, val label: String) : Action {
    override val tier = Tier.T1_REVERSIBLE
    override fun canonical() = "set-alarm|$hour|$minute|$label"
}

/** Opens the dialer with the number filled in. The user still presses call. */
data class DialContact(val recipient: ResolvedContact) : Action {
    override val tier = Tier.T2_OUTBOUND
    override fun canonical() = "dial|${recipient.id}|${recipient.number}"
}

/** Opens a draft in the mail app. The user still presses send. */
data class ComposeEmail(val recipient: ResolvedContact, val subject: String, val body: String) : Action {
    override val tier = Tier.T2_OUTBOUND
    override val targetPackage get() = "com.google.android.gm"
    override fun canonical() = "compose-email|${recipient.id}|${recipient.email}|$subject|$body"
}

data class Remember(val fact: String) : Action {
    override val tier = Tier.T1_REVERSIBLE
    override fun canonical() = "remember|$fact"
}

/** A chat thread the user received a message in, identified by trusted notification metadata. */
data class ConversationRef(val id: String, val app: String, val senderName: String)

data class ReplyToMessage(val conversation: ConversationRef, val body: String) : Action {
    override val tier = Tier.T2_OUTBOUND
    override val targetPackage get() = conversation.app
    override fun canonical() = "reply|${conversation.app}|${conversation.id}|${conversation.senderName}|$body"
}

fun Action.fingerprint(): String =
    MessageDigest.getInstance("SHA-256").digest(canonical().toByteArray()).joinToString("") { "%02x".format(it) }

sealed interface Resolution {
    data class Unique(val contact: ResolvedContact) : Resolution
    data class Ambiguous(val candidates: List<ResolvedContact>) : Resolution
    data object NotFound : Resolution
}

/** The only place a [ResolvedContact] is created. Lookup is by the user's own spoken or typed name. */
class ContactBook(entries: List<Triple<String, String, String>>, emails: Map<String, String> = emptyMap()) {
    private val contacts = entries.map { (id, name, number) -> ResolvedContact(id, name, number, emails[id]) }

    fun resolve(spokenName: String): Resolution {
        val q = spokenName.trim().lowercase()
        if (q.isEmpty()) return Resolution.NotFound
        val exact = contacts.filter { it.displayName.lowercase() == q }
        val hits = exact.ifEmpty { contacts.filter { c -> c.displayName.lowercase().split(' ').any { it == q } } }
        return when (hits.size) {
            0 -> Resolution.NotFound
            1 -> Resolution.Unique(hits.single())
            else -> Resolution.Ambiguous(hits)
        }
    }
}
