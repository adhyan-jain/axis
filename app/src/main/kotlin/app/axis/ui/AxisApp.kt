package app.axis.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.axis.AxisViewModel
import app.axis.KeyRow
import axis.agent.Action
import axis.agent.ApprovalMethod
import axis.agent.Author
import axis.agent.ComposeEmail
import axis.agent.DialContact
import axis.agent.Pending
import axis.agent.ReplyToMessage
import axis.agent.SendMessage
import axis.llm.KeyHealth
import axis.llm.Provider
import kotlinx.coroutines.delay

private val Ink = Color(0xFF101B26)

@Composable
fun AxisApp(vm: AxisViewModel) {
    val keys by vm.keys.collectAsStateWithLifecycle()
    var showSetup by rememberSaveable { mutableStateOf(false) }

    // The user may change a setting in Android and come back; re-read it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refreshAccess() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = showSetup && keys.isNotEmpty()) { showSetup = false }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        when {
            keys.isEmpty() -> SetupScreen(vm, keys, firstRun = true, onBack = {})
            showSetup -> SetupScreen(vm, keys, firstRun = false, onBack = { showSetup = false })
            else -> ChatScreen(vm, onOpenSetup = { showSetup = true })
        }
    }
}

@Composable
private fun ChatScreen(vm: AxisViewModel, onOpenSetup: () -> Unit) {
    val state by vm.chat.collectAsStateWithLifecycle()
    val access by vm.access.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val contactsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.loadContacts() }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.listen() }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) vm.loadContacts()
    }
    LaunchedEffect(state.lines.size) { if (state.lines.isNotEmpty()) listState.animateScrollToItem(state.lines.lastIndex) }

    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Axis", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenSetup) { Text("Setup") }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 4.dp)) }

        if (!access.contacts) SetupCard("Find people by name", "Allow contacts so Axis can match “Atharva” to the right person. Contacts stay on this phone.", "Allow contacts") {
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        if (!access.notifications) SetupCard(
            "Read and answer messages",
            "Turn on notification access so Axis can summarize WhatsApp and Instagram messages and reply for you. Messages stay in memory and are dropped after a day. OTPs and bank alerts are never kept.\n\n" +
                "If the Axis switch is greyed out, Android is blocking apps installed outside the Play Store. Tap “App info”, then the ⋮ menu, then “Allow restricted settings”, and come back.",
            "Open notification access",
            onClick = { context.tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            secondButton = "App info",
            onSecond = {
                context.tryStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.lines.isEmpty()) item {
                Text("Try: “What messages did I miss?”, “Reply to Atharva yes”, “Set an alarm for 7:30”, or “Give me my briefing”.",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(top = 16.dp))
            }
            items(state.lines) { line ->
                val mine = line.author == Author.USER
                Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) { Text(line.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                vm.clearNotice()
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.listen()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }, enabled = !state.busy && !listening) { Text(if (listening) "Listening" else "Talk") }
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                placeholder = { Text("Ask Axis") }, singleLine = true, enabled = !state.busy,
            )
            Button(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && !state.busy) { Text(if (state.busy) "Working" else "Send") }
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            TextButton(onClick = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) }) { Text("Allow calendar for your briefing") }
        }
    }

    state.pending?.let { ConfirmDialog(it, onConfirm = vm::approve, onCancel = vm::cancel) }
}

@Composable
private fun SetupCard(title: String, body: String, button: String, secondButton: String? = null, onSecond: () -> Unit = {}, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body)
            Row {
                TextButton(onClick = onClick) { Text(button) }
                if (secondButton != null) TextButton(onClick = onSecond) { Text(secondButton) }
            }
        }
    }
}

/** What the confirmation shows, built from the typed action and never from model text. */
private data class ConfirmContent(val title: String, val rows: List<Pair<String, String>>, val button: String, val mono: Set<String> = emptySet())

private fun contentFor(action: Action, shown: String): ConfirmContent = when (action) {
    is SendMessage -> ConfirmContent("Send WhatsApp message", listOf("To" to "${action.recipient.displayName}\n${action.recipient.number}", "Message" to action.body), "Send", setOf("Message"))
    is ReplyToMessage -> ConfirmContent("Reply to ${action.conversation.senderName}", listOf("Message" to action.body), "Send reply", setOf("Message"))
    is ComposeEmail -> ConfirmContent("Open email draft", listOf("To" to "${action.recipient.displayName}\n${action.recipient.email}", "Subject" to action.subject, "Message" to action.body), "Open draft", setOf("Message"))
    is DialContact -> ConfirmContent("Open the dialer", listOf("Number" to "${action.recipient.displayName}\n${action.recipient.number}"), "Open dialer")
    else -> ConfirmContent("Confirm action", listOf("Details" to shown), "Confirm", setOf("Details"))
}

/**
 * Confirmation drawn only from the typed action. Cancel is the default, the confirm button is delayed,
 * the window is secure, and it ignores touches while another app draws over it.
 */
@Composable
private fun ConfirmDialog(pending: Pending, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var ready by remember(pending) { mutableStateOf(false) }
    LaunchedEffect(pending) { delay(700); ready = true }
    val needsStronger = pending.method == ApprovalMethod.BIOMETRIC
    val content = contentFor(pending.action, pending.shown)

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, dismissOnClickOutside = false)) {
        val view = LocalView.current
        SideEffect { view.rootView.filterTouchesWhenObscured = true }
        Card(shape = RoundedCornerShape(18.dp), border = BorderStroke(2.dp, Amber), colors = CardDefaults.cardColors(containerColor = AmberSurface, contentColor = Ink)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(content.title, fontWeight = FontWeight.Bold, color = AmberInk)
                content.rows.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = AmberInk)
                        Text(value, fontFamily = if (label in content.mono) FontFamily.Monospace else FontFamily.Default)
                    }
                }
                if (needsStronger) Text("This needs fingerprint confirmation, which isn't available yet.", color = AmberInk)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = onConfirm, enabled = ready && !needsStronger, modifier = Modifier.padding(start = 8.dp)) { Text(content.button) }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: AxisViewModel, keys: List<KeyRow>, firstRun: Boolean, onBack: () -> Unit) {
    val access by vm.access.collectAsStateWithLifecycle()
    val speak by vm.speakReplies.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val announce by vm.announceCalls.collectAsStateWithLifecycle()
    val crash by vm.crashReport.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val callPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        vm.setAnnounceCalls(granted[Manifest.permission.READ_PHONE_STATE] == true && granted[Manifest.permission.READ_CALL_LOG] == true)
    }
    var provider by rememberSaveable { mutableStateOf(Provider.GEMINI) }
    var draft by rememberSaveable { mutableStateOf("") }
    var githubDraft by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var githubError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (firstRun) "Add your AI key" else "Setup", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Axis uses your own AI keys, stored encrypted on this phone. Requests go straight to the provider; Axis has no servers. " +
            "Free-tier keys may let the provider use prompts to improve its products. Add several keys and Axis moves to the next one when a key is rate limited.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p -> FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        OutlinedTextField(
            value = draft, onValueChange = { draft = it; error = null }, modifier = Modifier.fillMaxWidth(),
            label = { Text("${provider.name.lowercase().replaceFirstChar { it.uppercase() }} API key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = error != null, supportingText = { error?.let { Text(it) } },
        )
        TextButton(onClick = {
            val url = when (provider) {
                Provider.GEMINI -> "https://aistudio.google.com/apikey"
                Provider.GROQ -> "https://console.groq.com/keys"
                Provider.OPENAI -> "https://platform.openai.com/api-keys"
                Provider.CLAUDE -> "https://console.anthropic.com/settings/keys"
            }
            context.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Where do I get a ${provider.name.lowercase().replaceFirstChar { it.uppercase() }} key?") }
        Button(onClick = { vm.addKey(provider, draft) { err -> error = err; if (err == null) draft = "" } }, enabled = !checking) {
            Text(if (checking) "Checking key" else "Add key")
        }

        keys.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${row.provider.name.lowercase().replaceFirstChar { it.uppercase() }} key ${row.id}", fontWeight = FontWeight.SemiBold)
                    Text(when (row.health) {
                        KeyHealth.OK -> "Ready"
                        KeyHealth.COOLING -> "Rate limited, retrying soon"
                        KeyHealth.INVALID -> "Rejected by the provider. Remove it and add a new one."
                    })
                }
                TextButton(onClick = { vm.removeKey(row.id) }) { Text("Remove") }
            }
        }

        if (!firstRun) {
            Text("GitHub token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Optional. A read-only token lets Axis fetch deployment links. Create one with only the “Deployments” and “Metadata” read permissions.")
            if (access.githubToken) {
                OutlinedButton(onClick = { vm.removeGitHubToken() }) { Text("Remove GitHub token") }
            } else {
                OutlinedTextField(
                    value = githubDraft, onValueChange = { githubDraft = it; githubError = null }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("GitHub token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    isError = githubError != null, supportingText = { githubError?.let { Text(it) } },
                )
                Button(onClick = { githubError = vm.setGitHubToken(githubDraft); if (githubError == null) githubDraft = "" }) { Text("Save token") }
            }

            Text("Replies", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().toggleable(value = speak, role = Role.Switch, onValueChange = vm::setSpeakReplies), verticalAlignment = Alignment.CenterVertically) {
                Text("Read replies aloud", Modifier.weight(1f))
                Switch(checked = speak, onCheckedChange = null)
            }
            Text("Off by default so message summaries aren't spoken in public.")

            Text("Phone calls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth().toggleable(value = announce, role = Role.Switch, onValueChange = { on ->
                    if (!on) vm.setAnnounceCalls(false)
                    else callPermissions.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS))
                }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Say who is calling", Modifier.weight(1f))
                Switch(checked = announce, onCheckedChange = null)
            }
            Text("Off by default because it speaks aloud. Needs access to phone state and call log.")

            crash?.let { report ->
                Text("Support", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Axis closed unexpectedly last time. The report holds no messages or contacts, and nothing is sent unless you share it.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                        context.tryStart(Intent.createChooser(send, "Share crash report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Share report") }
                    OutlinedButton(onClick = { vm.clearCrashReport() }) { Text("Dismiss") }
                }
            }

            Text("Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { vm.forgetEverything() }) { Text("Forget remembered facts and messages") }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

/** Opening another app can fail on phones without that app (no browser, no share target). Say so instead of crashing. */
private fun android.content.Context.tryStart(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(this, "This phone has no app that can open that.", android.widget.Toast.LENGTH_LONG).show()
    }
}
