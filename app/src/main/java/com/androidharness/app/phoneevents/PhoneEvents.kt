package com.androidharness.app.phoneevents

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.AtomicFile
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.androidharness.app.AppNotifications
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import com.androidharness.app.androiduse.PhoneAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

internal data class VisibleNotice(val id: String, val app: String, val time: Long, val canOpen: Boolean, val canMarkRead: Boolean)

/** The only durable event data is the local rule and reminder time. Notification contents never enter a chat. */
internal class PhoneEvents private constructor(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone-events.json"))
    private val initial = try { file.openRead().bufferedReader().use { json.decodeFromString<EventPolicy>(it.readText()) } }
        catch (_: FileNotFoundException) { EventPolicy() }
        catch (_: SerializationException) { EventPolicy() }
    private val state = MutableStateFlow(initial)
    val policy = state.asStateFlow()
    private val items = mutableMapOf<String, VisibleNotice>()
    private val displayed = MutableStateFlow<List<VisibleNotice>>(emptyList())
    val notices = displayed.asStateFlow()
    private val connected = MutableStateFlow(false)
    val listenerConnected = connected.asStateFlow()
    private val edges = EventEdges()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var listener: PhoneNotificationListener? = null

    private fun save(next: EventPolicy) {
        if (next == state.value) return
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(EventPolicy.serializer(), next).toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) { file.failWrite(stream); throw e }
        val remaining = next.pending.mapTo(mutableSetOf()) { it.ruleId }
        state.value.pending.filterNot { it.ruleId in remaining }.forEach { manager.cancel(it.ruleId, ID) }
        state.value = next
    }
    fun enabledNotificationAccess(): Boolean = hasService("enabled_notification_listeners",
        ComponentName(context, PhoneNotificationListener::class.java))
    fun enabledWindowAccess(): Boolean = hasService(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ComponentName(context, PhoneAccessibilityService::class.java))
    private fun hasService(setting: String, component: ComponentName) =
        Settings.Secure.getString(context.contentResolver, setting).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    private fun allowed(app: String, kind: PhoneEventKind) =
        state.value.enabled && app != context.packageName && state.value.rules.any { it.app == app && it.kind == kind }

    @Synchronized fun enable(value: Boolean) {
        save(state.value.copy(enabled = value, pending = emptyList()))
        edges.resetForeground()
        refresh()
    }
    @Synchronized fun setRule(rule: EventRule) {
        require(rule.app.isNotBlank() && rule.app != context.packageName)
        val existing = state.value.rules.find { it.id == rule.id }
        if (existing == rule) return
        save(state.value.setRule(rule))
        refresh()
    }
    @Synchronized fun remove(rule: EventRule) { save(state.value.remove(rule.id)); refresh() }
    @Synchronized fun dismiss(reminder: EventReminder) { save(state.value.copy(pending = state.value.pending - reminder)) }

    /** Called on returning from system settings as well as on binder transitions. Seed current notices without alerting. */
    @Synchronized fun refresh() {
        val notif = enabledNotificationAccess()
        val window = enabledWindowAccess()
        val valid = state.value.rules.filter { if (it.kind == PhoneEventKind.NOTIFICATION) notif else window }.map { it.id }.toSet()
        save(state.value.copy(pending = state.value.pending.filter { it.ruleId in valid }))
        val before = items.toMap()
        items.clear(); edges.resetNotifications()
        val service = listener
        if (state.value.enabled && notif && service != null && state.value.rules.any { it.kind == PhoneEventKind.NOTIFICATION }) {
            try {
                service.activeNotifications?.forEach { n ->
                    if (eligible(n)) { edges.posted(n.key); items[n.key] = describe(n, before[n.key]) }
                }
            } catch (_: SecurityException) { }
        }
        publish()
    }

    @Synchronized fun bind(service: PhoneNotificationListener) {
        listener = service; connected.value = true; refresh()
    }
    @Synchronized fun disconnected(service: PhoneNotificationListener) {
        if (listener !== service) return
        listener = null; connected.value = false; items.clear(); edges.resetNotifications(); publish()
        if (!enabledNotificationAccess()) clearPending(PhoneEventKind.NOTIFICATION)
    }
    private fun clearPending(kind: PhoneEventKind) {
        val ids = state.value.rules.filter { it.kind == kind }.mapTo(mutableSetOf()) { it.id }
        save(state.value.copy(pending = state.value.pending.filterNot { it.ruleId in ids }))
    }
    @Synchronized fun posted(service: PhoneNotificationListener, n: StatusBarNotification) {
        if (service !== listener) return
        if (!eligible(n)) {
            if (items.remove(n.key) != null) publish()
            return
        }
        val fresh = edges.posted(n.key)
        items[n.key] = describe(n, items[n.key])
        publish()
        if (fresh) fire(n.packageName, PhoneEventKind.NOTIFICATION)
    }
    @Synchronized fun removed(service: PhoneNotificationListener, key: String) {
        if (service !== listener) return
        edges.removed(key); if (items.remove(key) != null) publish()
    }
    private fun eligible(n: StatusBarNotification): Boolean = n.user == Process.myUserHandle() &&
        allowed(n.packageName, PhoneEventKind.NOTIFICATION) && n.notification.visibility != Notification.VISIBILITY_SECRET &&
        !n.isOngoing && n.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
    private fun describe(n: StatusBarNotification, old: VisibleNotice?): VisibleNotice {
        val content = n.notification.contentIntent
        return VisibleNotice(old?.takeIf { it.time == n.postTime }?.id ?: UUID.randomUUID().toString(), n.packageName, n.postTime,
            (content != null && content.creatorPackage == n.packageName && Build.VERSION.SDK_INT >= 31 && content.isActivity) ||
                context.packageManager.getLaunchIntentForPackage(n.packageName) != null,
            markReadAction(n) != null)
    }
    private fun markReadAction(n: StatusBarNotification): Notification.Action? =
        if (Build.VERSION.SDK_INT < 28) null else n.notification.actions?.firstOrNull {
            it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ && it.remoteInputs.isNullOrEmpty() &&
                it.actionIntent?.creatorPackage == n.packageName
        }
    private fun publish() { displayed.value = items.values.sortedByDescending { it.time } }

    /** Only explicit taps inside the unlocked Harness UI can open a source or use its declared mark-read action. */
    @Synchronized fun useNotice(activityContext: Context, id: String, markRead: Boolean): Boolean {
        if (!enabledNotificationAccess() || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val entry = items.entries.find { it.value.id == id } ?: return false
        val current = try { listener?.getActiveNotifications(arrayOf(entry.key))?.firstOrNull() }
            catch (_: SecurityException) { null } ?: return false
        if (current.packageName != entry.value.app || current.postTime != entry.value.time || !eligible(current)) return false
        return try {
            if (markRead) {
                (markReadAction(current)?.actionIntent ?: return false).send()
            } else {
                val open = current.notification.contentIntent
                if (Build.VERSION.SDK_INT >= 31 && open != null && open.creatorPackage == current.packageName && open.isActivity) open.send()
                else activityContext.startActivity(context.packageManager.getLaunchIntentForPackage(current.packageName) ?: return false)
            }
            true
        } catch (_: PendingIntent.CanceledException) { false }
        catch (_: SecurityException) { false }
        catch (_: ActivityNotFoundException) { false }
    }

    @Synchronized private fun foreground(app: String) {
        if (edges.enter(app) && allowed(app, PhoneEventKind.FOREGROUND)) fire(app, PhoneEventKind.FOREGROUND)
    }
    @Synchronized private fun lostWindow() { edges.resetForeground(); if (!enabledWindowAccess()) clearPending(PhoneEventKind.FOREGROUND) }
    private fun fire(app: String, kind: PhoneEventKind) {
        if (kind == PhoneEventKind.NOTIFICATION && !enabledNotificationAccess()) return
        val before = state.value
        val after = before.accept(app, kind, System.currentTimeMillis())
        if (after === before) return
        save(after)
        val rule = after.rules.first { it.app == app && it.kind == kind }
        if (!AppNotifications.enabled(context) || !manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.phone_events_title), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).setAction(OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reminder = after.pending.last()
        val dismissUri = Uri.Builder().scheme("harness-event").authority("dismiss")
            .appendPath(reminder.ruleId).appendPath(reminder.time.toString()).build()
        val delete = PendingIntent.getBroadcast(context, 0, Intent(context, PhoneEventDismissReceiver::class.java)
            .setData(dismissUri), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            AppNotifications.post(context, ID, Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(context.getString(R.string.phone_event_ready))
                .setContentText(context.getString(R.string.phone_event_open_reminder, label(app))).setContentIntent(open)
                .setDeleteIntent(delete).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_SECRET).build(), rule.id)
        } catch (_: SecurityException) { }
    }
    fun label(pkg: String) = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString()
    } catch (_: PackageManager.NameNotFoundException) { pkg }

    companion object {
        const val OPEN = "com.androidharness.app.OPEN_PHONE_EVENTS"
        private const val CHANNEL = "phone-event-reminders"
        private const val ID = 62409
        @Volatile private var instance: PhoneEvents? = null
        fun get(context: Context): PhoneEvents = instance ?: synchronized(this) {
            instance ?: PhoneEvents(context.applicationContext).also { instance = it }
        }

        /** App identity only. A window list change can arrive after the activity state event. */
        fun windowEvent(service: AccessibilityService, event: AccessibilityEvent?) {
            val type = event?.eventType ?: return
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
            val owner = get(service)
            if (!owner.policy.value.enabled || owner.policy.value.rules.none { it.kind == PhoneEventKind.FOREGROUND }) return
            if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked ||
                !service.getSystemService(PowerManager::class.java).isInteractive) return
            val active = service.windows.singleOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive } ?: return
            val pkg = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.windowId == active.id)
                event.packageName?.toString() else active.root?.packageName?.toString()
            if (pkg.isNullOrBlank()) return
            owner.foreground(pkg)
        }

        fun accessibilityGone(context: Context) { get(context).lostWindow() }
    }
}

/** Swiping an alert away clears just that occurrence; a delayed callback cannot discard a newer reminder. */
class PhoneEventDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val parts = intent.data?.pathSegments ?: return
        if (parts.size != 2) return
        PhoneEvents.get(context).dismiss(EventReminder(parts[0], parts[1].toLongOrNull() ?: return))
    }
}

class PhoneNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); PhoneEvents.get(this).bind(this) }
    override fun onListenerDisconnected() { PhoneEvents.get(this).disconnected(this); super.onListenerDisconnected() }
    override fun onDestroy() { PhoneEvents.get(this).disconnected(this); super.onDestroy() }
    override fun onNotificationPosted(n: StatusBarNotification) { PhoneEvents.get(this).posted(this,n) }
    override fun onNotificationRemoved(n: StatusBarNotification) { PhoneEvents.get(this).removed(this,n.key) }
}
