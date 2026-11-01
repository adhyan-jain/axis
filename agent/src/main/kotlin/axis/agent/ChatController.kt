package axis.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface RunResult {
    data class Done(val message: String) : RunResult
    data class Failed(val message: String) : RunResult
}

/** Performs an authorized action on the device. The Android layer implements this. */
fun interface ActionRunner {
    fun run(action: Action): RunResult
}

enum class Author { USER, ASSISTANT }

data class ChatLine(val author: Author, val text: String)

/** An action waiting for the user. The UI renders [shown] from trusted code, never from model text. */
data class Pending(val action: Action, val requirement: Decision.Require) {
    val shown: String get() = requirement.shown
    val method: ApprovalMethod get() = requirement.method
}

data class ChatState(
    val lines: List<ChatLine> = emptyList(),
    val pending: Pending? = null,
    val busy: Boolean = false,
)

/**
 * Drives one conversation: plan, check policy, ask for approval when required, then run.
 * Holds no Android types, so the whole flow is unit-testable.
 */
class ChatController(
    private val planner: PlanSource,
    private val runner: ActionRunner,
    private val isDeviceLocked: () -> Boolean,
    private val confirmations: ConfirmationService = ConfirmationService(),
    private val memory: MemoryStore? = null,
) {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private fun context() = PolicyContext(Trigger.USER_TEXT, deviceLocked = isDeviceLocked())
    private fun say(text: String) = _state.update { it.copy(lines = it.lines + ChatLine(Author.ASSISTANT, text)) }

    suspend fun submit(text: String) {
        val request = text.trim()
        if (request.isEmpty() || _state.value.busy) return
        // A new request replaces anything still waiting for approval.
        _state.update { it.copy(lines = it.lines + ChatLine(Author.USER, request), pending = null, busy = true) }
        try {
            when (val outcome = planner.plan(request)) {
                is PlanOutcome.Say -> say(outcome.text)
                is PlanOutcome.Clarify -> say("${outcome.question} ${outcome.choices.joinToString(" or ")}?")
                is PlanOutcome.Unavailable -> say(outcome.reason)
                is PlanOutcome.Proposed -> handle(outcome.action)
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun handle(action: Action) {
        when (val decision = PolicyEngine.evaluate(action, context())) {
            Decision.Allow -> execute(action, approval = null)
            is Decision.Require -> {
                _state.update { it.copy(pending = Pending(action, decision)) }
                say("Check the details and confirm.")
            }
            is Decision.Deny -> say(denyMessage(decision.reason))
        }
    }

    /** Called by the trusted confirmation UI after the user taps or authenticates. */
    fun approve(method: ApprovalMethod) {
        val pending = _state.value.pending ?: return
        val approval = confirmations.approve(pending.requirement, method)
        if (approval == null) { say("That needs a stronger confirmation."); return }
        _state.update { it.copy(pending = null) }
        execute(pending.action, approval)
    }

    fun cancel() {
        if (_state.value.pending == null) return
        _state.update { it.copy(pending = null) }
        say("Cancelled. Nothing was done.")
    }

    private fun execute(action: Action, approval: Approval?) {
        when (val auth = ActionGate.authorize(action, context(), approval)) {
            Authorization.Authorized ->
                if (action is Remember) say(if (memory?.remember(action.fact) == true) "Okay, I'll remember that." else "I already know that.")
                else when (val r = runner.run(action)) {
                    is RunResult.Done -> say(r.message)
                    is RunResult.Failed -> say(r.message)
                }
            is Authorization.Rejected -> say("Blocked: ${auth.reason}.")
        }
    }

    private fun denyMessage(reason: DenyReason) = when (reason) {
        DenyReason.FORBIDDEN_APP -> "Axis doesn't act inside banking, wallet or password apps."
        DenyReason.IRREVERSIBLE_FROM_UNTRUSTED -> "That can only be started by you directly."
        DenyReason.DEVICE_LOCKED -> "Unlock your phone first."
        DenyReason.ALL_TRIGGERS_BLOCKED -> "That isn't allowed."
    }
}
