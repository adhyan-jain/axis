package axis.core

/** Text with sensitive spans replaced by stable placeholders, plus the map to restore them on-device. */
class Redacted(val text: String, private val mapping: Map<String, String>) {
    /** Puts the original values back, for showing the user a reply that mentions a placeholder. */
    fun restore(modelOutput: String): String =
        mapping.entries.fold(modelOutput) { acc, (placeholder, original) -> acc.replace(placeholder, original) }

    override fun toString() = "Redacted(${mapping.size} spans)"
}

/** Removes personal identifiers before text leaves the phone. Deterministic, regex-based, no model involved. */
object Redactor {
    private class Rule(val label: String, val regex: Regex)

    // Order matters: longer, more specific patterns run before looser ones.
    private val rules = listOf(
        Rule("EMAIL", Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")),
        Rule("CARD", Regex("""\b(?:\d[ -]?){13,19}\b""")),
        Rule("ID", Regex("""\b\d{4}[ -]?\d{4}[ -]?\d{4}\b""")),
        Rule("PHONE", Regex("""(?<![\w])\+?\d[\d\s().-]{7,}\d(?![\w])""")),
        Rule("CODE", Regex("""(?i)(?<=\b(?:otp|code|pin|passcode|verification)\b[^\d]{0,15})\d{4,8}\b""")),
    )

    fun redact(input: String): Redacted {
        val mapping = linkedMapOf<String, String>()
        val counters = mutableMapOf<String, Int>()
        var text = input
        for (rule in rules) {
            text = rule.regex.replace(text) { m ->
                val existing = mapping.entries.firstOrNull { it.value == m.value }?.key
                existing ?: run {
                    val n = counters.merge(rule.label, 1, Int::plus)!!
                    "[${rule.label}_$n]".also { mapping[it] = m.value }
                }
            }
        }
        return Redacted(text, mapping)
    }
}
