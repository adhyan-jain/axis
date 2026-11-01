package axis.llm

import axis.core.Sensitive

enum class Provider { GEMINI, GROQ, OPENAI, CLAUDE }

enum class CostClass { FREE, PAID }

data class KeyEntry(
    val id: String,
    val provider: Provider,
    val key: Sensitive<String>,
    val costClass: CostClass = CostClass.FREE,
    /** Lower runs first among keys of the same cost class. */
    val order: Int = 0,
)

data class LlmRequest(val prompt: String)

data class LlmResponse(val text: String, val keyId: String)

sealed interface CallResult {
    data class Ok(val text: String) : CallResult
    data class RateLimited(val retryAfterMs: Long? = null) : CallResult
    /** Key rejected (401/403). Never retried. */
    data object AuthFailed : CallResult
    /** 5xx, timeout, or network error. Falls through without penalty. */
    data object Transient : CallResult
    /** The provider refused on safety grounds. Surfaced to the user; never shopped to another provider. */
    data class Blocked(val reason: String) : CallResult
}

/** One provider call. Adapters for Gemini, Groq, OpenAI and Claude implement this. */
fun interface LlmBackend {
    suspend fun generate(key: KeyEntry, request: LlmRequest): CallResult
}

sealed interface PoolResult {
    data class Success(val response: LlmResponse) : PoolResult
    /** Every usable key is cooling down; the earliest one frees up at [retryAtMs]. */
    data class Exhausted(val retryAtMs: Long) : PoolResult
    data class Blocked(val reason: String) : PoolResult
    /** No key is usable at all: none configured, all invalid, or paid keys are disallowed. */
    data object NoUsableKeys : PoolResult
}
