package com.androidharness.app.phoneevents

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.automation.AutomationSchedule
import com.androidharness.app.automation.AutomationStatus
import com.androidharness.app.automation.AutomationTask
import com.androidharness.app.ui.common.FilledTonalButton
import com.androidharness.app.ui.common.TextButton
import com.androidharness.app.ui.settings.SettingsAnchor
import com.androidharness.app.ui.settings.SettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun PhoneEventsSection(container: AppContainer) {
    val context = LocalContext.current
    val events = remember(container) { PhoneEvents.get(context) }
    val state by events.policy.collectAsStateWithLifecycle()
    val notices by events.notices.collectAsStateWithLifecycle()
    val connected by events.listenerConnected.collectAsStateWithLifecycle()
    val tasks by container.automation.repository.tasks.collectAsStateWithLifecycle()
    val runtime by container.runtimePolicy.state.collectAsStateWithLifecycle()
    var notificationAccess by remember { mutableStateOf(events.enabledNotificationAccess()) }
    var windowAccess by remember { mutableStateOf(events.enabledWindowAccess()) }
    val manager = remember(context) { context.getSystemService(NotificationManager::class.java) }
    var showsAlerts by remember { mutableStateOf(manager.areNotificationsEnabled()) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, events) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            events.refresh()
            notificationAccess = events.enabledNotificationAccess()
            windowAccess = events.enabledWindowAccess()
            showsAlerts = manager.areNotificationsEnabled()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EventRule?>(null) }
    var editKinds by remember { mutableStateOf(PhoneEventKind.entries.toList()) }
    var confirmTask by remember { mutableStateOf<EventReminder?>(null) }
    var showNotices by remember { mutableStateOf(false) }
    var confirmRead by remember { mutableStateOf<VisibleNotice?>(null) }
    SettingsAnchor(R.string.phone_events_title) {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.phone_events_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.phone_events_intro), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = state.enabled, onCheckedChange = events::enable)
                }
                if (state.enabled) {
                    if (state.rules.any { it.kind == PhoneEventKind.NOTIFICATION } && !notificationAccess)
                        FilledTonalButton(onClick = { openSetting(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                            Text(stringResource(R.string.phone_events_grant_notifications))
                        }
                    if (state.rules.any { it.kind == PhoneEventKind.FOREGROUND } && !windowAccess)
                        FilledTonalButton(onClick = { openSetting(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text(stringResource(R.string.phone_events_grant_window))
                        }
                    if (state.rules.isNotEmpty()) {
                        if (!runtime.notificationsEnabled) {
                            Text(stringResource(R.string.phone_events_alerts_off, stringResource(R.string.background_settings_title)),
                                style = MaterialTheme.typography.bodySmall)
                        } else if (!showsAlerts) TextButton(onClick = {
                            openSetting(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text(stringResource(R.string.phone_events_show_alerts)) }
                    }
                    state.pending.sortedByDescending { it.time }.forEach { reminder ->
                        val rule = state.rules.find { it.id == reminder.ruleId } ?: return@forEach
                        val task = tasks.find { it.id == rule.taskId }
                        HorizontalDivider()
                        Text(stringResource(kindLabel(rule.kind), events.label(rule.app)), style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getDateTimeInstance().format(Date(reminder.time)), style = MaterialTheme.typography.labelSmall)
                        Row {
                            if (task != null) TextButton(onClick = { confirmTask = reminder }) {
                                Text(stringResource(R.string.phone_event_review_task, task.title))
                            }
                            TextButton(onClick = { events.dismiss(reminder) }) { Text(stringResource(R.string.phone_event_dismiss)) }
                        }
                    }
                    state.rules.forEach { rule ->
                        HorizontalDivider()
                        Text(stringResource(kindLabel(rule.kind), events.label(rule.app)), style = MaterialTheme.typography.bodyLarge)
                        val task = tasks.find { it.id == rule.taskId }
                        Text(task?.title ?: stringResource(if(rule.taskId == null) R.string.phone_event_remind_only else R.string.phone_event_task_missing),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row {
                            TextButton(onClick = { editKinds = listOf(rule.kind); editing = rule }) { Text(stringResource(R.string.phone_event_edit)) }
                            TextButton(onClick = { events.remove(rule) }) { Text(stringResource(R.string.phone_event_remove)) }
                        }
                    }
                    FilledTonalButton(onClick = { adding = true }) { Text(stringResource(R.string.phone_event_add)) }
                    if (notificationAccess && state.rules.any { it.kind == PhoneEventKind.NOTIFICATION })
                        TextButton(onClick = { events.refresh(); showNotices = true }) {
                            Text(stringResource(R.string.phone_event_view_notices, notices.size))
                        }
                }
            }
        }
    }

    if (state.enabled && adding) ChooseApp(context, onPick = { app ->
        adding = false
        editKinds = PhoneEventKind.entries.filter { k -> state.rules.none { it.app == app && it.kind == k } }
        val first = editKinds.firstOrNull()
        if (first != null) editing = EventRule(app, first)
        else Toast.makeText(context, R.string.phone_event_all_rules, Toast.LENGTH_SHORT).show()
    }, onClose = { adding = false })
    if (state.enabled) editing?.let { initial ->
        RuleEditor(initial, editKinds, events.label(initial.app), tasks.filter { it.schedule == AutomationSchedule.MANUAL && it.enabled },
            onSave = { rule ->
                events.setRule(rule); editing = null
            }, onClose = { editing = null })
    }
    if (state.enabled && showNotices) AlertDialog(onDismissRequest = { showNotices = false },
        title = { Text(stringResource(R.string.phone_event_active_notices)) },
        text = {
            Column {
                Text(stringResource(R.string.phone_event_metadata_only), style = MaterialTheme.typography.bodySmall)
                if (!connected || notices.isEmpty()) Text(stringResource(if (connected) R.string.phone_event_no_notices else R.string.phone_event_no_listener))
                LazyColumn(Modifier.heightIn(max=380.dp)) { items(notices, key = { it.id }) { notice ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(events.label(notice.app), style = MaterialTheme.typography.titleSmall)
                        Text(DateFormat.getDateTimeInstance().format(Date(notice.time)), style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (notice.canOpen) TextButton(onClick = {
                                if (!events.useNotice(context, notice.id, false)) unavailable(context)
                            }) { Text(stringResource(R.string.phone_event_open_source)) }
                            if (notice.canMarkRead) TextButton(onClick = { confirmRead = notice }) {
                                Text(stringResource(R.string.phone_event_mark_read))
                            }
                        }
                    }
                } }
            }
        }, confirmButton = { TextButton(onClick = { showNotices = false }) { Text(stringResource(android.R.string.ok)) } })
    if (state.enabled) confirmRead?.let { n ->
        AlertDialog(onDismissRequest = { confirmRead = null }, title = { Text(stringResource(R.string.phone_event_mark_read)) },
            text = { Text(stringResource(R.string.phone_event_read_confirm, events.label(n.app))) },
            confirmButton = { TextButton(onClick = { if (!events.useNotice(context,n.id,true)) unavailable(context); confirmRead = null }) {
                Text(stringResource(R.string.phone_event_mark_read)) } },
            dismissButton = { TextButton(onClick = { confirmRead = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
    if (state.enabled) confirmTask?.let { reminder ->
        val rule = state.rules.find { it.id == reminder.ruleId }
        val task = tasks.find { it.id == rule?.taskId }
        if (task != null && rule != null && reminder in state.pending) {
            val eligible = task.schedule == AutomationSchedule.MANUAL && task.enabled && task.activeRun == null &&
                task.lastStatus !in setOf(AutomationStatus.QUEUED, AutomationStatus.RUNNING)
            AlertDialog(onDismissRequest = { confirmTask = null }, title = { Text(task.title) },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(task.projectName, style = MaterialTheme.typography.labelLarge)
                    Text(task.prompt)
                    if (!eligible) Text(stringResource(R.string.phone_event_task_busy))
                } }, confirmButton = { TextButton(enabled = eligible, onClick = {
                    events.refresh()
                    val current = container.automation.repository.task(task.id)
                    if (current == task && reminder in events.policy.value.pending) {
                        events.dismiss(reminder)
                        container.automation.runNow(task.id)
                    } else unavailable(context)
                    confirmTask = null
                }) { Text(stringResource(R.string.phone_event_run_task)) } },
                dismissButton = { TextButton(onClick = { confirmTask = null }) { Text(stringResource(android.R.string.cancel)) } })
        }
    }
}

private fun kindLabel(kind: PhoneEventKind) = if (kind == PhoneEventKind.FOREGROUND) R.string.phone_event_opened else R.string.phone_event_notified
private fun unavailable(context: Context) = Toast.makeText(context,R.string.phone_event_unavailable,Toast.LENGTH_SHORT).show()
private fun openSetting(context: Context, intent: Intent) {
    try { context.startActivity(intent) } catch (_: ActivityNotFoundException) { unavailable(context) }
    catch (_: SecurityException) { unavailable(context) }
}

@Composable
private fun ChooseApp(context: Context, onPick: (String)->Unit, onClose: ()->Unit) {
    var query by remember { mutableStateOf("") }
    val apps by produceState<List<Pair<String,String>>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
                .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                .filter { it.first != context.packageName }
                .distinctBy { it.first }.sortedBy { it.second.lowercase() }
        }
    }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.phone_event_choose_app)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query,{query=it},singleLine=true,label={ Text(stringResource(R.string.phone_event_search)) })
            if (apps == null) LinearProgressIndicator()
            LazyColumn(Modifier.heightIn(max=330.dp)) {
                items(apps.orEmpty().filter { it.first.contains(query,true) || it.second.contains(query,true) }, key={it.first}) { (pkg,label) ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical=8.dp)) {
                        Text(label, style=MaterialTheme.typography.bodyLarge)
                        Text(pkg, style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } })
}
@Composable
private fun RuleEditor(initial: EventRule, kinds: List<PhoneEventKind>, label: String, tasks: List<AutomationTask>, onSave: (EventRule)->Unit, onClose: ()->Unit) {
    var kind by remember(initial) { mutableStateOf(initial.kind) }
    var task by remember(initial) { mutableStateOf(initial.taskId) }
    AlertDialog(onDismissRequest=onClose,title={Text(label)},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(initial.app,style=MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.phone_event_when),style=MaterialTheme.typography.titleSmall)
            for (option in kinds) Choice(stringResource(if(option==PhoneEventKind.FOREGROUND) R.string.phone_event_when_open else R.string.phone_event_when_notified),kind==option){kind=option}
            HorizontalDivider()
            Text(stringResource(R.string.phone_event_then),style=MaterialTheme.typography.titleSmall)
            Choice(stringResource(R.string.phone_event_remind_only),task==null){task=null}
            tasks.forEach { item -> Choice(item.title,task==item.id){task=item.id} }
        }
    },confirmButton={TextButton(onClick={onSave(EventRule(initial.app,kind,task))}){Text(stringResource(R.string.phone_event_save))}},
        dismissButton={TextButton(onClick=onClose){Text(stringResource(android.R.string.cancel))}})
}
@Composable
private fun Choice(label: String, selected:Boolean, choose:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=choose).padding(vertical=4.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
        RadioButton(selected=selected,onClick=null)
        Text(label,style=MaterialTheme.typography.bodyMedium)
    }
}
