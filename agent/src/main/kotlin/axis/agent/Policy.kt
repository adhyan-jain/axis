package axis.agent

enum class ApprovalMethod { TAP, BIOMETRIC }

enum class DenyReason {
    FORBIDDEN_APP, IRREVERSIBLE_FROM_UNTRUSTED, DEVICE_LOCKED, ALL_TRIGGERS_BLOCKED,
}

data class PolicyContext(
    val trigger: Trigger,
    val deviceLocked: Boolean,
    /** Packages Axis must never read or act on (banking, wallets, password managers). */
    val forbiddenPackages: Set<String> = DEFAULT_FORBIDDEN,
) {
    companion object {
        val DEFAULT_FORBIDDEN = setOf(
            "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user",
            "net.one97.paytm", "com.phonepe.app", "com.x8bit.bitwarden", "com.agilebits.onepassword",
            "com.lastpass.lpandroid", "org.keepassdroid",
        )
    }
}

sealed interface Decision {
    data object Allow : Decision
    data class Require(val method: ApprovalMethod, val shown: String, val fingerprint: String) : Decision
    data class Deny(val reason: DenyReason) : Decision
}

/** Deterministic rules, no model involved. Same input always gives the same decision. */
object PolicyEngine {
    fun evaluate(action: Action, ctx: PolicyContext): Decision {
        if (action.targetPackage in ctx.forbiddenPackages) return Decision.Deny(DenyReason.FORBIDDEN_APP)

        val fromUntrusted = ctx.trigger == Trigger.UNTRUSTED_CONTENT
        return when (action.tier) {
            Tier.T0_READ -> Decision.Allow
            Tier.T1_REVERSIBLE ->
                if (fromUntrusted) require(ApprovalMethod.TAP, action) else Decision.Allow
            Tier.T2_OUTBOUND -> when {
                ctx.deviceLocked -> Decision.Deny(DenyReason.DEVICE_LOCKED)
                else -> require(ApprovalMethod.TAP, action)
            }
            Tier.T3_IRREVERSIBLE -> when {
                fromUntrusted -> Decision.Deny(DenyReason.IRREVERSIBLE_FROM_UNTRUSTED)
                ctx.deviceLocked -> Decision.Deny(DenyReason.DEVICE_LOCKED)
                else -> require(ApprovalMethod.BIOMETRIC, action)
            }
        }
    }

    private fun require(method: ApprovalMethod, action: Action) =
        Decision.Require(method, action.canonical(), action.fingerprint())
}

/** Proof the user approved one exact action. Only [ConfirmationService] can mint it. */
class Approval internal constructor(val fingerprint: String, val method: ApprovalMethod)

/** The trusted confirmation UI calls this after the user taps or authenticates. */
class ConfirmationService {
    fun approve(decision: Decision.Require, performed: ApprovalMethod): Approval? {
        val enough = performed == ApprovalMethod.BIOMETRIC || decision.method == ApprovalMethod.TAP
        return if (enough) Approval(decision.fingerprint, performed) else null
    }
}

sealed interface Authorization {
    data object Authorized : Authorization
    data class Rejected(val reason: String) : Authorization
}

/** Last check before an action runs. Re-evaluates policy and binds any approval to the exact action. */
object ActionGate {
    fun authorize(action: Action, ctx: PolicyContext, approval: Approval?): Authorization =
        when (val d = PolicyEngine.evaluate(action, ctx)) {
            Decision.Allow -> Authorization.Authorized
            is Decision.Deny -> Authorization.Rejected("denied: ${d.reason}")
            is Decision.Require -> when {
                approval == null -> Authorization.Rejected("approval required")
                approval.fingerprint != d.fingerprint -> Authorization.Rejected("approval does not match this action")
                approval.method == ApprovalMethod.TAP && d.method == ApprovalMethod.BIOMETRIC ->
                    Authorization.Rejected("biometric approval required")
                else -> Authorization.Authorized
            }
        }
}
