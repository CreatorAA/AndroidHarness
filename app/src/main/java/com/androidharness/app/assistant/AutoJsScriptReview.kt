package com.androidharness.app.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidharness.app.R
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton

/** Kept in the assistant window: the target app remains underneath the actual confirmation. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutoJsScriptReview(draft: AutoJsScriptDrafts.Draft, app: String?, allowed: Boolean,
                                onRun: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(draft) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.autojs_trial_title, draft.savedName ?: stringResource(R.string.autojs_anonymous)),
            style = MaterialTheme.typography.titleMedium)
        Text(if (allowed && app != null) stringResource(R.string.autojs_trial_here, app)
            else stringResource(R.string.autojs_trial_other), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) stringResource(R.string.autojs_trial_less) else stringResource(R.string.autojs_trial_steps, draft.steps.size))
        }
        if (expanded) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            draft.steps.forEach { step -> Text(step, style = MaterialTheme.typography.bodySmall) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(enabled = allowed && app != null, onClick = onRun, modifier = Modifier.testTag("autojs_run_confirm")) {
                Text(stringResource(R.string.autojs_trial_allow))
            }
            TextButton(onClick = onCancel) { Text(stringResource(android.R.string.cancel)) }
        }
    }
}
