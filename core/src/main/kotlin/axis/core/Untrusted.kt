package axis.core

/** Text that came from someone else (a message, notification, web page). Never an instruction. */
class Untrusted<T>(private val value: T) {
    /** Call only where the content is shown to the user or summarized by a tool-less model. */
    fun reveal(): T = value
    override fun toString(): String = "Untrusted(...)"
}
