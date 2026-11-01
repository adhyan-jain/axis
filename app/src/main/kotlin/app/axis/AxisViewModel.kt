package app.axis

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.axis.device.AndroidActionRunner
import app.axis.device.AndroidCalendarSource
import app.axis.device.AxisNotificationListener
import app.axis.device.AxisServices
import app.axis.device.CallReceiver
import app.axis.device.CrashLog
import app.axis.device.ContactsReader
import app.axis.device.EncryptedFactStorage
import app.axis.device.Voice
import app.axis.vault.KeyVault
import axis.agent.ApprovalMethod
import axis.agent.ChatController
import axis.agent.ChatState
import axis.agent.ContactBook
import axis.agent.MemoryStore
import axis.agent.PlanOutcome
import axis.agent.Planner
import axis.core.Sensitive
import axis.agent.GitHubClient
import axis.llm.CallResult
import axis.llm.KeyEntry
import axis.llm.KeyHealth
import axis.llm.LlmRequest
import axis.llm.KeyStatus
import axis.llm.MultiProviderBackend
import axis.llm.Provider
import axis.llm.ProviderPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class KeyRow(val id: String, val provider: Provider, val health: KeyHealth)

data class Access(val contacts: Boolean = false, val notifications: Boolean = false, val githubToken: Boolean = false)

class AxisViewModel(private val app: Application) : AndroidViewModel(app) {
    private val vault = KeyVault(app)
    private val keyguard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val memory = MemoryStore(EncryptedFactStorage(vault))
    private val voice = Voice(app)

    private var contacts = ContactBook(emptyList())
    private var contactsLoaded = false
    private var pool: ProviderPool? = null
    private var planner: Planner? = null

    private val _keys = MutableStateFlow<List<KeyRow>>(emptyList())
    val keys: StateFlow<List<KeyRow>> = _keys.asStateFlow()

    private val _access = MutableStateFlow(Access())
    val access: StateFlow<Access> = _access.asStateFlow()

    private val _speakReplies = MutableStateFlow(false)
    val speakReplies: StateFlow<Boolean> = _speakReplies.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val settings = app.getSharedPreferences(CallReceiver.PREFS, Context.MODE_PRIVATE)
    private val _announceCalls = MutableStateFlow(settings.getBoolean(CallReceiver.KEY_ANNOUNCE, false))
    val announceCalls: StateFlow<Boolean> = _announceCalls.asStateFlow()

    private val _crash = MutableStateFlow(CrashLog.read(app))
    val crashReport: StateFlow<String?> = _crash.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // The controller outlives key and contact changes; it asks for the current planner on every request.
    private val controller = ChatController(
        planner = { request -> planner?.plan(request) ?: PlanOutcome.Unavailable("No working AI key. Add one in Keys.") },
        runner = AndroidActionRunner(app),
        isDeviceLocked = { keyguard.isKeyguardLocked },
        memory = memory,
    )
    val chat: StateFlow<ChatState> = controller.state

    init {
        rebuild(); refreshAccess()
        // After the app was force-stopped or killed, Android does not reconnect the notification listener by itself.
        if (_access.value.notifications) {
            NotificationListenerService.requestRebind(ComponentName(app, AxisNotificationListener::class.java))
        }
    }

    /** Rebuilds the provider pool and planner from stored keys. Called when keys, contacts or the GitHub token change. */
    private fun rebuild() {
        val entries = vault.list()
        val newPool = ProviderPool(entries, MultiProviderBackend.standard(), System::currentTimeMillis)
        pool = newPool
        val github = vault.getSecret(GITHUB)?.let { GitHubClient(Sensitive(it)) }
        planner = if (entries.isEmpty()) null
        else Planner(newPool, contacts, AxisServices.messages, memory, github, AndroidCalendarSource(app))
        refreshKeyRows()
    }

    private fun refreshKeyRows() {
        val status: Map<String, KeyStatus> = pool?.status().orEmpty().associateBy { it.id }
        _keys.value = vault.list().map { KeyRow(it.id, it.provider, status[it.id]?.health ?: KeyHealth.OK) }
    }

    /** Call when the screen returns to the foreground: the user may have changed a setting in Android. */
    fun refreshAccess() {
        _access.value = Access(
            contacts = contactsLoaded,
            notifications = NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName),
            githubToken = vault.getSecret(GITHUB) != null,
        )
    }

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /**
     * Checks a key with one tiny request before saving it, so a mistyped key is caught now instead of later.
     * A rate-limited or unreachable provider still means the key is probably fine, so only a rejection blocks it.
     */
    fun addKey(provider: Provider, raw: String, onDone: (String?) -> Unit) {
        val key = raw.trim()
        KeyVault.validate(key)?.let { onDone(it); return }
        // Say so at once instead of spending a live request on a key that is already saved.
        if (vault.list().any { it.key.reveal() == key }) { onDone("That key is already added."); return }
        viewModelScope.launch {
            _checking.value = true
            // A slow network must not leave the user staring at "Checking key": after 15 s, save it unverified.
            val verdict = withTimeoutOrNull(KEY_CHECK_TIMEOUT_MS) {
                MultiProviderBackend.standard().generate(KeyEntry("check", provider, Sensitive(key)), LlmRequest("Reply with the single word: ok"))
            }
            _checking.value = false
            if (verdict == CallResult.AuthFailed) {
                onDone("${provider.name.lowercase().replaceFirstChar { it.uppercase() }} rejected this key. Check that you copied all of it.")
            } else {
                onDone(vault.add(provider, key).also { if (it == null) rebuild() })
            }
        }
    }
    fun removeKey(id: String) { vault.remove(id); rebuild() }

    fun setGitHubToken(raw: String): String? {
        val token = raw.trim()
        if (token.length < 20 || token.any { it.isWhitespace() }) return "That doesn't look like a GitHub token."
        vault.putSecret(GITHUB, token); rebuild(); refreshAccess(); return null
    }
    fun removeGitHubToken() { vault.deleteSecret(GITHUB); rebuild(); refreshAccess() }

    fun loadContacts() {
        contacts = ContactsReader.load(app.contentResolver)
        contactsLoaded = true
        rebuild(); refreshAccess()
    }

    fun send(text: String) { viewModelScope.launch { controller.submit(text); refreshKeyRows(); speakLast() } }

    fun listen() {
        if (!voice.available) { _notice.value = "This phone has no speech recognition service."; return }
        _listening.value = true
        voice.listen(
            onText = { _listening.value = false; send(it) },
            onFailure = { _listening.value = false; _notice.value = it },
        )
    }

    fun setAnnounceCalls(on: Boolean) {
        settings.edit().putBoolean(CallReceiver.KEY_ANNOUNCE, on).apply()
        _announceCalls.value = on
    }
    fun clearCrashReport() { CrashLog.clear(app); _crash.value = null }

    fun setSpeakReplies(on: Boolean) { _speakReplies.value = on }
    fun clearNotice() { _notice.value = null }
    fun approve() = controller.approve(ApprovalMethod.TAP)
    fun cancel() = controller.cancel()

    /** Forgets remembered facts and received messages. */
    fun forgetEverything() { memory.forgetAll(); AxisServices.clear() }

    private fun speakLast() {
        if (!_speakReplies.value) return
        controller.state.value.lines.lastOrNull()?.takeIf { it.author == axis.agent.Author.ASSISTANT }?.let { voice.speak(it.text) }
    }

    override fun onCleared() { voice.shutdown() }

    private companion object {
        const val GITHUB = "github_token"
        const val KEY_CHECK_TIMEOUT_MS = 15_000L
    }
}
