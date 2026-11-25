package com.androidharness.app.ui.settings

import com.androidharness.app.agent.ThinkingLevel
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.androidharness.app.R
import com.androidharness.app.AppContainer
import com.androidharness.app.assistant.HarnessAssistantService
import com.androidharness.app.phoneevents.PhoneEventsSection
import com.androidharness.app.ui.common.SwipeDismissibleSnackbarHost
import com.androidharness.app.assistant.AssistantAvailability
import com.androidharness.app.assistant.AssistantCheck
import com.androidharness.app.assistant.AssistantFacts
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

/** A role holder can survive a cleaner's force-stop after Android clears its voice service. */
internal fun isHarnessAssistant(context: Context): Boolean =
    VoiceInteractionService.isActiveService(context, ComponentName(context, HarnessAssistantService::class.java))

internal fun needsAssistantReconnect(context: Context): Boolean =
    AssistantCheck.roleHeld(context) == true && !isHarnessAssistant(context)

/** Puts the assistant check on the clipboard for a bug report. */
private fun copyAssistantReport(context: Context, facts: AssistantFacts) {
    val report = AssistantCheck.report(facts, com.androidharness.app.BuildConfig.VERSION_NAME)
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.assistant_check_copy_label), report))
    // Android 13 and later confirm copies on their own.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        Toast.makeText(context, R.string.assistant_check_copied, Toast.LENGTH_SHORT).show()
}

/** Opens the system screen where the assistant app is picked. */
internal fun openAssistantPicker(context: Context) {
    val intents = listOf(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(Settings.ACTION_SETTINGS))
    for (intent in intents) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
    Toast.makeText(context, R.string.assistant_settings_open_failed, Toast.LENGTH_LONG).show()
}

/** Only after the app is opened and unlocked; never relaunch a force-stopped service. */
@Composable
internal fun AssistantReconnectNotice(modifier: Modifier) {
    val context = LocalContext.current
    var offered by rememberSaveable { mutableStateOf(false) }
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        if (!offered && needsAssistantReconnect(context)) {
            offered = true
            val result = host.showSnackbar(context.getString(R.string.assistant_edge_reconnect_steps),
                actionLabel = context.getString(R.string.assistant_edge_reconnect),
                withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed && needsAssistantReconnect(context))
                openAssistantPicker(context)
        }
    }
    SwipeDismissibleSnackbarHost(host, modifier.navigationBarsPadding().padding(8.dp))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AssistantSetupSection(
    container: AppContainer,
    voiceFirst: Boolean,
    onVoiceFirst: (Boolean) -> Unit,
    thinking: ThinkingLevel,
    onThinking: (ThinkingLevel) -> Unit,
    speakReplies: Boolean,
    onSpeakReplies: (Boolean) -> Unit,
    edgeHandleEnabled: Boolean,
    onEdgeHandleEnabled: (Boolean) -> Unit,
    modelPicker: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var facts by remember(context) { mutableStateOf(AssistantCheck.facts(context, isHarnessAssistant(context))) }
    val active = facts.active
    val availability = AssistantCheck.availability(facts)
    val reconnect = availability == AssistantAvailability.RECONNECT
    var overlayAllowed by remember(context) { mutableStateOf(Settings.canDrawOverlays(context)) }
    var requestingOverlay by rememberSaveable { mutableStateOf(false) }
    val setEdgeHandle by rememberUpdatedState(onEdgeHandleEnabled)
    // Both grants are managed by Android. The overlay settings activity has no result contract.
    DisposableEffect(owner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                facts = AssistantCheck.facts(context, isHarnessAssistant(context))
                overlayAllowed = Settings.canDrawOverlays(context)
                if (requestingOverlay) {
                    requestingOverlay = false
                    if (facts.active && overlayAllowed) setEdgeHandle(true)
                }
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val scheme = MaterialTheme.colorScheme
    val badge by animateColorAsState(if (active) scheme.primary else scheme.secondaryContainer, label = "assistantBadge")
    val glyph by animateColorAsState(if (active) scheme.onPrimary else scheme.onSecondaryContainer, label = "assistantGlyph")

    SettingsAnchor(R.string.assistant_settings_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(44.dp).background(badge, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                        Icon(if (active) Icons.Rounded.CheckCircle else Icons.Rounded.TouchApp, null, Modifier.size(22.dp), tint = glyph)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.assistant_settings_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(if (reconnect) R.string.assistant_edge_disconnected else if (active) R.string.assistant_settings_on else R.string.assistant_settings_off),
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.assistant_settings_desc), style = MaterialTheme.typography.bodyMedium)
                val canPick = active || reconnect || availability == AssistantAvailability.PICK ||
                    availability == AssistantAvailability.PICK_SAMSUNG
                if (canPick) Text(stringResource(if (reconnect) R.string.assistant_edge_reconnect_steps else R.string.assistant_settings_steps), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                when (availability) {
                    AssistantAvailability.ACTIVE, AssistantAvailability.RECONNECT -> null
                    AssistantAvailability.UNSUPPORTED -> R.string.assistant_check_unsupported
                    AssistantAvailability.OTHER_PROFILE -> R.string.assistant_check_profile
                    AssistantAvailability.PICK_SAMSUNG -> R.string.assistant_check_samsung
                    AssistantAvailability.PICK -> R.string.assistant_check_generic
                }?.let { hint ->
                    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall,
                        color = if (canPick) scheme.onSurfaceVariant else scheme.error)
                }
                Text(stringResource(R.string.assistant_settings_privacy), style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant)
                // The anchor fills the row, so the copy button sits on its own line below it.
                if (canPick) SettingsAnchor(R.string.assistant_settings_open) {
                    FilledTonalButton(onClick = { openAssistantPicker(context) }) {
                        Text(stringResource(if (reconnect) R.string.assistant_edge_reconnect else R.string.assistant_settings_open))
                    }
                }
                if (!active) TextButton(onClick = { copyAssistantReport(context, facts) }) {
                    Text(stringResource(R.string.assistant_check_copy))
                }
            }
        }
    }
    SettingsAnchor(R.string.assistant_edge_title) {
        val canToggle = edgeHandleEnabled || (active && !requestingOverlay)
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().toggleable(edgeHandleEnabled, enabled = canToggle, role = Role.Switch) { enabled ->
                    if (!enabled) {
                        requestingOverlay = false
                        setEdgeHandle(false)
                    } else {
                        facts = AssistantCheck.facts(context, isHarnessAssistant(context))
                        if (!facts.active) return@toggleable
                        overlayAllowed = Settings.canDrawOverlays(context)
                        if (overlayAllowed) {
                            setEdgeHandle(true)
                        } else {
                            requestingOverlay = true
                            if (!openOverlayPermission(context)) requestingOverlay = false
                        }
                    }
                }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_edge_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_edge_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                    val requirement = when {
                        reconnect -> R.string.assistant_edge_disconnected
                        !active -> R.string.assistant_settings_off
                        !overlayAllowed -> R.string.assistant_edge_permission_hint
                        else -> null
                    }
                    if (requirement != null) {
                        Text(stringResource(requirement), style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant)
                    }
                }
                Switch(edgeHandleEnabled, onCheckedChange = null, enabled = canToggle)
            }
            if (edgeHandleEnabled && active && !overlayAllowed) {
                FilledTonalButton(onClick = { openOverlayPermission(context) }, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                    Text(stringResource(R.string.assistant_edge_grant_overlay))
                }
            }
        }
    }
    AssistantPhoneSettings(container)
    PhoneEventsSection(container)
    SettingsAnchor(R.string.assistant_settings_voice_first) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(voiceFirst, role = Role.Switch, onValueChange = onVoiceFirst).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_settings_voice_first), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_settings_voice_first_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                Switch(voiceFirst, onCheckedChange = null)
            }
        }
    }
    modelPicker()
    SettingsAnchor(R.string.assistant_settings_thinking) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_settings_thinking), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_settings_thinking_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                val shown = assistantThinkingChoice(thinking)
                SettingsChoiceGroup(
                    options = AssistantThinkingChoices.map { it to it.label },
                    selected = { it == shown },
                    onSelect = onThinking,
                )
            }
        }
    }
    SettingsAnchor(R.string.assistant_settings_speak) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().toggleable(speakReplies, role = Role.Switch, onValueChange = onSpeakReplies).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.assistant_settings_speak), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.assistant_settings_speak_desc), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                }
                Switch(speakReplies, onCheckedChange = null)
            }
        }
    }
}

private fun openOverlayPermission(context: Context): Boolean {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        intent.data = Uri.fromParts("package", context.packageName, null)
    }
    try {
        context.startActivity(intent)
        return true
    } catch (_: ActivityNotFoundException) {
        // Some Android builds do not expose this screen.
    } catch (_: SecurityException) {
        // Device policy can make special app access unavailable.
    }
    Toast.makeText(context, R.string.assistant_edge_permission_unavailable, Toast.LENGTH_LONG).show()
    return false
}

/** The levels offered in settings; the panel can also pick any level the current model has. */
private val AssistantThinkingChoices = listOf(ThinkingLevel.OFF, ThinkingLevel.LOW, ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.MAX)

/** A level picked in the panel that settings does not list shows as its nearest neighbour. */
private fun assistantThinkingChoice(level: ThinkingLevel): ThinkingLevel = when (level) {
    ThinkingLevel.OFF -> ThinkingLevel.OFF
    ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> ThinkingLevel.LOW
    ThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
    ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> ThinkingLevel.HIGH
    ThinkingLevel.MAX, ThinkingLevel.ULTRA -> ThinkingLevel.MAX
}
