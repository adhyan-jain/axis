package app.axis.device

import android.app.Application
import android.content.Context
import android.os.Build
import axis.core.Redactor
import java.io.File
import java.util.Date

/**
 * Keeps the last crash in a private file so a customer can share it on purpose. Nothing is sent anywhere.
 * Exception messages go through the same redaction as prompts, and no chat, notification or contact text is added.
 */
object CrashLog {
    private const val FILE = "crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { File(app.filesDir, FILE).writeText(report(error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    internal fun report(error: Throwable): String = buildString {
        appendLine("Axis crash report")
        appendLine("time: ${Date()}")
        appendLine("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), device: ${Build.MANUFACTURER} ${Build.MODEL}")
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 4) {
            appendLine("${cause.javaClass.name}: ${Redactor.redact(cause.message.orEmpty()).text}")
            cause.stackTrace.take(30).forEach { appendLine("  at $it") }
            cause = cause.cause
            depth++
        }
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()
    fun clear(context: Context) { File(context.filesDir, FILE).delete() }
}
