package com.androidharness.app.ui.settings

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PhoneSpeechSettingsDeviceTest {
    @Test fun offlineTogglePersistsWithoutChangingTheSelectedProvider() {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture")=="true")
        val target = ins.targetContext
        val c = (target.applicationContext as HarnessApp).container
        val old = runBlocking { c.settings.settings.first() }
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val title = target.getString(R.string.voice_local_only)
        fun find(n:AccessibilityNodeInfo?,text:String):AccessibilityNodeInfo? {
            if (n==null) return null
            if(n.text?.toString()?.contains(text)==true) return n
            for(i in 0 until n.childCount) find(n.getChild(i),text)?.let { return it }
            return null
        }
        fun waitFor(text:String):AccessibilityNodeInfo {
            val end=SystemClock.uptimeMillis()+8_000
            do { find(ui.rootInActiveWindow,text)?.let { return it }; SystemClock.sleep(50) } while(SystemClock.uptimeMillis()<end)
            throw AssertionError("Missing speech setting: $text")
        }
        try {
            runBlocking { c.settings.setOfflineMandarinOnly(false) }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val prefs by c.settings.settings.collectAsState(initial=old)
                    val scope=rememberCoroutineScope()
                    HarnessTheme {
                        VoiceListeningCard(c,prefs,scope,emptyList(),emptySet(),VoiceEngineState(null,true,true),
                            VoiceActions({},{},{},{},{},{}))
                    }
                } }
                waitFor(target.getString(R.string.voice_system_first))
                var row:AccessibilityNodeInfo?=waitFor(title)
                while(row!=null && !row.isClickable) row=row.parent
                assertTrue(row?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                waitFor(target.getString(R.string.voice_local_only_on))
                val updated=runBlocking { c.settings.settings.first() }
                assertTrue(updated.offlineMandarinOnly); assertEquals(old.voiceEngine,updated.voiceEngine)
                ui.waitForIdle(350,2_000) // capture the final thumb position, not the toggle animation
                ui.takeScreenshot()?.let { shot ->
                    File(target.getExternalFilesDir(null),"offline-mandarin-setting.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }
                    shot.recycle()
                }
            }
        } finally { runBlocking { c.settings.setOfflineMandarinOnly(old.offlineMandarinOnly) } }
    }
}
