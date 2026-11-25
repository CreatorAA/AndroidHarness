package com.androidharness.app.assistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.androidharness.app.R
import com.androidharness.app.androiduse.PhoneAccessibilityService
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A small trusted accessibility window, never an Activity or a system-alert permission. */
internal class TaskIslandWindow(
    val host: PhoneAccessibilityService, private val expand: () -> Unit, private val resume: () -> Unit,
    private val chat: () -> Unit, private val stop: () -> Unit, private val close: () -> Unit,
    onWindowLost: () -> Unit,
) {
    private companion object { const val WINDOW_TITLE = "Harness task status" }
    private var detachedAt: Long? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastRepair: Long? = null
    private val repair = Runnable {
        lastRepair = SystemClock.uptimeMillis()
        onWindowLost()
    }
    private val ctx = ContextCompat.getContextForLanguage(host)
    private val wm = host.getSystemService(WindowManager::class.java)
    private val dark = Color.rgb(30, 32, 36)
    private val mint = Color.rgb(174, 232, 201)
    private val root = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = dp(4).toFloat()
        background = GradientDrawable().apply { setColor(dark); cornerRadius = dp(25).toFloat() }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.RIGHT
        x = dp(12); y = dp(32)
        windowAnimations = 0
        // The system lays this window below bars and out of the cutout area.
        title = WINDOW_TITLE
    }
    var attached = false
        private set
    init {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (!attached) return // hide() already released ownership
                attached = false
                val now = SystemClock.uptimeMillis()
                detachedAt = now
                // Reconsider chat visibility, lock state and the current service after WM releases
                // the old root. If a cleaner immediately removes the replacement, wait for an
                // actual task/panel event rather than fighting the system in a re-add loop.
                if (lastRepair?.let { now - it < 1_500L } != true) main.post(repair)
            }
        })
    }
    private var model = ""
    private var expandedLast = false
    private fun dp(value: Int) = (value * host.resources.displayMetrics.density).roundToInt()
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(host).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
    }
    fun show(title: String, trail: List<String>, expanded: Boolean, terminal: Boolean, paused: Boolean) {
        val screen = host.resources.displayMetrics
        val cardWidth = minOf(dp(292), (screen.widthPixels - dp(24)).coerceAtLeast(dp(80)))
        val key = "$title:$expanded:$terminal:$paused:${screen.widthPixels}:" + if (expanded) trail.joinToString() else ""
        if (model != key) {
            if (expandedLast != expanded) {
                // WRAP_CONTENT can grow at the old surface origin before WindowManager reanchors
                // it, leaving the rightmost action outside the display. Publish a new sized surface.
                hide()
                lp.width = if (expanded) cardWidth else WindowManager.LayoutParams.WRAP_CONTENT
                if (expanded) {
                    lp.x = lp.x.coerceIn(dp(12), (screen.widthPixels - cardWidth - dp(12)).coerceAtLeast(dp(12)))
                    lp.y = lp.y.coerceIn(dp(16), (screen.heightPixels - dp(295)).coerceAtLeast(dp(16)))
                }
                expandedLast = expanded
            }
            model = key
            root.removeAllViews()
            root.setPadding(dp(if (expanded) 16 else 12), dp(if (expanded) 10 else 0), dp(if (expanded) 16 else 12), dp(if(expanded) 5 else 0))
            val row = LinearLayout(host).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(if (expanded) 32 else 40)
                val mark = View(host).apply { background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(if (terminal) Color.WHITE else mint)
                } }
                addView(mark, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
                addView(text(if (paused) ctx.getString(R.string.phone_island_paused) else title, 14f, Color.WHITE, true)
                    .apply { maxWidth = dp(210); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
            }
            root.addView(row)
            row.contentDescription = ctx.getString(R.string.phone_island_details, title)
            row.setOnClickListener { if (terminal) close() else if (expanded) resume() else expand() }
            touch(row)
            if (expanded) {
                val lines = trail.takeLast(4)
                lines.forEachIndexed { i, s -> root.addView(text(s, 13f, if (i==lines.lastIndex) Color.WHITE else Color.LTGRAY)
                    .apply { maxWidth = dp(246); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, dp(3)) }) }
                fun nextRow() = LinearLayout(host).apply { gravity = Gravity.CENTER_VERTICAL; root.addView(this) }
                var actions = nextRow()
                var used = 0
                listOf(Triple(R.string.phone_island_resume, mint, resume), Triple(R.string.phone_island_chat, Color.WHITE, chat),
                    Triple(R.string.phone_island_stop, Color.rgb(255, 185, 174), stop)).forEach { (label, color, action) ->
                    val button = text(ctx.getString(label), 13f, color, true).apply {
                        setPadding(dp(6), 0, dp(6), 0); minimumHeight = dp(48); setOnClickListener { action() }
                    }
                    button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    if (used > 0 && used + button.measuredWidth > cardWidth - dp(32)) { actions = nextRow(); used = 0 }
                    used += button.measuredWidth
                    actions.addView(button)
                }
            }
        }
        if (!attached) attached = runCatching { wm.addView(root, lp); true }.getOrDefault(false)
    }
    private fun touch(v: View) {
        var dx = 0f; var dy = 0f; var startX = 0; var startY = 0; var drag = false
        val slop = ViewConfiguration.get(host).scaledTouchSlop
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; startX = lp.x; startY = lp.y; drag = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX-dx) + abs(e.rawY-dy) > slop) drag = true
                    if (drag && attached) {
                        val metrics = host.resources.displayMetrics
                        lp.x = (startX - (e.rawX-dx).roundToInt()).coerceIn(0, (metrics.widthPixels-root.width).coerceAtLeast(0))
                        lp.y = (startY + (e.rawY-dy).roundToInt()).coerceIn(0, (metrics.heightPixels-root.height-dp(48)).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(root, lp) }
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!drag) view.performClick(); true }
                else -> true
            }
        }
    }
    fun hide() {
        main.removeCallbacks(repair)
        lastRepair = null
        if (attached) {
            attached = false
            runCatching { wm.removeViewImmediate(root) }
            detachedAt = SystemClock.uptimeMillis()
        }
    }

    /** Removing the View does not mean WindowManager has published its new window list.
     * A review-to-run handoff may already have detached the island before this call. */
    suspend fun awaitOffscreen() {
        hide()
        val removed = detachedAt ?: return
        val settle = removed + host.serviceInfo.notificationTimeout.coerceIn(0, 250) + 16 - SystemClock.uptimeMillis()
        if (settle > 0) delay(settle)
        val clear = withTimeoutOrNull(1100) {
            while (host.windows.any { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.title?.toString() == WINDOW_TITLE }) {
                if (Build.VERSION.SDK_INT >= 33) host.clearCache()
                delay(24)
            }
            true
        } == true
        check(clear) { "The task status window has not left the target. No input was sent." }
        detachedAt = null
    }
}
