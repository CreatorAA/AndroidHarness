package com.androidharness.app.assistant

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.androidharness.app.AgentService
import com.androidharness.app.AppNotifications
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import com.androidharness.app.androiduse.PhoneAccessibilityService
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** User-owned task status. No model-supplied strings, screen content or tool arguments go into the island. */
internal class PhoneTaskIsland(
    context: Context, val sid: String, val token: String,
    private val showChat: () -> Unit, private val hideChat: () -> Unit, private val stopTask: () -> Unit,
) {
    private val ctx = ContextCompat.getContextForLanguage(context.applicationContext)
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val paused = MutableStateFlow(false)
    private var input = 0
    private var panelVisible = true
    private var expanded = false
    private var chatOpen = false
    private var pendingChat = false
    private var fullChat = false
    private var terminal = false
    var active = true
        private set
    private var stopped = false
    private var badge: TaskIslandWindow? = null
    private var lastNotice = ""
    private val trail = ArrayDeque<String>()
    private var headline = ctx.getString(R.string.phone_island_planning)
    private var lastCall: String? = null
    private var turn: String? = null
    private var checked = false
    private var uncertain = false
    private var failed = false
    private var trialResult: Boolean? = null
    private var planId: String? = null
    private var plan = emptyList<Pair<String,String>>()
    private val id = 0x60701000 xor sid.hashCode()

    init {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.phone_island_title), NotificationManager.IMPORTANCE_LOW))
        trail.addLast(headline)
    }
    fun setPanelShown(shown: Boolean) { panelVisible = shown; render() }
    /** A human summons pauses further device input until they put the panel away or resume. */
    fun inspectInPanel() {
        if (!active) return
        fullChat = false
        pendingChat = false; paused.value = true; expanded = false; chatOpen = true
        PhonePanel.setReview(sid); render()
    }
    fun panelLeft() {
        panelVisible = false
        if (chatOpen) {
            chatOpen = false; pendingChat = false; paused.value = false
            PhonePanel.setReview(null)
        }
        render()
    }
    fun returnUnavailable() {
        if (!chatOpen) return
        // The human requested inspection. Do not silently resume input if Android denied a window.
        chatOpen = false; expanded = true; fullChat = true
        PhonePanel.setReview(null); render()
    }
    fun update(state: AssistantUiState) {
        if (!active || state.sessionId != sid) return
        val t = state.currentTurnId ?: return
        turn = t
        val calls = state.runningCalls
        val call = calls.lastOrNull()
        if (state.approval != null || state.question != null) {
            record(ctx.getString(R.string.phone_island_review))
        } else if (call != null && lastCall != call.id) {
            lastCall = call.id
            val kind = runCatching { Json.parseToJsonElement(call.argumentsJson).jsonObject["kind"]?.jsonPrimitive?.content }.getOrNull()
            val label = when {
                call.name == "todo_write" -> R.string.phone_island_planning
                call.name == "android_observe" || call.name == "android_capabilities" -> R.string.phone_island_looking
                kind == "find_on_home" -> R.string.phone_island_finding
                kind == "tap" -> R.string.phone_island_tap
                kind == "set_text" -> R.string.phone_island_type
                kind == "swipe" -> R.string.phone_island_swipe
                kind == "drag" -> R.string.phone_island_gesture
                else -> R.string.phone_island_action
            }
            record(ctx.getString(label))
        } else if (call == null && lastCall != null) {
            lastCall = null; record(ctx.getString(R.string.phone_island_check))
        }
        val rows = state.messages
        val planReceipt = rows.lastOrNull { it.turnId == t && it.role == Role.TOOL && it.toolName == "todo_write" && !it.isError }
        if (planReceipt?.toolCallId != null && planReceipt.toolCallId != planId) {
            val args = rows.asReversed().asSequence().filter { it.turnId == t }.flatMap { it.toolCalls.asSequence() }
                .firstOrNull { it.id == planReceipt.toolCallId }?.argumentsJson
            plan = runCatching {
                val raw = Json.parseToJsonElement(requireNotNull(args)).jsonObject.getValue("todos")
                val items = if (raw is JsonArray) raw else Json.parseToJsonElement(raw.jsonPrimitive.content).jsonArray
                items.map { item ->
                    val obj = item.jsonObject
                    obj.getValue("status").jsonPrimitive.content to obj.getValue("content").jsonPrimitive.content
                        .replace('\n', ' ').take(80)
                }
            }.getOrDefault(emptyList())
            planId = planReceipt.toolCallId
            render()
        }
        val last = rows.lastOrNull { it.turnId == t && it.role == Role.TOOL && it.toolName in ACTIONS } ?: return
        failed = last.isError
        val receipt = runCatching { Json.parseToJsonElement(last.text).jsonObject }.getOrNull()
        val status = (receipt?.get("status") as? JsonPrimitive)?.contentOrNull
        checked = status == "verified" && (receipt?.get("methods") == null) &&
            (receipt?.get("verifiedScope") as? JsonPrimitive)?.contentOrNull != "foreground" ||
            (status == "ran" && (receipt?.get("finalScreenChecked") as? JsonPrimitive)?.booleanOrNull == true)
        uncertain = status in setOf("dispatched_unverified", "outcome_unknown", "not_verified", "stopped")
    }
    fun step(current: Int, total: Int) {
        if (!active) return
        record(ctx.getString(R.string.phone_island_batch, current, total))
    }
    private fun record(text: String) {
        if (headline == text) return
        headline = text
        if (trail.size >= 5) trail.removeFirst()
        trail.addLast(text)
        render()
    }
    suspend fun awaitResume(): Boolean {
        paused.first { !it }; return active
    }
    suspend fun beforeInput() {
        check(awaitResume()) { "The phone task has ended." }
        input++
        try {
            badge?.awaitOffscreen()
            // A system summons can arrive during the WindowManager settle interval.
            check(awaitResume()) { "The phone task has ended." }
        } catch (e: Throwable) { afterInput(); throw e }
    }
    fun afterInput() {
        if (input > 0) input--
        if (pendingChat && input == 0 && active) { pendingChat = false; chat() } else render()
    }
    fun review() {
        if (!active || input != 0) return
        paused.value = true; expanded = true
        render()
    }
    fun chat() {
        if (!active || chatOpen) return
        if (fullChat) {
            runCatching { (PhoneAccessibilityService.current ?: ctx).startActivity(resultIntent()) }
            return
        }
        if (input != 0) { pendingChat = true; return }
        paused.value = true; expanded = false; chatOpen = true
        PhonePanel.setReview(sid); badge?.hide(); showChat(); render()
    }
    fun resume() {
        if (!active) return
        expanded = false
        if (chatOpen) { hideChat(); return } // panelLeft releases input after Android confirms the hide
        fullChat = false
        paused.value = false; render()
    }
    fun stop() {
        if (!active) return
        stopped = true; stopTask()
    }
    fun hasResultSurface(): Boolean = badge?.attached == true || (AppNotifications.enabled(ctx) &&
        nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true)
    fun trialResult(ok: Boolean, finalScreenChecked: Boolean) { trialResult = ok; checked = ok && finalScreenChecked; failed = !ok }
    fun finish(completed: Boolean, cancelled: Boolean, error: Boolean, rows: List<ChatMessage>) {
        if (terminal || !active) return
        // The last tool receipt can be persisted between the last live UI sample and task teardown.
        turn?.let { update(AssistantUiState(sessionId = sid, currentTurnId = it, messages = rows)) }
        active = false; terminal = true; expanded = false; chatOpen = false; paused.value = false
        PhonePanel.setReview(null)
        val state = when {
            stopped || (trialResult == null && cancelled) -> R.string.phone_island_stopped
            failed || (trialResult == null && (error || !completed)) -> R.string.phone_island_failed
            uncertain -> R.string.phone_island_review
            checked && plan.none { it.first != "completed" } -> R.string.phone_island_done
            else -> R.string.phone_island_ended
        }
        headline = ctx.getString(state); trail.addLast(headline)
        render()
        scope.launch { delay(4500); dismiss() }
    }
    private fun render() {
        val mayShow = !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            ctx.getSystemService(PowerManager::class.java).isInteractive
        val service = PhoneAccessibilityService.current
        if (!mayShow || input > 0 || chatOpen || !terminal && panelVisible || service == null) {
            badge?.hide()
        } else {
            val view = badge?.takeIf { it.host === service } ?: run {
                badge?.hide()
                TaskIslandWindow(service, ::review, ::resume, ::chat, ::stop, ::openResult, ::render).also { badge = it }
            }
            // Only the unlocked, explicitly expanded inspector shows task text. Notifications stay generic.
            val milestones = plan.take(4).map { (status, label) ->
                (when (status) { "completed" -> "✓ "; "in_progress" -> "› "; else -> "· " }) + label
            }
            view.show(headline, if (expanded && milestones.isNotEmpty()) milestones else trail.toList(), expanded, terminal, paused.value)
        }
        notifyStatus()
    }
    private fun notifyStatus() {
        if (!AppNotifications.enabled(ctx)) return
        val key = "$headline:$expanded:$chatOpen:$terminal:$fullChat"
        if (lastNotice == key) return
        lastNotice = key
        val review = PendingIntent.getBroadcast(ctx, 0, command("chat"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val full = PendingIntent.getActivity(ctx, id, resultIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.phone_island_title)).setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText(trail.joinToString("\n")))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(!terminal)
            .setContentIntent(if (terminal || fullChat) full else review).setAutoCancel(terminal)
        if (fullChat && !terminal) b.addAction(0, ctx.getString(R.string.phone_island_resume),
            PendingIntent.getBroadcast(ctx, 0, command("resume"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if (!terminal) b.addAction(0, ctx.getString(R.string.phone_island_stop),
            PendingIntent.getBroadcast(ctx, 0, command("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        AppNotifications.post(ctx, id, b.build())
    }
    private fun command(op: String) = Intent(ctx, PhoneTaskStatusReceiver::class.java)
        .setData(Uri.Builder().scheme("harness-task").authority(op).appendPath(token).build())
    private fun resultIntent() = Intent(ctx, MainActivity::class.java).putExtra(AgentService.EXTRA_SESSION_ID, sid)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    private fun openResult() {
        if (!terminal) return
        // A human tap on the accessibility window opens stored history; it never creates a lease.
        runCatching { (PhoneAccessibilityService.current ?: ctx).startActivity(resultIntent()) }.onSuccess { dismiss() }
    }
    fun dismiss() {
        if (!terminal) nm.cancel(id)
        active = false; paused.value = false; scope.cancel(); badge?.hide(); badge = null
        PhonePanel.detach(this)
    }
    companion object {
        private const val CHANNEL = "phone_task_progress"
        private val ACTIONS = setOf("android_action", "android_sequence", "autojs_run")
    }
}

/** Immutable, lease-specific notifications cannot control a later task in the same conversation. */
class PhoneTaskStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val task = PhonePanel.progress?.takeIf { it.active && it.token == uri.lastPathSegment } ?: return
        when (uri.host) { "stop" -> task.stop(); "chat" -> task.chat(); "resume" -> task.resume() }
    }
}
