package com.androidharness.app.assistant

import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.androidharness.app.AgentService
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import com.androidharness.app.androiduse.*
import com.androidharness.app.ui.common.DialogHost
import com.androidharness.app.ui.common.LocalDialogHost
import com.androidharness.app.ui.common.RefreshRate
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject

/**
 * The floating panel Android shows when the user calls the assistant. The session window
 * is not an Activity, so it carries its own lifecycle, saved state and view model store
 * for Compose.
 */
internal class HarnessAssistantSession(context: Context) : VoiceInteractionSession(context),
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val container = (context.applicationContext as HarnessApp).container
    private val conversation = container.panelConversation ?: PanelConversation(container).also { container.panelConversation = it }
    private val controller get() = conversation.controller
    private val phoneTask get() = conversation.task
    private val main = Handler(Looper.getMainLooper())
    private val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val onMain = Executor { main.post(it) }
    private val pages = if (Build.VERSION.SDK_INT >= 29)
        SessionPageActions(this, onMain, ::pageUsable, ::revealForPage) else null
    private var stopPageWatch: (() -> Unit)? = null
    private var requestPageOnReturn = false
    private var returningPanel = false
    private var legacyPackage: String? = null
    fun currentPageSource(pkg: String): Any? = if (Build.VERSION.SDK_INT >= 29) pages?.sourceFor(pkg)
        else legacyPackage?.takeIf { it == pkg }
    val pageSource: PageActionSource = pages ?: object : PageActionSource {
        override suspend fun isCurrent(packageName: String, authorized: () -> Boolean) =
            authorized() && visible.value && legacyPackage == packageName && pageUsable(packageName)
        override suspend fun discover(packageName: String, authorized: () -> Boolean) = PageActionList("unavailable")
        override suspend fun perform(packageName: String, ref: String, arguments: JsonObject, authorized: () -> Boolean): PageActionReply =
            pageNotSent("Direct Actions require Android 10 or later.")
    }

    private fun pageUsable(pkg: String): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            runCatching { PhoneScope.requireApp(pkg, context.packageName) }.isSuccess

    private fun revealForPage() {
        val task = phoneTask ?: return
        if (task.backgrounded || !container.androidUse.isActive(task.sid)) return
        if (!visible.value) {
            requestPageOnReturn = true
            task.requestReturn()
        } else {
            // A normal approval/result return requests no screen data. Refresh only when a tool
            // actually needs to address the current ActivityId, never on every result delivery.
            try { show(Bundle().apply { putString(PAGE_REFRESH, task.returnToken) }, SHOW_WITH_ASSIST) }
            catch (_: IllegalStateException) { } catch (_: SecurityException) { }
        }
    }

    @RequiresApi(32)
    private fun watchForeground(): () -> Unit {
        val callback = object : VisibleActivityCallback {
            override fun onInvisible(activityId: ActivityId) { pages?.invisible(activityId) }
        }
        registerVisibleActivityCallback(onMain, callback)
        return { unregisterVisibleActivityCallback(callback) }
    }

    fun releasePage(sid: String) {
        PageActions.unbind(sid, pageSource)
        if (Build.VERSION.SDK_INT >= 29) pages?.endTask()
    }

    fun showPhone(token: String): Boolean {
        val flags = if (requestPageOnReturn) SHOW_WITH_ASSIST else 0
        requestPageOnReturn = false
        return try { show(returnArgs(token), flags); true }
        catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }
    }

    /** A new summons replays the entrance; a phone task returning to its panel keeps its draft and view. */
    private val showCount = mutableIntStateOf(0)
    /** False while Android has the panel hidden, so the microphone is let go. */
    private val visible = mutableStateOf(false)

    override fun onCreate() {
        conversation.attach(this)
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        super.onCreate()
        window.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            // The panel animates itself; the system fade would swallow the first part of it.
            w.setWindowAnimations(0)
            w.decorView.attachOwners()
        }
        keepWindowStill()
    }

    /**
     * Android pans this window above the keyboard on its own, and the panel already lifts
     * itself by the keyboard height, so the two together threw it halfway up the screen.
     * The window has to stay put so the panel alone follows the keyboard.
     *
     * "Adjust nothing" is the normal way to ask for that, but some phones put this window
     * back to "pan". Letting the window lay out without limits also turns the pan off
     * (Android then gives it no visible insets to pan into), and the panel still gets the
     * keyboard and system bar insets it lays itself out with.
     */
    private fun keepWindowStill() {
        val w = window.window ?: return
        val still = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        val mask = WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        val noLimits = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (w.attributes.softInputMode and mask != still) w.setSoftInputMode((w.attributes.softInputMode and mask.inv()) or still)
        if (w.attributes.flags and noLimits == 0) w.addFlags(noLimits)
        val root = w.peekDecorView() ?: return
        val params = root.layoutParams as? WindowManager.LayoutParams ?: return
        if (params.softInputMode and mask == still && params.flags and noLimits != 0) return
        params.softInputMode = (params.softInputMode and mask.inv()) or still
        params.flags = params.flags or noLimits
        if (root.isAttachedToWindow) runCatching { w.windowManager.updateViewLayout(root, params) }
    }

    /**
     * Without a stated rate this window gets the phone's "normal" rate, 60 Hz on many phones,
     * even on a 120 Hz screen, and every animation in the panel looked choppy next to the app
     * behind it. Ask for the fastest mode, the same as the app's own windows do.
     */
    private fun preferFastestRefresh(v: View) {
        val w = window.window ?: return
        val display = v.display ?: return
        val mode = runCatching { RefreshRate.fastestModeId(display) }.getOrDefault(0)
        if (mode == 0 || w.attributes.preferredDisplayModeId == mode) return
        w.attributes = w.attributes.apply { preferredDisplayModeId = mode }
    }

    private var panelView: ComposeView? = null

    override fun onCreateContentView(): View = ComposeView(ContextCompat.getContextForLanguage(context)).apply {
        panelView = this
        attachOwners()
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { keepWindowStill(); preferFastestRefresh(v); v.post { keepWindowStill() } }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        // Focusing the text field is the moment Android decides to pan, so check again then.
        viewTreeObserver.addOnGlobalFocusChangeListener { _, _ -> keepWindowStill() }
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@HarnessAssistantSession))
        // Dialogs opened from the answer (tool details, images, tables) attach to this window.
        val dialogs = DialogHost(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG) { windowToken }
        setContent {
            CompositionLocalProvider(LocalDialogHost provides dialogs) {
                AssistantPanelRoot(container, controller, showCount.intValue, visible.value, onDismiss = ::hide,
                    onContinue = ::continueInApp, onOpenUrl = ::openUrl,
                    onPageActions = { pkg ->
                        if (Build.VERSION.SDK_INT >= 29) pages!!.inspect(pkg) else PageActionList("unavailable")
                    })
            }
        }
    }

    private fun View.attachOwners() {
        setViewTreeLifecycleOwner(this@HarnessAssistantSession)
        setViewTreeSavedStateRegistryOwner(this@HarnessAssistantSession)
        setViewTreeViewModelStoreOwner(this@HarnessAssistantSession)
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        if (Build.VERSION.SDK_INT >= 29) pages?.reset(showing = true)
        legacyPackage = null
        returningPanel = conversation.inFlight || args?.getString(RETURN_TOKEN) != null
        if (conversation.owns(this) && args?.getString(RETURN_TOKEN) == null && args?.getString(PAGE_REFRESH) == null)
            conversation.inspect()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Android 12 and earlier keep the service alive when an Activity switches app language.
        if (Build.VERSION.SDK_INT < 33) panelView?.let { view ->
            if (view.resources.configuration.locales != ContextCompat.getContextForLanguage(context).resources.configuration.locales) {
                view.disposeComposition()
                setContentView(onCreateContentView())
            }
        }
        keepWindowStill()
        val token = args?.getString(RETURN_TOKEN)
        val refreshToken = args?.getString(PAGE_REFRESH)
        val refresh = refreshToken != null && refreshToken == phoneTask?.returnToken
        if (!conversation.owns(this)) return
        if ((token != null && (token != phoneTask?.returnToken || phoneTask?.returning != true)) ||
            (refreshToken != null && (!refresh || phoneTask?.backgrounded == true))) {
            hide() // an obsolete platform callback must not reopen a dismissed or completed task
            return
        }
        if (!conversation.show(token, refresh)) {
            returningPanel = false
            // SystemUI may request both context types even when the person turned both off.
            // Android need not deliver an empty callback for a disabled source.
            val shared = showFlags and userDisabledShowContext.inv()
            controller.begin(
                wantsText = shared and SHOW_WITH_ASSIST != 0,
                wantsShot = shared and SHOW_WITH_SCREENSHOT != 0,
            )
            showCount.intValue++
        }
        visible.value = true
        if (Build.VERSION.SDK_INT >= 32 && stopPageWatch == null) stopPageWatch = watchForeground()
        container.runtimePolicy.setOverlayShown(true)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onHide() {
        stopPageWatch?.invoke()
        stopPageWatch = null
        if (Build.VERSION.SDK_INT >= 29) pages?.reset(showing = false)
        legacyPackage = null
        visible.value = false
        if (conversation.owns(this)) {
            conversation.hide(this)
            container.runtimePolicy.setOverlayShown(false)
        }
        registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onCloseSystemDialogs() {
        // An assist key closes system dialogs before showing the assistant. Calling hide() on
        // the already-hidden session cancels that next show when Android reuses the session.
        if (visible.value) super.onCloseSystemDialogs()
    }

    override fun onDestroy() {
        stopPageWatch?.invoke()
        stopPageWatch = null
        if (Build.VERSION.SDK_INT >= 29) pages?.reset(showing = false)
        pageScope.cancel()
        conversation.detach(this)
        main.removeCallbacksAndMessages(null)
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        super.onDestroy()
    }

    @Deprecated("Android still calls this for the first assist result")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        // Null structure: the user turned off "Use text from screen" or the app is secure.
        if (Build.VERSION.SDK_INT < 29) legacyPackage = data?.getString(Intent.EXTRA_ASSIST_PACKAGE)
        if (returningPanel || !conversation.owns(this)) return
        controller.onAssist(structure, data?.getString(Intent.EXTRA_ASSIST_PACKAGE), content)
    }

    @RequiresApi(29)
    override fun onHandleAssist(state: AssistState) {
        if (state.isFocused) {
            val bridge = pages!!
            val pkg = state.assistData?.getString(Intent.EXTRA_ASSIST_PACKAGE)
            if (pkg != null) bridge.focus(state.activityId, pkg)
            else {
                // The activity component can require lazy IPC; don't walk screen structure on the UI thread.
                val epoch = bridge.generation
                pageScope.launch {
                    val component = withContext(Dispatchers.Default) {
                        runCatching { state.assistStructure?.activityComponent?.packageName }.getOrNull()
                    }
                    if (bridge.generation == epoch) bridge.focus(state.activityId, component)
                }
            }
        }
        // Dispatches to the existing primary legacy hook once, preserving ordinary screen sharing.
        super.onHandleAssist(state)
    }

    @RequiresApi(29)
    override fun onDirectActionsInvalidated(activityId: ActivityId) {
        pages?.invalidated(activityId)
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Null when the app is FLAG_SECURE or "Use screenshot" is off.
        if (!returningPanel && conversation.owns(this)) controller.onScreenshot(screenshot)
    }

    /** Web links from an answer open in the browser, over which the panel steps aside. */
    private fun openUrl(url: String) {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        if (uri?.scheme?.lowercase() !in setOf("http", "https")) return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching { startAssistantActivity(intent) }.isSuccess
        if (opened) hide() else Toast.makeText(ContextCompat.getContextForLanguage(context), R.string.assistant_open_failed, Toast.LENGTH_SHORT).show()
    }

    private fun continueInApp() {
        val sid = controller.state.value.sessionId
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { if (sid != null) putExtra(AgentService.EXTRA_SESSION_ID, sid) }
        // The visible panel lets this app start an activity; the assistant stack is only a fallback.
        val opened = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching { startAssistantActivity(intent) }.isSuccess
        if (opened) hide() else Toast.makeText(ContextCompat.getContextForLanguage(context), R.string.assistant_open_failed, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun returnArgs(token: String) = Bundle().apply { putString(RETURN_TOKEN, token) }
        private const val RETURN_TOKEN = "com.androidharness.assistant.RETURN_PHONE_TASK"
        private const val PAGE_REFRESH = "com.androidharness.assistant.REFRESH_PHONE_PAGE"
    }
}
