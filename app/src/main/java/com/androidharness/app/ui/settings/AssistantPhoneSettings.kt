package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.androiduse.AutoJsPhoneDriver
import com.androidharness.app.androiduse.validAutoJsToken
import com.androidharness.app.assistant.openPhoneAccessibilitySettings
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantPhoneSettings(container: AppContainer) {
    val settings = container.androidUse.settings
    val active by container.androidUse.activeSession.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var autoJsEnabled by remember { mutableStateOf(settings.autoJsEnabled) }
    var autoManage by remember { mutableStateOf(settings.autoManageUserTasks) }
    var hasPairingToken by remember { mutableStateOf(settings.token.isNotBlank()) }
    var enabled by remember { mutableStateOf(settings.isAccessibilityEnabled()) }
    var port by remember { mutableIntStateOf(settings.port) }
    var editing by remember { mutableStateOf(false) }
    var showScripts by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember(autoJsEnabled, port) { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = settings.isAccessibilityEnabled()
                autoManage = settings.autoManageUserTasks
                autoJsEnabled = settings.autoJsEnabled
                hasPairingToken = settings.token.isNotBlank()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    SettingsAnchor(R.string.assistant_phone_action) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.assistant_phone_action), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.assistant_phone_settings_intro), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth().testTag("phone_auto_manage")
                    .toggleable(value = autoManage, role = Role.Switch, onValueChange = {
                        settings.autoManageUserTasks = it
                        autoManage = it
                    }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.phone_auto_manage), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.phone_auto_manage_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoManage, onCheckedChange = null)
                }
                Text(stringResource(R.string.assistant_phone_builtin), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(if (enabled) R.string.assistant_phone_accessibility_on else R.string.assistant_phone_accessibility_off),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { openPhoneAccessibilitySettings(context) }) {
                    Text(stringResource(R.string.assistant_phone_accessibility_settings))
                }
                Row(Modifier.fillMaxWidth().testTag("phone_allow_autojs")
                    .toggleable(value = autoJsEnabled, role = Role.Switch, enabled = active == null && !checking,
                        onValueChange = { selected ->
                            settings.autoJsEnabled = selected
                            autoJsEnabled = selected
                            if (!selected) { showScripts = false; container.autoJsScriptDrafts.clear() }
                        }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.autojs_allow), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.autojs_allow_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = autoJsEnabled, onCheckedChange = null, enabled = active == null && !checking)
                }
                if (autoJsEnabled) {
                    Text(if (hasPairingToken) stringResource(R.string.assistant_phone_endpoint, port)
                        else stringResource(R.string.autojs_need_pair), style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = hasPairingToken && active == null && !checking, onClick = {
                            if (checking) return@FilledTonalButton
                            checking = true
                            checkResult = null
                            scope.launch {
                                try {
                                    checkResult = withContext(Dispatchers.IO) {
                                        val driver = AutoJsPhoneDriver(context.applicationContext, settings.port, settings.token)
                                        try {
                                            if ("observe" in driver.capabilities()) R.string.assistant_phone_check_ok
                                            else R.string.assistant_phone_check_partial
                                        } finally { driver.close() }
                                    }
                                } catch (e: CancellationException) { throw e }
                                catch (e: Exception) {
                                    // The peer controls error messages. Show only our own recovery steps, never its response or credential.
                                    val reason = e.message.orEmpty()
                                    checkResult = when {
                                        "401" in reason || reason.contains("unauthor", true) || e is IllegalArgumentException ->
                                            R.string.assistant_phone_check_token
                                        "403" in reason || reason.contains("pair", true) || reason.contains("denied", true) ||
                                            reason.contains("timed out", true) -> R.string.assistant_phone_check_pairing
                                        else -> R.string.assistant_phone_check_offline
                                    }
                                } finally { checking = false }
                            }
                        }) { Text(stringResource(R.string.assistant_phone_check)) }
                        TextButton(onClick = { editing = true }, enabled = active == null && !checking) {
                            Text(stringResource(R.string.assistant_phone_pair))
                        }
                    }
                    (if (checking) R.string.assistant_phone_check_waiting else checkResult)?.let { message ->
                        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { showScripts = true }) { Text(stringResource(R.string.autojs_library)) }
                }
            }
        }
    }
    if (showScripts && autoJsEnabled) AutoJsScriptLibrary(container, onClose = { showScripts = false })
    if (editing && active == null) {
        val tokenSaved = remember { settings.token.isNotBlank() }
        var portText by remember { mutableStateOf(port.toString()) }
        var token by remember { mutableStateOf("") }
        var saveFailed by remember { mutableStateOf(false) }
        val number = portText.toIntOrNull()?.takeIf { it in 1..65535 }
        AlertDialog(onDismissRequest = { editing = false }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            title = { Text(stringResource(R.string.assistant_phone_pair)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.assistant_phone_pair_steps))
                    OutlinedTextField(portText, { portText = it; saveFailed = false }, singleLine = true,
                        label = { Text(stringResource(R.string.assistant_phone_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = number == null)
                    OutlinedTextField(token, { token = it; saveFailed = false }, singleLine = true,
                        label = { Text(stringResource(if (tokenSaved) R.string.assistant_phone_replace_token else R.string.assistant_phone_token)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(), isError = token.isNotEmpty() && !validAutoJsToken(token.trim()),
                        supportingText = { Text(stringResource(R.string.assistant_phone_token_hint)) })
                    if (saveFailed) Text(stringResource(R.string.assistant_phone_save_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(enabled = number != null && (validAutoJsToken(token.trim()) || (token.isEmpty() && tokenSaved)),
                onClick = {
                    try {
                        if (token.isNotBlank()) settings.token = token.trim()
                        settings.port = checkNotNull(number)
                        settings.autoJsEnabled = true
                        checkResult = null
                        port = settings.port
                        autoJsEnabled = true
                        hasPairingToken = true
                        editing = false
                    } catch (_: Exception) { saveFailed = true }
                }) { Text(stringResource(R.string.assistant_phone_use_companion)) } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
