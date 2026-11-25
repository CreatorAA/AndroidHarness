package com.androidharness.app.androiduse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.Display
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.androidharness.app.HarnessApp
import com.androidharness.app.phoneevents.PhoneEvents
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Bound only by Android after the user enables this service in system settings. */
class PhoneAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        PhoneEvents.windowEvent(this, event)
    }

    override fun onInterrupt() {
        interruptTask()
    }

    override fun onDestroy() {
        PhoneEvents.accessibilityGone(this)
        if (current === this) {
            current = null
            interruptTask()
        }
        super.onDestroy()
    }

    private fun interruptTask() {
        val app = application as? HarnessApp ?: return
        val session = app.container.androidUse
        session.builtinInterrupted()
    }

    companion object {
        @Volatile
        internal var current: PhoneAccessibilityService? = null
            private set
    }
}

@Suppress("DEPRECATION")
class BuiltinPhoneDriver(private val context: Context) : PhoneDriver {
    override val label = "Android accessibility"
    @Volatile private var closed = false
    private var secureWindow: Int? = null

    private fun service(): PhoneAccessibilityService {
        check(!closed) { "Phone task stopped." }
        return PhoneAccessibilityService.current
            ?: error("Enable Harness phone controls in Android Accessibility settings.")
    }

    override suspend fun capabilities(): Set<String> {
        service()
        return buildSet {
            addAll(
                listOf(
                    "observe", "scroll_forward", "scroll_backward",
                    "nodes",
                    "tap",
                    "long_press",
                    "swipe",
                    "set_text",
                    "back",
                    "home",
                    "launch",
                )
            )
            if (Build.VERSION.SDK_INT >= 30) { add("image"); add("drag") }
        }
    }

    private data class Page(
        val frame: PhoneFrame,
        val root: AccessibilityNodeInfo,
        val bounds: Rect,
        val blocked: List<Rect>,
        val displayMasks: List<Rect>,
    )

    private class WindowTransition(detail: String) : IllegalStateException(detail)

    /** VoiceInteractionSession.onHide may precede Android publishing the app's accessibility root. */
    private suspend fun foregroundPage(s: PhoneAccessibilityService, allowed: Set<String>?, withHitMap: Boolean = false): Page {
        val deadline = SystemClock.elapsedRealtime() + 1800L
        var cleared = false
        while (true) {
            currentCoroutineContext().ensureActive()
            check(service() === s) { "Phone service changed. Start a new observation." }
            try {
                return page(s, allowed, withHitMap)
            } catch (missing: WindowTransition) {
                if (SystemClock.elapsedRealtime() >= deadline) throw missing
                if (!cleared && Build.VERSION.SDK_INT >= 33) {
                    s.clearCache()
                    cleared = true
                }
                delay(60)
            }
        }
    }

    private fun page(s: PhoneAccessibilityService, allowed: Set<String>?, withHitMap: Boolean): Page {
        check(
            context.getSystemService(PowerManager::class.java).isInteractive &&
                !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        ) {
            "Unlock the phone to continue."
        }
        val display =
            context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: error("No interactive default display.")
        val size = Point().also { display.getRealSize(it) }
        if (Build.VERSION.SDK_INT >= 30) {
            val displays = s.windowsOnAllDisplays
            check(
                (0 until displays.size()).none { i ->
                    displays.keyAt(i) != Display.DEFAULT_DISPLAY &&
                        displays.valueAt(i).any {
                            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
                        }
                }
            ) {
                "Use one display for phone controls."
            }
        }
        val windows = s.windows
        val root = s.rootInActiveWindow ?: throw WindowTransition("No readable foreground window.")
        if (root.windowId != secureWindow) secureWindow = null
        check(secureWindow == null) {
            "Android blocked this secure window. Complete this step yourself."
        }
        val pkg = root.packageName?.toString().orEmpty()
        val target = windows.firstOrNull { it.id == root.windowId }
        // After closing our approval panel Android can briefly return its old root or a root
        // from a different window inventory. Only wait here; never act through another window.
        if (pkg == context.packageName)
            throw WindowTransition("The assistant window has not left the foreground.")
        if (allowed != null && pkg !in allowed) throw UnexpectedPhoneApp()
        PhoneScope.requireApp(pkg, context.packageName)
        if (target == null)
            throw WindowTransition("The foreground window is changing (root ${root.windowId}, $pkg).")
        check(target.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
            "The foreground is not an application window (type ${target.type}, $pkg)."
        }
        if (!target.isFocused) {
            val focused = windows.firstOrNull { it.isFocused }
            if (focused == null || focused.root?.packageName?.toString() == context.packageName)
                throw WindowTransition("Waiting for the target app to regain focus.")
            error("Another window has focus. Return to the target app.")
        }
        val bounds = Rect().also(target::getBoundsInScreen)
        val apps = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.id != target.id
        }
        check(apps.none { it.root?.isVisibleToUser == true }) {
            "Close split-screen or the overlapping app first."
        }
        val visibleRegion = Region(bounds)
        val blocked = mutableListOf<Rect>()
        val displayMasks = mutableListOf<Rect>()
        for (above in windows.filter { it.id != target.id && it.layer > target.layer }) {
            val box = Rect().also(above::getBoundsInScreen)
            displayMasks += box
            // A system window can contain two disjoint edge strips. Its bounding RECT covers
            // the entire phone even though its actual interaction REGION leaves the app open.
            val region = if (Build.VERSION.SDK_INT >= 30) Region().also(above::getRegionInScreen) else Region(box)
            visibleRegion.op(region, Region.Op.DIFFERENCE)
            val pieces = RegionIterator(region)
            val part = Rect()
            while (pieces.next(part)) blocked += Rect(part)
            if (visibleRegion.isEmpty) {
                val detail = "Window ${above.id} (type ${above.type}, focused=${above.isFocused}, active=${above.isActive}) covers the target."
                if (!above.isFocused && !above.isActive) throw WindowTransition(detail)
                error(detail)
            }
        }
        val nodes = mutableListOf<PhoneNode>()
        val inspectedContainers = mutableSetOf<String>()
        val areas = if (withHitMap) mutableListOf<PhoneTouchArea>() else null
        var hitMapComplete = true
        fun Rect.box() = PhoneBox(left,top,right,bottom)
        var visits = 0
        fun walk(n: AccessibilityNodeInfo, path: String, depth: Int, inheritedTap: String?, listOwner: String?): Boolean {
            if (!n.isVisibleToUser) return true
            if (depth > 36 || ++visits > 1600) { hitMapComplete = false; return false }
            if (allowed != null && n.className?.toString()?.contains("AppWidgetHostView") == true) {
                hitMapComplete = false; return false
            }
            check(!n.isPassword) { "Private input is on screen. Please handle it yourself." }
            val r = Rect().also(n::getBoundsInScreen)
            val actualText = n.text
            val t = actualText?.take(220)?.toString().orEmpty()
            val d = n.contentDescription?.toString()?.take(220).orEmpty()
            val relevant = t.isNotBlank() || d.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable ||
                inheritedTap != null && PhoneImagePreviews.potential(n.className?.toString().orEmpty(), r.width(), r.height(), size.x, size.y)
            val retained = nodes.size < 260 && !r.isEmpty
            val collection = if (n.isScrollable || n.collectionInfo != null) path else listOwner
            val tapOwner = when {
                n.isEditable || n.isCheckable || n.isScrollable || !n.isEnabled -> null
                n.isClickable -> path.takeIf { retained }
                n.isLongClickable -> null
                else -> inheritedTap
            }
            if (areas != null && !r.isEmpty) areas += PhoneTouchArea(path,tapOwner,
                n.isClickable || n.isLongClickable || n.isEditable || n.isCheckable,r.box())
            if (relevant && retained)
                nodes +=
                    PhoneNode(
                        path,
                        t,
                        d,
                        n.isEditable && n.isEnabled,
                        n.isClickable && n.isEnabled,
                        r.left,
                        r.top,
                        r.right,
                        r.bottom,
                        n.className?.toString().orEmpty(),
                        n.viewIdResourceName.orEmpty(),
                        tapOwner,
                        collection, n.isScrollable, n.isCheckable,
                    ).readEditorValue(actualText)
            // Silence from an incomplete accessibility subtree cannot prove there are no other
            // actions in this card. Reject clipped labels and actions absent from our flat rows.
            var complete = (!relevant || retained) && (n.text?.length ?: 0) <= 220 &&
                (n.contentDescription?.length ?: 0) <= 220 && !n.isScrollable && !n.isCheckable &&
                !n.className.toString().contains("WebView", true) &&
                listOf(n.text, n.contentDescription).none { value ->
                    value is Spanned && value.getSpans(0, value.length, ClickableSpan::class.java).isNotEmpty()
                }
            for (i in 0 until n.childCount) {
                if (visits >= 1600) { hitMapComplete = false; complete = false; break }
                val child = n.getChild(i)
                if (child == null) hitMapComplete = false
                if (child == null || !walk(child, "$path.$i", depth + 1, tapOwner, collection)) complete = false
            }
            if (complete && n.isClickable && n.isEnabled && !n.isEditable) inspectedContainers += path
            return complete && !n.isLongClickable && !(n.isClickable || n.isEditable)
        }
        walk(root, "0", 0, null, null)
        val key =
            "${target.id}:${root.className}:${bounds.flattenToString()}:" +
                blocked.joinToString(";") { it.flattenToString() }
        return Page(
            PhoneFrame(pkg, key, size.x, size.y, display.rotation, nodes, inspectedContainers = inspectedContainers,
                touchMap = areas?.takeIf { hitMapComplete }?.let {
                    PhoneTouchMap(bounds.box(), (if (Build.VERSION.SDK_INT >= 34) blocked else displayMasks).map { mask -> mask.box() }, it)
                }),
            root,
            bounds,
            blocked,
            displayMasks,
        )
    }

    override suspend fun observe(includeImage: Boolean) = readPage(includeImage, null)
    override suspend fun observeInApps(allowed: Set<String>) = readPage(false, allowed)
    override suspend fun observeInApps(allowed: Set<String>, includeImage: Boolean) = readPage(includeImage, allowed)

    private suspend fun readPage(includeImage: Boolean, allowedPackages: Set<String>?): PhoneFrame =
        withContext(Dispatchers.Main.immediate) {
            val s = service()
            val before = foregroundPage(s, allowedPackages, withHitMap = includeImage)
            if (!includeImage || Build.VERSION.SDK_INT < 30) return@withContext before.frame
            var witnessedMap = before.frame.touchMap
            val image =
                try {
                    capture(s, before)
                } catch (_: ScreenshotBusy) {
                    delay(420)
                    val again = page(service(), allowedPackages, withHitMap = true)
                    check(
                        sameGeometry(before.frame, again.frame) &&
                            before.frame.nodes == again.frame.nodes
                    ) {
                        "Screen changed while waiting for a screenshot."
                    }
                    if (again.frame.touchMap != witnessedMap) witnessedMap = null
                    capture(s, again)
                }
            val after = page(service(), allowedPackages, withHitMap = true).frame
            check(sameGeometry(before.frame, after) && before.frame.nodes == after.nodes) {
                "Screen changed during capture. Observe again."
            }
            after.copy(image = image, touchMap = after.touchMap.takeIf { image != null && it == witnessedMap })
        }

    @TargetApi(30)
    private suspend fun capture(s: PhoneAccessibilityService, page: Page): PhoneImage? {
        if (Build.VERSION.SDK_INT < 34 && page.displayMasks.any { it.contains(page.bounds) }) return null
        val bitmap =
            suspendCancellableCoroutine<Bitmap?> { cont ->
                val callback =
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val b =
                                result.hardwareBuffer.use { buffer ->
                                    if (!cont.isActive) return
                                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                }
                            if (cont.isActive) cont.resume(b) else b?.recycle()
                        }

                        override fun onFailure(errorCode: Int) {
                            if (!cont.isActive) return
                            if (
                                Build.VERSION.SDK_INT >= 34 &&
                                    errorCode ==
                                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW
                            )
                                secureWindow = page.root.windowId
                            if (
                                errorCode ==
                                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                            )
                                cont.resumeWithException(ScreenshotBusy())
                            else
                                cont.resumeWithException(
                                    IllegalStateException(
                                        "Cannot capture this screen (Android error $errorCode)."
                                    )
                                )
                        }
                    }
                if (Build.VERSION.SDK_INT >= 34)
                    s.takeScreenshotOfWindow(page.root.windowId, s.mainExecutor, callback)
                else s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, callback)
            } ?: return null
        return try {
            withContext(Dispatchers.Default) {
                val w = page.frame.width
                val h = page.frame.height
                val whole = bitmap.width == w && bitmap.height == h
                if (
                    !whole &&
                        (bitmap.width != page.bounds.width() ||
                            bitmap.height != page.bounds.height())
                )
                    return@withContext null
                val scale = minOf(1f, 1568f / maxOf(w, h))
                val preview =
                    Bitmap.createBitmap(
                        maxOf(1, (w * scale).toInt()),
                        maxOf(1, (h * scale).toInt()),
                        Bitmap.Config.ARGB_8888,
                    )
                try {
                    val canvas = Canvas(preview)
                    canvas.drawColor(Color.BLACK)
                    canvas.scale(preview.width.toFloat() / w, preview.height.toFloat() / h)
                    // On Android 11–13 the screenshot is display-wide. Never attach pixels from
                    // the keyboard, system bars or another window above the authorized app.
                    canvas.clipRect(page.bounds)
                    (if (Build.VERSION.SDK_INT >= 34) page.blocked else page.displayMasks)
                        .forEach { canvas.clipOutRect(it) }
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        if (whole) Rect(0, 0, w, h) else page.bounds,
                        Paint(Paint.FILTER_BITMAP_FLAG),
                    )
                    val out = ByteArrayOutputStream()
                    check(preview.compress(Bitmap.CompressFormat.JPEG, 82, out))
                    PhoneImage(out.toByteArray(), "image/jpeg", preview.width, preview.height)
                } finally {
                    preview.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private class ScreenshotBusy :
        Exception("Android is limiting screenshots. Wait for the next observation.")

    private fun sameGeometry(a: PhoneFrame, b: PhoneFrame) =
        a.packageName == b.packageName &&
            a.windowKey == b.windowKey &&
            a.width == b.width &&
            a.height == b.height &&
            a.rotation == b.rotation &&
            a.displayId == b.displayId

    override suspend fun dispatch(frame: PhoneFrame, action: PhoneAction): String =
        withContext(Dispatchers.Main.immediate) {
            var handedOff = false
            try {
                val s = service()
                val now = foregroundPage(s, setOf(frame.packageName), withHitMap = action.sourcePoint != null)
                check(sameGeometry(frame, now.frame)) {
                    "The foreground window or geometry changed. Observe again."
                }
                if (action.tapEvidenceNode != null) check(PhoneTapTargets.stillCurrent(frame, now.frame, action)) {
                    "The action target or its page changed. Observe again."
                }
                val node =
                    action.node?.let { id ->
                        val old = frame.nodes.singleOrNull { it.id == id } ?: error("Unknown node.")
                        check(now.frame.nodes.singleOrNull { it.id == id } == old) {
                            "The node changed. Observe again."
                        }
                        var live = now.root
                        id.split('.').drop(1).forEach {
                            live = live.getChild(it.toInt()) ?: error("Node disappeared.")
                        }
                        check(!live.isPassword && live.isEnabled && live.isVisibleToUser)
                        live
                    }
                fun point(x: Int?, y: Int?): Point {
                    require(
                        x != null &&
                            y != null &&
                            now.bounds.contains(x, y) &&
                            now.blocked.none { it.contains(x, y) }
                    ) {
                        "Point is outside the target or obscured."
                    }
                    return Point(x, y)
                }
                currentCoroutineContext().ensureActive()
                when (action.kind) {
                    "scroll_forward", "scroll_backward" -> {
                        require(node != null && node.isScrollable) { "Use the current scrollable container." }
                        val direction = if (action.kind == "scroll_forward") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        check(node.actionList.any { it.id == direction }) { "No further scroll in that direction." }
                        handedOff = true
                        check(node.performAction(direction)) { "The container did not scroll." }
                    }
                    "set_text" -> {
                        require(node != null && node.isEditable && action.text != null)
                        handedOff = true
                        check(
                            node.performAction(
                                AccessibilityNodeInfo.ACTION_SET_TEXT,
                                Bundle().apply {
                                    putCharSequence(
                                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                        action.text,
                                    )
                                },
                            )
                        ) {
                            "The editor did not accept text."
                        }
                    }
                    "tap",
                    "long_press",
                    "swipe", "drag" ->
                        if (node != null) {
                            require(action.kind != "swipe" && action.kind != "drag")
                            handedOff = true
                            check(
                                node.performAction(
                                    if (action.kind == "tap") AccessibilityNodeInfo.ACTION_CLICK
                                    else AccessibilityNodeInfo.ACTION_LONG_CLICK
                                )
                            ) {
                                "The target did not accept this action."
                            }
                        } else {
                            check(frame.nodes == now.frame.nodes) {
                                "The visible controls changed. Observe again."
                            }
                            val from = point(action.x, action.y)
                            val to =
                                if (action.kind == "swipe" || action.kind == "drag") point(action.toX, action.toY) else from
                            handedOff = true
                            if (action.kind == "drag") {
                                require(from != to) { "A drag needs two different positions." }
                                heldDrag(s, from, to, action.durationMs.coerceIn(80, 1500)) {
                                    val fresh = foregroundPage(s, setOf(frame.packageName))
                                    check(fresh.frame.rotation == frame.rotation && fresh.bounds == now.bounds &&
                                        fresh.blocked.none { it.contains(to.x,to.y) || it.contains(from.x,from.y) }) {
                                        "The destination changed during the hold."
                                    }
                                }
                            } else gesture(
                                s,
                                from,
                                to,
                                when (action.kind) {
                                    "tap" -> 60
                                    "long_press" -> 650
                                    else -> action.durationMs.coerceIn(80, 1500)
                                },
                            )
                        }
                    "back",
                    "home" -> {
                        handedOff = true
                        check(
                            s.performGlobalAction(
                                if (action.kind == "back") AccessibilityService.GLOBAL_ACTION_BACK
                                else AccessibilityService.GLOBAL_ACTION_HOME
                            )
                        )
                    }
                    "launch" -> {
                        val pkg = requireNotNull(action.packageName)
                        PhoneScope.requireApp(pkg, context.packageName)
                        val intent =
                            context.packageManager.getLaunchIntentForPackage(pkg)
                                ?: error("App is unavailable or has no launcher activity.")
                        handedOff = true
                        s.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    else -> error("Unsupported phone action.")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (handedOff) throw failure else throw PhoneActionNotSent(failure)
            }
            "Dispatched ${action.kind}."
        }

    private suspend fun gesture(
        s: PhoneAccessibilityService,
        from: Point,
        to: Point,
        duration: Int,
    ) = sendStroke(s, GestureDescription.StrokeDescription(path(from, to), 0, duration.toLong()))

    private fun path(from: Point, to: Point) = Path().apply {
        moveTo(from.x.toFloat(), from.y.toFloat())
        if (from != to) lineTo(to.x.toFloat(), to.y.toFloat())
    }

    /** Android keeps the original finger down between strokes. A slow swipe doesn't do this. */
    private suspend fun heldDrag(s: PhoneAccessibilityService, from: Point, to: Point, duration: Int, beforeMove: suspend () -> Unit) {
        val hold = GestureDescription.StrokeDescription(path(from, from), 0, 1, true)
        var down = false
        try {
            sendStroke(s, hold) { down = true }
            // A stationary, continuing stroke can complete as soon as DOWN is injected.
            // Keep that pointer down for a real long press before asking Android to move it.
            delay(ViewConfiguration.getLongPressTimeout().toLong() + 120)
            beforeMove()
            sendStroke(s, hold.continueStroke(path(from,to), 0, duration.toLong(), false)) { down = false }
        } finally {
            // On cancellation release the held contact in place, never complete a cancelled move.
            if (down) runCatching { s.dispatchGesture(GestureDescription.Builder().addStroke(
                hold.continueStroke(path(from,from), 0, 1, false)).build(), null, null) }
        }
    }

    private suspend fun sendStroke(s: PhoneAccessibilityService, stroke: GestureDescription.StrokeDescription,
                                   accepted: () -> Unit = {}) =
        suspendCancellableCoroutine<Unit> { cont ->
            val desc =
                GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()
            val callback =
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive)
                            cont.resumeWithException(
                                IllegalStateException(
                                    "Gesture interrupted; observe before continuing."
                                )
                            )
                    }
                }
            if (s.dispatchGesture(desc, callback, null)) accepted()
            else if (cont.isActive)
                cont.resumeWithException(IllegalStateException("Gesture was not accepted."))
        }

    override fun close() {
        closed = true
    }
}

internal object PhoneScope {
    fun requireApp(pkg: String, own: String) {
        require(
            pkg.isNotBlank() &&
                pkg != own &&
                pkg !in
                    setOf(
                        "android",
                        "com.android.systemui",
                        "com.android.settings",
                        "com.android.permissioncontroller",
                        "com.google.android.permissioncontroller",
                        "com.android.packageinstaller",
                        "com.google.android.packageinstaller",
                    )
        ) {
            "System prompts, security settings and the assistant itself need your direct input."
        }
    }
}
