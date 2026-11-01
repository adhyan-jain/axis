package app.axis.device

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import axis.agent.Action
import axis.agent.ActionRunner
import axis.agent.ComposeEmail
import axis.agent.DialContact
import axis.agent.OpenApp
import axis.agent.ReplyToMessage
import axis.agent.RunResult
import axis.agent.SendMessage
import axis.agent.SetAlarm
import axis.agent.SetTimer

/**
 * Carries out authorized actions with ordinary Android intents and notification reply buttons.
 * Axis never automates another app's screen: chats open with the text ready and you press send,
 * and replies go through the reply button the messaging app itself put in its notification.
 */
class AndroidActionRunner(private val context: Context) : ActionRunner {

    override fun run(action: Action): RunResult = try {
        when (action) {
            is SetTimer -> {
                start(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, action.seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                RunResult.Done("Timer set for ${describe(action.seconds)}.")
            }
            is SetAlarm -> {
                start(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, action.hour).putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, action.label).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                RunResult.Done("Alarm set for %d:%02d.".format(action.hour, action.minute))
            }
            is OpenApp -> {
                val launch = context.packageManager.getLaunchIntentForPackage(action.packageName)
                if (launch == null) RunResult.Failed("That app isn't installed.") else { start(launch); RunResult.Done("Opening it now.") }
            }
            is DialContact -> {
                start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(action.recipient.number))))
                RunResult.Done("The dialer is open for ${action.recipient.displayName}. Press call when you're ready.")
            }
            is ComposeEmail -> {
                start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(action.recipient.email))
                    .putExtra(Intent.EXTRA_SUBJECT, action.subject).putExtra(Intent.EXTRA_TEXT, action.body))
                RunResult.Done("Your email draft to ${action.recipient.displayName} is open. Press send when it looks right.")
            }
            is SendMessage -> sendMessage(action)
            is ReplyToMessage -> reply(action)
            else -> RunResult.Failed("Axis can't do that on this phone yet.")
        }
    } catch (_: ActivityNotFoundException) {
        RunResult.Failed("No app on this phone could handle that.")
    } catch (_: SecurityException) {
        RunResult.Failed("Android blocked that action.")
    }

    private fun sendMessage(action: SendMessage): RunResult {
        if (action.appPackage != WHATSAPP) return RunResult.Failed("Sending with that app isn't supported yet.")
        val digits = action.recipient.number.filter { it.isDigit() }
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=${Uri.encode(action.body)}")).setPackage(WHATSAPP))
        return RunResult.Done("WhatsApp is open with your message to ${action.recipient.displayName}. Tap send there.")
    }

    private fun reply(action: ReplyToMessage): RunResult {
        val button = AxisServices.replyFor(action.conversation.id) ?: return RunResult.Failed("That chat's reply button is gone. Open the app to answer.")
        val input = button.remoteInputs?.firstOrNull() ?: return RunResult.Failed("That chat can't be answered from here.")
        val filled = Intent()
        android.app.RemoteInput.addResultsToIntent(button.remoteInputs, filled, Bundle().apply { putCharSequence(input.resultKey, action.body) })
        return try {
            button.actionIntent.send(context, 0, filled)
            RunResult.Done("Replied to ${action.conversation.senderName}.")
        } catch (_: PendingIntent.CanceledException) {
            RunResult.Failed("That chat's reply button expired. Open the app to answer.")
        }
    }

    private fun start(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun describe(seconds: Int): String = when {
        seconds % 3600 == 0 -> "${seconds / 3600} hour" + if (seconds / 3600 > 1) "s" else ""
        seconds % 60 == 0 -> "${seconds / 60} minute" + if (seconds / 60 > 1) "s" else ""
        else -> "$seconds seconds"
    }

    companion object { const val WHATSAPP = "com.whatsapp" }
}
