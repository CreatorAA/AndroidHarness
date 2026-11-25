package com.androidharness.app.assistant.edge

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Removes only a window created by this test; never stops the actual assistant. */
@RunWith(AndroidJUnit4::class)
class AssistantEdgeRecoveryDeviceTest {
    @Test fun systemDetachRestoresNewWindowButUserHideCancelsPendingRepair() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantEdgeRecovery") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val ins = InstrumentationRegistry.getInstrumentation()
        val context = ins.targetContext
        check(Settings.canDrawOverlays(context)) { "Allow Harness to display overlays on the test device." }
        lateinit var handle: AssistantEdgeHandle
        lateinit var existing: Set<View>
        fun installed(): View? = WindowInspector.getGlobalWindowViews().singleOrNull { view ->
            view !in existing && view.isAttachedToWindow &&
                (view.layoutParams as? WindowManager.LayoutParams)?.title == "AndroidHarnessAssistantEdge"
        }
        fun awaitHandle(previous: View? = null): View {
            val end = SystemClock.uptimeMillis() + 5_000
            var found: View? = null
            while (found == null && SystemClock.uptimeMillis() < end) {
                ins.runOnMainSync { found = installed()?.takeUnless { it === previous } }
                if (found == null) SystemClock.sleep(50)
            }
            return checkNotNull(found) { "Expected a fresh attached edge window" }
        }
        fun systemRemove(v: View) {
            v.context.getSystemService(WindowManager::class.java).removeViewImmediate(v)
        }
        ins.runOnMainSync {
            existing = WindowInspector.getGlobalWindowViews().toSet()
            handle = AssistantEdgeHandle(context) {}
            handle.setVisible(true)
        }
        try {
            val first = awaitHandle()
            ins.runOnMainSync { systemRemove(first) }
            val restored = awaitHandle(first)
            ins.runOnMainSync {
                systemRemove(restored)
                handle.setVisible(false)
            }
            SystemClock.sleep(700) // past the scheduled replacement
            ins.runOnMainSync { assertTrue("User hide must win over a pending repair", installed() == null) }
        } finally {
            ins.runOnMainSync { handle.setVisible(false) }
        }
    }
}
