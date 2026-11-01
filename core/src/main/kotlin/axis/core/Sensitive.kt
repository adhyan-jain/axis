package axis.core

/** Wraps a secret so it cannot leak through toString, string templates, or logging. */
class Sensitive<T>(private val value: T) {
    fun reveal(): T = value
    override fun toString(): String = "Sensitive(***)"
}
