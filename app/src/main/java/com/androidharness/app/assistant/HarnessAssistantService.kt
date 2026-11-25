package com.androidharness.app.assistant

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.assistant.edge.AssistantEdgeHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Bound by Android while AndroidHarness is the chosen digital assistant app. */
class HarnessAssistantService : VoiceInteractionService() {
    private var edgeScope: CoroutineScope? = null
    private var permissionWatcher: AppOpsManager.OnOpChangedListener? = null
    private var edgeHandle: AssistantEdgeHandle? = null
    private var destroyed = false
    private val returnToTask: (String) -> Boolean = { token ->
        try { showSession(HarnessAssistantSession.returnArgs(token), 0); true }
        catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
    }

    override fun onReady() {
        super.onReady()
        if (destroyed || edgeScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        PanelConversation.serviceReturn = returnToTask
        edgeScope = scope
        val container = (application as HarnessApp).container
        val permitted = MutableStateFlow(Settings.canDrawOverlays(this))
        val appOps = getSystemService(AppOpsManager::class.java)
        val watcher = AppOpsManager.OnOpChangedListener { _, _ ->
            permitted.value = Settings.canDrawOverlays(this)
        }
        appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, watcher)
        permissionWatcher = watcher
        permitted.value = Settings.canDrawOverlays(this)

        // The handle itself does not make the app foreground or request screen context.
        val requested = container.settings.settings.map { it.assistantEdgeHandleEnabled }.distinctUntilChanged()
        val hideHandle = combine(container.runtimePolicy.assistantPanelVisible, PhonePanel.suppressEdge) { shown, reserved -> shown || reserved }
        scope.launch {
            val foreground = container.runtimePolicy.state.map { it.visible }.distinctUntilChanged()
            combine(requested, permitted, hideHandle, foreground) { enabled, allowed, hidden, _ ->
                enabled && allowed && !hidden
            }.collect { visible ->
                if (visible && edgeHandle == null)
                    edgeHandle = AssistantEdgeHandle(this@HarnessAssistantService, ::showFromEdge)
                edgeHandle?.setVisible(visible)
            }
        }
    }

    private fun showFromEdge() {
        if (!Settings.canDrawOverlays(this)) return
        try {
            showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        } catch (_: IllegalStateException) {
            showEdgeError()
        } catch (_: SecurityException) {
            showEdgeError()
        }
    }

    private fun showEdgeError() {
        Toast.makeText(ContextCompat.getContextForLanguage(this), R.string.assistant_edge_start_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onShutdown() {
        val c = (application as HarnessApp).container
        if (!VoiceInteractionService.isActiveService(this, ComponentName(this, HarnessAssistantService::class.java))) {
            c.panelConversation?.task?.sid?.let(c.androidUse::end)
        }
        stopEdgeHandle()
        super.onShutdown()
    }

    override fun onDestroy() {
        destroyed = true
        stopEdgeHandle()
        super.onDestroy()
    }

    private fun stopEdgeHandle() {
        if (PanelConversation.serviceReturn === returnToTask) PanelConversation.serviceReturn = null
        edgeScope?.cancel()
        edgeScope = null
        permissionWatcher?.let { getSystemService(AppOpsManager::class.java).stopWatchingMode(it) }
        permissionWatcher = null
        edgeHandle?.setVisible(false)
        edgeHandle = null
    }
}

/** Hands Android one floating panel per assistant call. */
class HarnessAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = HarnessAssistantSession(this)
}

/**
 * Picks the phone's real speech recognizer. Choosing AndroidHarness as the assistant can make
 * Android route recognition to [HarnessRecognitionService], which only declines, so the app's
 * own voice input asks another installed recognizer instead (Google's first).
 */
internal object SpeechRecognizers {
    fun create(context: Context): SpeechRecognizer {
        val default = runCatching {
            android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        }.getOrNull()?.let(ComponentName::unflattenFromString)
        if (default == null || default.packageName != context.packageName) return SpeechRecognizer.createSpeechRecognizer(context)
        val other = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
            .sortedByDescending { it.packageName.startsWith("com.google.") }
            .firstOrNull()
            ?: return SpeechRecognizer.createSpeechRecognizer(context)
        return SpeechRecognizer.createSpeechRecognizer(context, ComponentName(other.packageName, other.name))
    }
}

/**
 * Android only accepts an assistant that also names a speech recognizer. The panel is
 * text first, so this one turns every request down instead of pretending to listen.
 */
class HarnessRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}
