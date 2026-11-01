package axis.agent

import axis.core.Redactor
import axis.llm.LlmRequest
import axis.llm.PoolResult
import axis.llm.ProviderPool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface PlanOutcome {
    /** A concrete action. Still has to pass [PolicyEngine] and, if required, user approval. */
    data class Proposed(val action: Action) : PlanOutcome
    /** The assistant just answers in words. */
    data class Say(val text: String) : PlanOutcome
    data class Clarify(val question: String, val choices: List<String>) : PlanOutcome
    data class Unavailable(val reason: String, val retryAtMs: Long? = null) : PlanOutcome
}

fun interface PlanSource {
    suspend fun plan(userRequest: String): PlanOutcome
}

/** Apps the assistant knows how to address, by the name a person would say. */
object AppCatalog {
    private val apps = mapOf(
        "whatsapp" to "com.whatsapp", "instagram" to "com.instagram.android", "gmail" to "com.google.android.gm",
        "chrome" to "com.android.chrome", "maps" to "com.google.android.apps.maps", "github" to "com.github.android",
    )
    fun packageFor(name: String): String? = apps[name.trim().lowercase()]
}

/**
 * Turns the user's own words into a proposed [Action].
 * The model only ever sees the user's request and facts the user asked to remember, with personal identifiers
 * redacted. What it returns is a suggestion: recipients come from the user's contacts or from real incoming
 * conversations, never from model text. Message contents are summarized by a separate, tool-less [Summarizer].
 */
class Planner(
    private val pool: ProviderPool,
    private val contacts: ContactBook,
    private val messages: MessageStore? = null,
    private val memory: MemoryStore? = null,
    private val deployments: DeploymentSource? = null,
    private val calendar: CalendarSource? = null,
) : PlanSource {
    private val summarizer = Summarizer(pool)

    override suspend fun plan(userRequest: String): PlanOutcome {
        val redacted = Redactor.redact(userRequest)
        val facts = memory?.all().orEmpty().joinToString("\n") { "- " + Redactor.redact(it).text }
        val prompt = PROMPT + (if (facts.isNotEmpty()) "Things the user asked you to remember:\n$facts\n" else "") + "User request: " + redacted.text
        val text = when (val result = pool.generate(LlmRequest(prompt))) {
            is PoolResult.Success -> result.response.text
            is PoolResult.Exhausted -> return PlanOutcome.Unavailable("All AI keys are busy. Try again shortly.", result.retryAtMs)
            PoolResult.NoUsableKeys -> return PlanOutcome.Unavailable("No working AI key. Add one in Keys.")
            is PoolResult.Blocked -> return PlanOutcome.Unavailable("The AI provider declined this request.")
        }
        val json = parse(text) ?: return PlanOutcome.Say("I couldn't work out what to do. Try rephrasing.")
        return resolve(json) { redacted.restore(it) }
    }

    private suspend fun resolve(json: JsonObject, restore: (String) -> String): PlanOutcome {
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull?.let(restore)
        return when (json["action"]?.jsonPrimitive?.contentOrNull) {
            "say" -> PlanOutcome.Say(str("text") ?: "")
            "set_timer" -> {
                val seconds = json["seconds"]?.jsonPrimitive?.intOrNull
                if (seconds == null || seconds !in 1..86_400) PlanOutcome.Say("I need a timer length between 1 second and 24 hours.")
                else PlanOutcome.Proposed(SetTimer(seconds))
            }
            "set_alarm" -> {
                val h = json["hour"]?.jsonPrimitive?.intOrNull; val m = json["minute"]?.jsonPrimitive?.intOrNull ?: 0
                if (h == null || h !in 0..23 || m !in 0..59) PlanOutcome.Say("I need an alarm time like 7:30.")
                else PlanOutcome.Proposed(SetAlarm(h, m, (str("label") ?: "").take(60)))
            }
            "open_app" -> AppCatalog.packageFor(str("app").orEmpty())
                ?.let { PlanOutcome.Proposed(OpenApp(it)) } ?: PlanOutcome.Say("I don't know how to open that app yet.")
            "send_message" -> sendMessage(str("app"), str("recipient_name").orEmpty(), str("body").orEmpty().trim())
            "call" -> withContact(str("recipient_name").orEmpty()) { PlanOutcome.Proposed(DialContact(it)) }
            "email" -> withContact(str("recipient_name").orEmpty()) { c ->
                if (c.email == null) PlanOutcome.Say("${c.displayName} has no email address in your contacts.")
                else PlanOutcome.Proposed(ComposeEmail(c, (str("subject") ?: "").take(150), (str("body") ?: "").trim()))
            }
            "remember" -> (str("fact") ?: "").trim().let { if (it.isEmpty()) PlanOutcome.Say("What should I remember?") else PlanOutcome.Proposed(Remember(it)) }
            "read_messages" -> readMessages(str("app"))
            "reply" -> reply(str("sender_name").orEmpty(), (str("body") ?: "").trim())
            "briefing" -> briefing()
            "send_deployment_link" -> deploymentLink(str("repo").orEmpty(), str("app"), str("recipient_name").orEmpty())
            else -> PlanOutcome.Say("I can't do that yet.")
        }
    }

    private fun withContact(name: String, then: (ResolvedContact) -> PlanOutcome): PlanOutcome = when (val r = contacts.resolve(name)) {
        is Resolution.Unique -> then(r.contact)
        is Resolution.Ambiguous -> PlanOutcome.Clarify("Which $name?", r.candidates.map { it.displayName })
        Resolution.NotFound -> PlanOutcome.Say("I couldn't find \"$name\" in your contacts.")
    }

    private fun sendMessage(app: String?, name: String, body: String): PlanOutcome {
        val pkg = AppCatalog.packageFor(app ?: "whatsapp")
        return when {
            body.isEmpty() -> PlanOutcome.Say("What should the message say?")
            pkg == null -> PlanOutcome.Say("I can't send messages with that app yet.")
            else -> withContact(name) { PlanOutcome.Proposed(SendMessage(pkg, it, body)) }
        }
    }

    private suspend fun readMessages(app: String?): PlanOutcome {
        val store = messages ?: return PlanOutcome.Say("Message access isn't turned on yet.")
        val pkg = app?.let { AppCatalog.packageFor(it) }
        return PlanOutcome.Say(summarizer.summarize(store.recent(pkg)))
    }

    private fun reply(senderName: String, body: String): PlanOutcome {
        val store = messages ?: return PlanOutcome.Say("Message access isn't turned on yet.")
        if (body.isEmpty()) return PlanOutcome.Say("What should the reply say?")
        val senders = store.sendersMatching(senderName)
        return when (senders.size) {
            0 -> PlanOutcome.Say("I don't see a recent message from \"$senderName\" that I can reply to.")
            1 -> senders.single().let { PlanOutcome.Proposed(ReplyToMessage(ConversationRef(it.id, it.app, it.sender), body)) }
            else -> PlanOutcome.Clarify("Which $senderName?", senders.map { it.sender })
        }
    }

    private suspend fun briefing(): PlanOutcome {
        val events = calendar?.todayEvents().orEmpty()
        val unread = messages?.let { summarizer.summarize(it.recent()) } ?: "Message access isn't turned on yet."
        val agenda = if (events.isEmpty()) "Nothing on your calendar today." else "Today: " + events.joinToString("; ") + "."
        return PlanOutcome.Say("$agenda $unread")
    }

    private suspend fun deploymentLink(repo: String, app: String?, name: String): PlanOutcome {
        val source = deployments ?: return PlanOutcome.Say("Add a GitHub token in Keys to use deployment links.")
        val pkg = AppCatalog.packageFor(app ?: "whatsapp") ?: return PlanOutcome.Say("I can't send messages with that app yet.")
        // Resolve the person first so GitHub isn't called for a recipient who doesn't exist.
        val contact = when (val r = contacts.resolve(name)) {
            is Resolution.Unique -> r.contact
            is Resolution.Ambiguous -> return PlanOutcome.Clarify("Which $name?", r.candidates.map { it.displayName })
            Resolution.NotFound -> return PlanOutcome.Say("I couldn't find \"$name\" in your contacts.")
        }
        return when (val r = source.latestDeploymentUrl(repo)) {
            is DeploymentResult.Found -> PlanOutcome.Proposed(SendMessage(pkg, contact, r.url))
            DeploymentResult.NoDeployment -> PlanOutcome.Say("I couldn't find a deployment link for $repo.")
            DeploymentResult.NotConfigured -> PlanOutcome.Say("Add a GitHub token in Keys to use deployment links.")
            is DeploymentResult.Failed -> PlanOutcome.Say(r.reason)
        }
    }

    /** Accepts bare JSON or JSON wrapped in a code fence or chatter; takes the first object. */
    internal fun parse(text: String): JsonObject? {
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    companion object {
        private const val PROMPT = """You are the planner for a phone assistant. Reply with ONE JSON object and nothing else.
Choose exactly one action:
{"action":"send_message","app":"whatsapp|instagram|gmail","recipient_name":"<name the user said>","body":"<message text>"}
{"action":"reply","sender_name":"<name of someone who messaged the user>","body":"<reply text>"}
{"action":"read_messages","app":"whatsapp|instagram or omit"}
{"action":"briefing"}
{"action":"call","recipient_name":"<name>"}
{"action":"email","recipient_name":"<name>","subject":"<subject>","body":"<text>"}
{"action":"send_deployment_link","repo":"owner/name","app":"whatsapp","recipient_name":"<name>"}
{"action":"set_timer","seconds":<integer>}
{"action":"set_alarm","hour":<0-23>,"minute":<0-59>,"label":"<short label>"}
{"action":"remember","fact":"<something the user wants remembered>"}
{"action":"open_app","app":"<app name>"}
{"action":"say","text":"<short reply>"}
Use the person's name exactly as the user said it, never a phone number. Placeholders like [PHONE_1] stand for hidden values: keep them as written.
"""
    }
}
