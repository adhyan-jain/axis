package app.axis.device

import android.app.Notification
import axis.agent.MessageStore
import java.util.concurrent.ConcurrentHashMap

/** Process-wide state shared by the notification listener and the UI. Messages live in memory only. */
object AxisServices {
    val messages = MessageStore(clock = System::currentTimeMillis)

    /** Reply buttons from live notifications, so a confirmed reply can be sent without automating any UI. */
    private val replies = object : LinkedHashMap<String, Notification.Action>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Notification.Action>?) = size > 200
    }

    @Synchronized fun putReply(id: String, action: Notification.Action) { replies[id] = action }
    @Synchronized fun replyFor(id: String): Notification.Action? = replies[id]
    @Synchronized fun clear() { replies.clear(); messages.clear() }
}
