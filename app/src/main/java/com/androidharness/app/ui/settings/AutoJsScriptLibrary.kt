package com.androidharness.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.androiduse.AutoJsUiScripts
import com.androidharness.app.ui.common.Button
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.IconButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutoJsScriptLibrary(container: AppContainer, onClose: () -> Unit) {
    val store = container.androidUse.scripts
    val rows by store.entries.collectAsStateWithLifecycle()
    val ready by container.autoJsScriptDrafts.pending.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val hasPairingToken = remember { container.androidUse.settings.token.isNotBlank() }
    var editing by rememberSaveable { mutableStateOf(false) }
    var originalName by rememberSaveable { mutableStateOf<String?>(null) }
    var originalSource by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var tested by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val active = rows.firstOrNull { it.name == originalName }
    val sample = stringResource(R.string.autojs_sample_source)
    fun edit(saved: String?, body: String) {
        originalName = saved; originalSource = body; name = saved.orEmpty(); source = body
        tested = false; error = null; editing = true
    }
    fun leave() {
        if (working) return
        if (!editing) onClose()
        else if (source != originalSource || name != originalName.orEmpty()) discard = true
        else { editing = false; error = null }
    }
    fun write(work: suspend () -> Unit) {
        if (working) return
        working = true; error = null
        scope.launch {
            try { work() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: context.getString(R.string.autojs_write_failed) }
            finally { working = false }
        }
    }
    val steps = remember(source, editing) { if (editing) runCatching { AutoJsUiScripts.check(source) } else null }
    val dirtySaved = originalName != null && active?.source != originalSource
    val canStage = hasPairingToken && steps?.isSuccess == true && (originalName == null || active?.enabled == true && !dirtySaved)
    fun stage() {
        try {
            require(container.androidUse.settings.autoJsEnabled) { context.getString(R.string.autojs_not_selected) }
            val saved = originalName?.takeIf { active?.source == source && active?.enabled == true }
            container.autoJsScriptDrafts.stage(source, saved)
            error = null
        } catch (e: IllegalArgumentException) { error = e.message }
    }
    fun save() = write {
        val title = name.trim()
        require(originalName != null || store.entries.value.none { it.name == title }) { context.getString(R.string.autojs_name_exists) }
        require(originalName == null || store.entries.value.firstOrNull { it.name == originalName }?.source == originalSource) {
            context.getString(R.string.autojs_changed)
        }
        store.save(title, source, active?.enabled ?: true)
        editing = false
    }
    Dialog(onDismissRequest = ::leave, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }, color = scheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::leave) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.autojs_back)) }
                    Text(if (!editing) stringResource(R.string.autojs_library) else originalName ?: stringResource(R.string.autojs_new),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (editing) Button(enabled = !working && name.isNotBlank() && steps?.isSuccess == true && !dirtySaved,
                        onClick = ::save, modifier = Modifier.testTag("autojs_save")) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.settings_action_save))
                    } else FilledTonalButton(onClick = { edit(null, sample) }, modifier = Modifier.testTag("autojs_new")) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.autojs_new))
                    }
                }
                error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = scheme.error) }
                ready?.let { staged ->
                    val remaining = container.autoJsScriptDrafts.remaining(staged)
                    LaunchedEffect(staged) {
                        kotlinx.coroutines.delay(remaining)
                        container.autoJsScriptDrafts.discard(staged)
                    }
                    Surface(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium,
                        color = scheme.tertiaryContainer) {
                        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.autojs_prepared, staged.savedName ?: stringResource(R.string.autojs_anonymous)),
                                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { container.autoJsScriptDrafts.discard(staged) }) { Text(stringResource(android.R.string.cancel)) }
                        }
                    }
                }
                if (editing) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (originalName == null) OutlinedTextField(value = name, onValueChange = { name = it; error = null },
                            singleLine = true, label = { Text(stringResource(R.string.autojs_name)) },
                            modifier = Modifier.fillMaxWidth().testTag("autojs_name"))
                        OutlinedTextField(value = source, onValueChange = { source = it; tested = false; error = null },
                            minLines = 6, maxLines = 16, modifier = Modifier.fillMaxWidth().testTag("autojs_source"),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = HarnessMono),
                            label = { Text(stringResource(R.string.autojs_source)) }, isError = tested && steps?.isFailure == true)
                        Text(stringResource(R.string.autojs_format), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = { tested = true }, modifier = Modifier.testTag("autojs_check")) {
                                Text(stringResource(R.string.autojs_check))
                            }
                            TextButton(onClick = ::stage, enabled = canStage && !working, modifier = Modifier.testTag("autojs_prepare")) {
                                Text(stringResource(R.string.autojs_prepare))
                            }
                        }
                        if (dirtySaved) Text(stringResource(R.string.autojs_changed), color = scheme.error)
                        else if (originalName != null && active?.enabled != true)
                            Text(stringResource(R.string.autojs_disabled_hint), style = MaterialTheme.typography.bodyMedium)
                        else if (!hasPairingToken)
                            Text(stringResource(R.string.autojs_need_pair), style = MaterialTheme.typography.bodyMedium)
                        if (tested) {
                            val parsed = steps?.getOrNull()
                            if (parsed != null) {
                                Text(stringResource(R.string.autojs_preview, parsed.size), style = MaterialTheme.typography.titleSmall)
                                parsed.forEach { step -> Text(step, style = MaterialTheme.typography.bodyMedium) }
                            } else Text(steps?.exceptionOrNull()?.message ?: stringResource(R.string.autojs_write_failed), color = scheme.error)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            Text(stringResource(if (rows.isEmpty()) R.string.autojs_empty else R.string.autojs_enabled_hint),
                                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        items(rows, key = { it.name }) { entry ->
                            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = scheme.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable { edit(entry.name, entry.source) }.padding(14.dp)) {
                                        Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(entry.source.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = HarnessMono))
                                    }
                                    Switch(checked = entry.enabled, onCheckedChange = { v -> write { store.setEnabled(entry.name, v) } },
                                        enabled = !working, modifier = Modifier.testTag("autojs_toggle_" + entry.name))
                                    IconButton(onClick = { removing = entry.name }, enabled = !working) {
                                        Icon(Icons.Rounded.Delete, stringResource(R.string.autojs_delete, entry.name))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    removing?.let { doomed ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text(stringResource(R.string.autojs_delete, doomed)) },
            confirmButton = { TextButton(onClick = { removing = null; write { store.delete(doomed) } }) { Text(stringResource(R.string.autojs_delete_action)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.sk_discard_title)) },
        confirmButton = { TextButton(onClick = { discard = false; editing = false; error = null }) { Text(stringResource(R.string.sk_discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.sk_keep_editing)) } })
}
