package app.axis.device

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import axis.agent.IncomingMessage
import axis.core.Untrusted

/**
 * Reads message notifications from the apps the user allows (WhatsApp, Instagram). Sender and app come from
 * notification metadata; the text is stored as [Untrusted] and never treated as an instruction.
 * OTPs and bank alerts are dropped by the store before anything is kept.
 */
class AxisNotificationListener : NotificationListenerService() {

    /** Android can drop a listener (battery savers, updates). Ask to be reconnected so message access doesn't silently stop. */
    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, AxisNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }
        val id = "${sbn.key}#${sbn.postTime}"
        val stored = AxisServices.messages.ingest(
            IncomingMessage(id, sbn.packageName, sender, Untrusted(text), sbn.postTime, canReply = reply != null),
        )
        if (stored && reply != null) AxisServices.putReply(id, reply)
    }
}
