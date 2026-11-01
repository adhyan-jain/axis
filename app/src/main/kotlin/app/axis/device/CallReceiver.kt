package app.axis.device

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Says who is calling, when the user turned that on. Off by default because it speaks aloud.
 * Only the Android system can send PHONE_STATE, and the action is checked again here.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (intent.getStringExtra(TelephonyManager.EXTRA_STATE) != TelephonyManager.EXTRA_STATE_RINGING) return
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ANNOUNCE, false)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return

        // Android sends RINGING twice; the first often has no number yet. Wait for the one that does.
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)?.takeIf { it.isNotBlank() } ?: return
        val now = System.currentTimeMillis()
        synchronized(CallReceiver) {
            if (number == lastNumber && now - lastAt < DEDUPE_MS) return
            lastNumber = number; lastAt = now
        }

        val name = lookup(context, number)
        Log.d(TAG, if (name != null) "announcing a known caller" else "announcing an unknown caller")
        speak(context, if (name != null) "$name is calling." else "Unknown number is calling.")
    }

    private fun lookup(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun speak(context: Context, text: String) {
        val pending = goAsync()
        var engine: TextToSpeech? = null
        fun finish() { engine?.shutdown(); engine = null; pending.finish() }
        engine = TextToSpeech(context.applicationContext) { status ->
            val e = engine
            if (status != TextToSpeech.SUCCESS || e == null) return@TextToSpeech finish()
            e.language = Locale.getDefault()
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish()
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finish()
            })
            e.speak(text, TextToSpeech.QUEUE_FLUSH, null, "caller")
        }
    }

    companion object {
        const val PREFS = "axis_settings"
        const val KEY_ANNOUNCE = "announce_calls"
        private const val TAG = "AxisCall"
        private const val DEDUPE_MS = 8_000L
        private var lastNumber: String? = null
        private var lastAt = 0L
    }
}
