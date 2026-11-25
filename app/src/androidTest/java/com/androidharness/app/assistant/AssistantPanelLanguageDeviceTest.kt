package com.androidharness.app.assistant

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a test emulator with Harness selected as the system assistant. */
@RunWith(AndroidJUnit4::class)
class AssistantPanelLanguageDeviceTest {
    @Test fun assistantWindowUsesTheAppLanguageWhileTheSystemStaysEnglish() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val base = ins.targetContext.applicationContext
        val panel = (base as HarnessApp).container.runtimePolicy.assistantPanelVisible
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantLanguageFixture") == "true")
        assumeTrue(Build.VERSION.SDK_INT < 33 && base.resources.configuration.locales[0].language == "en")
        val previous = AppCompatDelegate.getApplicationLocales()
        val ui = ins.uiAutomation
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
            ui.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        fun contains(node: AccessibilityNodeInfo?, label: String): Boolean {
            node ?: return false
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return true
            return (0 until node.childCount).any { contains(node.getChild(it), label) }
        }
        val component = base.packageName + "/" + HarnessAssistantService::class.java.name
        assumeTrue(shell("settings get secure voice_interaction_service").trim() == component)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            fun select(tags: LocaleListCompat, language: String) {
                activity.onActivity { AppCompatDelegate.setApplicationLocales(tags) }
                val until = SystemClock.elapsedRealtime() + 10_000
                // The preference is written before AppCompat finishes recreating MainActivity.
                // Summoning the assistant during that transition lets the replacement Activity hide it.
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < until) {
                    activity.onActivity { ready = it.hasWindowFocus() && it.resources.configuration.locales[0].language == language }
                    ready = ready && ContextCompat.getContextForLanguage(base).resources.configuration.locales[0].language == language
                    if (!ready) SystemClock.sleep(100)
                }
                assertTrue("Main activity did not return in language: $language", ready)
            }

            try {
                // Starting instrumentation force-stops the target, including its bound voice service.
                shell("settings delete secure voice_interaction_service")
                shell("settings put secure voice_interaction_service " + component)
                val ready = SystemClock.elapsedRealtime() + 10_000
                while (!shell("dumpsys voiceinteraction").contains("mBound=true mService=android.service.voice.") &&
                    SystemClock.elapsedRealtime() < ready) SystemClock.sleep(100)

                for (tag in listOf("zh-CN", "de")) {
                    select(LocaleListCompat.forLanguageTags(tag), tag.substringBefore('-'))
                    assertEquals("en", base.resources.configuration.locales[0].language)
                    val label = ContextCompat.getContextForLanguage(base).getString(R.string.assistant_phone_action)
                    shell("input keyevent KEYCODE_ASSIST")
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (!(panel.value && contains(ui.rootInActiveWindow, label)) && SystemClock.elapsedRealtime() < deadline)
                        SystemClock.sleep(100)
                    assertTrue("Assistant window missing: $label; active window: " + ui.rootInActiveWindow?.className,
                        panel.value && contains(ui.rootInActiveWindow, label))
                    ins.sendStatus(0, Bundle().apply { putString("panelLanguage", "$tag: $label") })
                    // Back hides the first window for reuse. Home must still dismiss a visible session.
                    shell("input keyevent " + if (tag == "de") "KEYCODE_HOME" else "KEYCODE_BACK")
                    val hidden = SystemClock.elapsedRealtime() + 5_000
                    while (panel.value && SystemClock.elapsedRealtime() < hidden) SystemClock.sleep(100)
                    assertTrue("Assistant did not dismiss after $tag", !panel.value)
                }
            } finally {
                ins.runOnMainSync { AppCompatDelegate.setApplicationLocales(previous) }
            }
        }
    }
}
