package axis.llm

enum class KeyHealth { OK, COOLING, INVALID }

/** Safe-to-display view of a key. Never carries key material. */
data class KeyStatus(
    val id: String,
    val provider: Provider,
    val costClass: CostClass,
    val health: KeyHealth,
    val cooldownUntilMs: Long?,
)

/**
 * Routes one LLM request across the user's keys and cascades to the next key when one is rate-limited.
 *
 * Only the LLM call is retried here. Callers must never re-run side effects (sends, installs) on a cascade.
 * Free keys run before paid ones; paid keys are used only when [allowPaid] is true.
 */
class ProviderPool(
    keys: List<KeyEntry>,
    private val backend: LlmBackend,
    private val clock: () -> Long,
    private val allowPaid: Boolean = false,
    private val baseBackoffMs: Long = 30_000,
    private val maxBackoffMs: Long = 600_000,
    private val transientRetryMs: Long = 5_000,
) {
    private class State {
        var invalid = false
        var cooldownUntil = 0L
        var strikes = 0
    }

    private val ordered = keys.sortedWith(compareBy({ it.costClass.ordinal }, { it.order }))
    private val states = ordered.associate { it.id to State() }
    private val lock = Any()

    private fun usable(key: KeyEntry) = allowPaid || key.costClass == CostClass.FREE

    suspend fun generate(request: LlmRequest): PoolResult {
        for (key in ordered) {
            if (!usable(key) || !tryAcquire(key)) continue
            when (val result = backend.generate(key, request)) {
                is CallResult.Ok -> {
                    synchronized(lock) { states.getValue(key.id).strikes = 0 }
                    return PoolResult.Success(LlmResponse(result.text, key.id))
                }
                is CallResult.RateLimited -> coolDown(key, result.retryAfterMs)
                CallResult.AuthFailed -> synchronized(lock) { states.getValue(key.id).invalid = true }
                CallResult.Transient -> Unit
                is CallResult.Blocked -> return PoolResult.Blocked(result.reason)
            }
        }
        return exhaustedOrNone()
    }

    fun status(): List<KeyStatus> = synchronized(lock) {
        val now = clock()
        ordered.map { key ->
            val s = states.getValue(key.id)
            val health = when {
                s.invalid -> KeyHealth.INVALID
                s.cooldownUntil > now -> KeyHealth.COOLING
                else -> KeyHealth.OK
            }
            KeyStatus(key.id, key.provider, key.costClass, health, s.cooldownUntil.takeIf { health == KeyHealth.COOLING })
        }
    }

    private fun tryAcquire(key: KeyEntry): Boolean = synchronized(lock) {
        val s = states.getValue(key.id)
        !s.invalid && s.cooldownUntil <= clock()
    }

    private fun coolDown(key: KeyEntry, retryAfterMs: Long?) = synchronized(lock) {
        val s = states.getValue(key.id)
        s.strikes++
        val backoff = minOf(baseBackoffMs shl (s.strikes - 1).coerceAtMost(20), maxBackoffMs)
        s.cooldownUntil = clock() + (retryAfterMs ?: backoff)
    }

    private fun exhaustedOrNone(): PoolResult = synchronized(lock) {
        val now = clock()
        val live = ordered.filter { usable(it) && !states.getValue(it.id).invalid }
        if (live.isEmpty()) return PoolResult.NoUsableKeys
        val earliest = live.map { states.getValue(it.id).cooldownUntil }.filter { it > now }.minOrNull()
        PoolResult.Exhausted(earliest ?: (now + transientRetryMs))
    }
}
