package com.androidharness.app.assistant.edge

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.androidharness.app.R
import kotlin.math.roundToInt

/** A single small, touchable window owned by the bound assistant service. */
internal class AssistantEdgeHandle(private val context: Context, private val onInvoke: () -> Unit) {
    private data class Window(val manager: WindowManager, val view: View)

    private var window: Window? = null
    private val main = Handler(Looper.getMainLooper())
    private var requested = false
    private var retries = 0
    private var lastAttempt = 0L
    private val attach = Runnable {
        if (requested && window == null && Settings.canDrawOverlays(context)) {
            lastAttempt = SystemClock.uptimeMillis()
            if (!tryAttach()) retry()
        }
    }
    private val placement = context.getSharedPreferences("assistant_edge_placement", Context.MODE_PRIVATE)

    @MainThread
    fun setVisible(visible: Boolean) {
        // An actual lifecycle, preference, or permission event can retry an exhausted attachment.
        main.removeCallbacks(attach)
        retries = 0
        requested = visible
        if (visible) attach.run() else removeWindow()
    }

    private fun retry() {
        if (!requested || retries >= 2) return
        main.removeCallbacks(attach)
        main.postDelayed(attach, if (retries++ == 0) 350L else 1_500L)
    }

    private fun tryAttach(): Boolean = try {
        installWindow()
    } catch (error: WindowManager.BadTokenException) {
        Log.w(TAG, "Could not attach the assistant edge handle", error)
        false
    } catch (error: WindowManager.InvalidDisplayException) {
        Log.w(TAG, "No display for the assistant edge handle", error)
        false
    } catch (error: SecurityException) {
        Log.w(TAG, "Overlay permission is no longer available", error)
        false
    }

    private fun installWindow(): Boolean {
        val windowContext = if (Build.VERSION.SDK_INT >= 30) {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY) ?: return false
            context.createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else context
        val manager = windowContext.getSystemService(WindowManager::class.java)
        val description = ContextCompat.getContextForLanguage(context)
            .getText(R.string.assistant_edge_handle_description)
        val view = HandleView(windowContext, description, onInvoke, placement.getFloat("height_fraction", 0.5f)) {
            placement.edit().putFloat("height_fraction", it).apply()
        }
        val params = WindowManager.LayoutParams(
            view.dp(44).roundToInt(),
            view.dp(64).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Physical right edge in all locales; an inset leaves the very edge available for Back.
            gravity = Gravity.RIGHT or Gravity.TOP
            x = view.dp(6).roundToInt()
            y = view.offset(manager, height)
            setTitle("AndroidHarnessAssistantEdge")
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                // Our own removal clears window *first*, including when the user disables the handle.
                if (window?.view !== v) return
                window = null
                if (SystemClock.uptimeMillis() - lastAttempt > 5_000L) retries = 0
                retry()
            }
        })
        manager.addView(view, params)
        window = Window(manager, view)
        return true
    }

    private fun removeWindow() {
        val attached = window ?: return
        window = null
        try {
            attached.manager.removeViewImmediate(attached.view)
        } catch (_: IllegalArgumentException) {
            // WindowManager can already have removed the view during display or permission changes.
        }
    }

    private class HandleView(
        context: Context,
        description: CharSequence,
        private val onInvoke: () -> Unit,
        private var heightFraction: Float,
        private val savePosition: (Float) -> Unit,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val swipe = AssistantEdgeSwipe(dp(48))
        private var dragOrigin = heightFraction

        @Suppress("DEPRECATION")
        private fun travel(manager: WindowManager, windowHeight: Int): Int {
            val available = if (Build.VERSION.SDK_INT >= 30) {
                val metrics = manager.currentWindowMetrics
                val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                metrics.bounds.height() - bars.top - bars.bottom
            } else resources.displayMetrics.heightPixels
            return (available - windowHeight).coerceAtLeast(1)
        }

        fun offset(manager: WindowManager, windowHeight: Int): Int =
            (heightFraction * travel(manager, windowHeight)).roundToInt()

        private fun updateOffset(delta: Float? = null) {
            val params = layoutParams as? WindowManager.LayoutParams ?: return
            if (!isAttachedToWindow) return
            val manager = context.getSystemService(WindowManager::class.java)
            if (delta != null) heightFraction = (dragOrigin + delta / travel(manager, params.height)).coerceIn(0f, 1f)
            val y = offset(manager, params.height)
            if (params.y != y) {
                params.y = y
                manager.updateViewLayout(this, params)
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            post { updateOffset() }
        }

        init {
            contentDescription = description
            // Exposes the same action to TalkBack and Switch Access. Ordinary taps do not open it.
            isClickable = true
            isSoundEffectsEnabled = false
        }

        fun dp(value: Int): Float = value * resources.displayMetrics.density

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (Build.VERSION.SDK_INT >= 29) {
                // A small exclusion at the handle still leaves the rest of the edge for Back.
                systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val thickness = dp(if (isPressed) 6 else 4)
            val right = width - dp(2)
            val left = right - thickness
            val top = (height - dp(52)) / 2f
            val bottom = height - top
            val outline = dp(1)
            paint.color = if (isPressed) 0xB0000000.toInt() else 0x83000000.toInt()
            canvas.drawRoundRect(left - outline, top - outline, right + outline, bottom + outline, thickness, thickness, paint)
            paint.color = if (isPressed) Color.WHITE else 0xD0FFFFFF.toInt()
            canvas.drawRoundRect(left, top, right, bottom, thickness / 2, thickness / 2, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipe.start(event.rawX, event.rawY)
                    dragOrigin = heightFraction
                    setTrackingAppearance(true)
                }
                MotionEvent.ACTION_MOVE -> swipe.verticalOffset(event.rawX, event.rawY)?.let { updateOffset(it) }
                MotionEvent.ACTION_UP -> {
                    swipe.verticalOffset(event.rawX, event.rawY)?.let { updateOffset(it); savePosition(heightFraction) }
                    val open = swipe.finish(event.rawX, event.rawY)
                    setTrackingAppearance(false)
                    if (open) performClick()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    if (swipe.repositioning) updateOffset(0f)
                    swipe.cancel()
                    setTrackingAppearance(false)
                }
            }
            return true
        }

        private fun setTrackingAppearance(active: Boolean) {
            isPressed = active
            invalidate()
        }

        override fun performClick(): Boolean {
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
            super.performClick()
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
                else HapticFeedbackConstants.VIRTUAL_KEY
            )
            onInvoke()
            return true
        }

        override fun onDetachedFromWindow() {
            swipe.cancel()
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val TAG = "AssistantEdgeHandle"
    }
}
