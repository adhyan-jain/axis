package fake.wa

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log

private const val TAG = "FakeWA"

class ViewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "VIEW ${intent.data}")
        finish()
    }
}

class PostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("m", "Messages", NotificationManager.IMPORTANCE_DEFAULT))
        val sender = intent.getStringExtra("sender") ?: "Someone"
        val text = intent.getStringExtra("text") ?: ""
        val id = intent.getIntExtra("id", sender.hashCode())
        val input = RemoteInput.Builder("reply").setLabel("Reply").build()
        val replyIntent = Intent(context, ReplyReceiver::class.java).putExtra("sender", sender)
        val pending = PendingIntent.getBroadcast(context, id, replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val action = Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", pending)
            .addRemoteInput(input).build()
        nm.notify(id, Notification.Builder(context, "m").setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(sender).setContentText(text).addAction(action).build())
        Log.i(TAG, "POSTED from $sender")
    }
}

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")
        Log.i(TAG, "REPLY to ${intent.getStringExtra("sender")}: $text")
    }
}
