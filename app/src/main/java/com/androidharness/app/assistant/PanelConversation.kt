package com.androidharness.app.assistant

import android.app.KeyguardManager
import android.os.PowerManager
import com.androidharness.app.AppContainer
import com.androidharness.app.androiduse.PageActions
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** The submitted turn belongs to the app. Android can discard or replace just its voice window. */
internal class PanelConversation(private val c: AppContainer) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var window: HarnessAssistantSession? = null
    private var shown = false
    private var inspecting = false
    private var returnAttempt = 0L
    var task: PhonePanelTask? = null
        private set
    val controller = AssistantController(c.appContext, c, ::bindPhone, ::finishPhone,
        onPhonePrompt = { task?.takeUnless { it.backgrounded || window == null }?.requestReturn() },
        pageSourceFor = { window?.currentPageSource(it) })

    init {
        scope.launch {
            controller.state.collect { state ->
                island()?.update(state)
                if (!state.busy) releaseIfUnused()
            }
        }
    }

    private fun island() = task?.let { t -> PhonePanel.progress?.takeIf { it.token == t.returnToken } }
    fun owns(w: HarnessAssistantSession) = window === w
    val inFlight get() = task != null || controller.state.value.busy

    fun attach(w: HarnessAssistantSession) {
        window?.takeUnless { it === w }?.let(::detachWindow)
        window = w
        bindPage()
    }

    private fun bindPage() {
        val w = window ?: return
        val t = task ?: return
        PageActions.bind(t.sid, w.pageSource) { task === t && window === w && c.androidUse.isActive(t.sid) }
    }

    private fun bindPhone(sid: String, onDemand: Boolean) {
        // A new turn may start while the previous result is still loading its final history rows.
        task?.let { old -> island()?.dismiss(); old.close() }
        val token = UUID.randomUUID().toString()
        val t = PhonePanelTask(sid, token, { window?.hide() }, {
            val attempt = ++returnAttempt
            val unlocked = c.appContext.getSystemService(PowerManager::class.java).isInteractive &&
                !c.appContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            val ok = unlocked && (window?.showPhone(token) ?: serviceReturn?.invoke(token) ?: false)
            if (!ok) unavailable(token)
            else scope.launch { delay(4_000); if (returnAttempt == attempt) unavailable(token) }
        }, release = {
            PhonePanel.unbind(sid)
            window?.releasePage(sid)
            task = null
            releaseIfUnused()
        }, loseWindow = {
            // Never treat a missing hide callback as a clear target. Revoke the device lease;
            // RunManager still owns delivery of any explanation from this turn.
            c.androidUse.end(sid)
        })
        task = t
        if (!shown) t.onHidden()
        fun startStatus() {
            val status = PhoneTaskIsland(c.appContext, sid, token, t::requestReturn,
                hideChat = { if (task === t) scope.launch { t.awaitHidden { c.androidUse.isActive(sid) } } },
                stopTask = { if (task === t) controller.stop() })
            PhonePanel.attach(status)
            status.setPanelShown(shown)
            if (inspecting && shown) status.inspectInPanel()
        }
        if (!onDemand) startStatus()
        PhonePanel.bind(sid, if (onDemand) ::startStatus else null) { t.awaitHidden { c.androidUse.isActive(sid) } }
        bindPage()
    }

    private fun unavailable(token: String) {
        val t = task?.takeIf { it.returnToken == token && it.returning } ?: return
        island()?.returnUnavailable()
        t.returnUnavailable()
    }

    private fun finishPhone(sid: String) {
        val t = task?.takeIf { it.sid == sid } ?: return
        val status = island()?.takeIf { it.active }
        if (status == null) { t.finish(); return }
        scope.launch {
            val run = c.runManager.live(sid).first { !it.running }
            val rows = c.sessions.messages(sid)
            if (task !== t) return@launch
            status.finish(c.runManager.controls.flow(sid).value.completed, run.cancelled, run.error != null, rows)
            t.finish(keepTargetVisible = status.hasResultSurface() || window == null)
        }
    }

    /** Returns true when this window is displaying the already-submitted turn. */
    fun show(token: String?, refresh: Boolean): Boolean {
        shown = true
        val t = task
        val reuse = refresh || t?.onShown(token) == true || inFlight
        PhonePanel.progress?.setPanelShown(true)
        return reuse
    }

    /** Gate future device input before Android adds the newly summoned window to the display. */
    fun inspect() {
        inspecting = inFlight
        if (inspecting) { task?.inspect(); island()?.inspectInPanel() }
    }

    fun hide(w: HarnessAssistantSession) {
        // Android commonly destroys an already-hidden window while a replacement is returning.
        // Its teardown is not a second dismissal of the replacement.
        if (!owns(w) || (!shown && !inspecting)) return
        shown = false
        inspecting = false
        controller.panelHidden()
        controller.quiet()
        task?.onHidden()
        PhonePanel.progress?.panelLeft()
    }

    private fun detachWindow(w: HarnessAssistantSession) {
        hide(w)
        task?.let { w.releasePage(it.sid) }
        window = null
        c.runtimePolicy.setOverlayShown(false)
    }

    fun detach(w: HarnessAssistantSession) {
        if (!owns(w)) return
        detachWindow(w)
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (window != null || inFlight || c.panelConversation !== this) return
        c.panelConversation = null
        controller.close()
        scope.cancel()
    }

    companion object {
        // Available only while Android owns the current default assistant service.
        var serviceReturn: ((String) -> Boolean)? = null
    }
}
