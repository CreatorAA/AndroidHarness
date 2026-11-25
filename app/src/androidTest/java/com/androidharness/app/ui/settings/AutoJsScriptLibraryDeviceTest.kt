package com.androidharness.app.ui.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutoJsScriptLibraryDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ui by lazy { instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }

    @Test fun validateSaveAndDisableWithoutRunning() {
        assumeTrue("Dedicated test device only", InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true")
        val target = instrumentation.targetContext
        val container = (target.applicationContext as HarnessApp).container
        val phone = container.androidUse
        val scripts = phone.scripts
        val before = container.autoJsScriptDrafts.pending.value
        assumeTrue("Leave pending user work untouched", before == null && phone.activeSession.value == null)
        val name = "UI test " + UUID.randomUUID().toString().take(8)
        val source = """text("Continue").waitFor();"""
        val originalFlags = ui.serviceInfo.flags
        try {
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent { HarnessTheme { AutoJsScriptLibrary(container) {} } }
                }
                if (scripts.entries.value.isEmpty()) node("empty catalog") { it.text?.toString() == target.getString(R.string.autojs_empty) }
                click("autojs_new")
                enter("autojs_name", name)
                enter("autojs_source", "unsupported();")
                click("autojs_check")
                node("invalid line") { it.text?.toString()?.startsWith("Line 1:") == true }
                assertFalse(tag("autojs_save").isEnabled)
                enter("autojs_source", source)
                click("autojs_check")
                node("one-step preview") { it.text?.toString() == target.getString(R.string.autojs_preview, 1) }
                assertNull(phone.activeSession.value)
                assertSame(before, container.autoJsScriptDrafts.pending.value)
                val bitmap = requireNotNull(ui.takeScreenshot())
                try {
                    target.cacheDir.resolve("autojs-library-editor.png").outputStream().use {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                } finally { bitmap.recycle() }
                click("autojs_save")
                val toggle = tag("autojs_toggle_" + name)
                assertEquals(source, scripts.get(name)?.source)
                assertEquals(true, scripts.get(name)?.enabled)
                assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                node("script disabled") { it.viewIdResourceName?.substringAfterLast('/') == "autojs_toggle_" + name && !it.isChecked }
                assertEquals(false, scripts.get(name)?.enabled)
                assertNull(phone.activeSession.value)
                assertSame(before, container.autoJsScriptDrafts.pending.value)
            }
        } finally {
            ui.serviceInfo = ui.serviceInfo.apply { flags = originalFlags }
            if (scripts.get(name) != null) runBlocking { scripts.delete(name) }
        }
    }

    private fun tag(id: String) = node(id) { it.viewIdResourceName?.substringAfterLast('/') == id }
    private fun click(id: String) = assertTrue(id, tag(id).performAction(AccessibilityNodeInfo.ACTION_CLICK))
    private fun enter(id: String, value: String) {
        val field = requireNotNull(find(tag(id)) { n -> n.isEditable }) { "No text field: " + id }
        assertTrue(id, field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        node("edited " + id) { it.isEditable && it.text?.contains(value) == true }
    }
    private fun node(label: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 6_000
        do {
            instrumentation.waitForIdleSync()
            find(ui.rootInActiveWindow, matches)?.let { return it }
            SystemClock.sleep(70)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("Missing " + label)
    }
    private fun find(n: AccessibilityNodeInfo?, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (n == null) return null
        if (matches(n)) return n
        for (i in 0 until n.childCount) find(n.getChild(i), matches)?.let { return it }
        return null
    }
}
