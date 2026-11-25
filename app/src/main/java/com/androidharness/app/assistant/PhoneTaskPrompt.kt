package com.androidharness.app.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.androiduse.PageActionList
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.settings.openAssistantPicker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun openPhoneAccessibilitySettings(context: Context): Boolean {
    try {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    } catch (_: ActivityNotFoundException) {
        // No Settings handler on this build.
    } catch (_: SecurityException) {
        // Device policy may restrict access.
    }
    Toast.makeText(context, R.string.assistant_phone_settings_unavailable, Toast.LENGTH_LONG).show()
    return false
}

@Composable
internal fun PhoneTaskPrompt(
    state: AssistantUiState, setupError: Int?, modifier: Modifier,
    onDismiss: () -> Unit, onPageActions: suspend (String) -> PageActionList,
    fromCode: Boolean = false,
    instruction: String? = null,
    onAllowOnce: (() -> Unit)? = null,
    onManagedStart: (() -> Unit)? = null,
) {
    val screen = state.screen
    val target = screen.packageName
    val context = LocalContext.current
    var waited by remember(target) { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (target.isNullOrBlank()) { delay(1_500); waited = true }
    }
    val problem = when {
        setupError != null -> setupError
        target.isNullOrBlank() && (state.screenSettled || waited) -> R.string.assistant_phone_missing_target
        else -> null
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (target == null) stringResource(R.string.assistant_phone_action)
             else stringResource(R.string.phone_task_target, screen.appName ?: target),
             style = MaterialTheme.typography.titleSmall)
        if (target != null) {
            if (instruction != null) Text(instruction, style = MaterialTheme.typography.bodyMedium)
            if (screen.appName != target)
                Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.assistant_phone_consent), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.phone_managed_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { onManagedStart?.invoke() }, enabled = onManagedStart != null) {
                Text(stringResource(R.string.phone_managed_start))
            }
            if (instruction != null) Row {
                TextButton(onClick = { onAllowOnce?.invoke() }, enabled = onAllowOnce != null) { Text(stringResource(R.string.assistant_phone_allow_once)) }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
            key(target) { PageActionCheck(target, onPageActions) }
        } else if (problem == null) Text(stringResource(R.string.assistant_phone_identifying))
        if (fromCode) Text(stringResource(R.string.android_use_from_code), style = MaterialTheme.typography.labelLarge)
        if (problem != null) Text(stringResource(problem), color = MaterialTheme.colorScheme.error)
        if (problem == R.string.assistant_phone_missing_target) {
            TextButton(onClick = { onDismiss(); openAssistantPicker(context) }) {
                Text(stringResource(R.string.assistant_phone_context_settings))
            }
        }
    }
}

/** Local inspection only: no model, task grant or execution. */
@Composable
private fun PageActionCheck(target: String, check: suspend (String) -> PageActionList) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PageActionList?>(null) }
    val current = result
    Column(Modifier.semantics { testTagsAsResourceId = true }) {
        TextButton(enabled = !checking, onClick = {
            checking = true
            result = null
            scope.launch {
                try { result = check(target) } finally { checking = false }
            }
        }) {
            Text(stringResource(if (checking) R.string.phone_page_checking else R.string.phone_page_check))
        }
        if (current != null) {
            val label = when (current.status) {
                "available" -> stringResource(R.string.phone_page_found, current.actions.size)
                "empty" -> stringResource(R.string.phone_page_empty)
                "timeout" -> stringResource(R.string.phone_page_timeout)
                "changed", "wrong_target" -> stringResource(R.string.phone_page_changed)
                else -> stringResource(R.string.phone_page_unavailable)
            }
            Text(label, Modifier.testTag("phone_page_result"), style = MaterialTheme.typography.bodyMedium)
            current.actions.forEach { entry ->
                Text(entry.id, Modifier.testTag("phone_page_action_id"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
