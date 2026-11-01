package axis.llm

/**
 * Tries each model in turn for one key. Quotas are per model and models get overloaded or retired,
 * so a key only counts as rate limited once every model is, and reports the soonest time one frees up.
 */
internal suspend fun runLadder(models: List<String>, call: suspend (String) -> CallResult): CallResult {
    var rateLimited: CallResult.RateLimited? = null
    for (model in models) {
        when (val r = call(model)) {
            is CallResult.Ok, is CallResult.Blocked, CallResult.AuthFailed -> return r
            is CallResult.RateLimited -> rateLimited = soonest(rateLimited, r)
            CallResult.Transient -> Unit
        }
    }
    return rateLimited ?: CallResult.Transient
}

private fun soonest(a: CallResult.RateLimited?, b: CallResult.RateLimited): CallResult.RateLimited {
    if (a == null) return b
    if (a.retryAfterMs == null || b.retryAfterMs == null) return CallResult.RateLimited(null)
    return CallResult.RateLimited(minOf(a.retryAfterMs, b.retryAfterMs))
}
