package com.androidharness.app.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.R
import com.androidharness.app.MainActivity
import com.androidharness.app.AgentService
import com.androidharness.app.HarnessApp
import com.androidharness.app.AppNotifications
import com.androidharness.app.agent.withElapsedTime
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.androiduse.DragTimingProbeActivity
import com.androidharness.app.androiduse.PhoneAccessibilityService
import com.androidharness.app.androiduse.awaitFixturePhoneService
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneTaskIslandDeviceTest {
    @Test fun timedActionReceiptSelectsTheVerifiedOrReviewNotification(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ctx = ContextCompat.getContextForLanguage(InstrumentationRegistry.getInstrumentation().targetContext)
        val manager = ctx.getSystemService(NotificationManager::class.java)
        assumeTrue(AppNotifications.enabled(ctx) && manager.areNotificationsEnabled())
        for ((status, label) in listOf("verified" to R.string.phone_island_done,
            "dispatched_unverified" to R.string.phone_island_review)) {
            val sid = UUID.randomUUID().toString()
            val island = withContext(Dispatchers.Main) { PhoneTaskIsland(ctx, sid, sid, {}, {}, {}) }
            val id = 0x60701000 xor sid.hashCode()
            try {
                val receipt = ToolResult(true, """{"status":"$status"}""").withElapsedTime("android_action", 320)
                val rows = listOf(ChatMessage(Role.TOOL, receipt.output, toolName = "android_action", turnId = "turn"))
                withContext(Dispatchers.Main) {
                    island.update(AssistantUiState(sessionId = sid, currentTurnId = "turn", messages = rows))
                    island.finish(completed = true, cancelled = false, error = false, rows = rows)
                }
                val title = ctx.getString(label)
                withTimeout(4000) {
                    while (manager.activeNotifications.none { it.id == id &&
                        it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() == title }) delay(50)
                }
            } finally {
                withContext(Dispatchers.Main) { island.dismiss() }
                manager.cancel(id)
            }
        }
    }

    @Test fun systemRemovalRestoresOneIslandButHidingAndDismissalWin(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        val host = checkNotNull(PhoneAccessibilityService.current)
        val wm = host.getSystemService(WindowManager::class.java)
        val sid = UUID.randomUUID().toString()
        val island = PhoneTaskIsland(ins.targetContext, sid, sid, {}, {}, {})
        fun windows() = WindowInspector.getGlobalWindowViews().filter { view ->
            (view.layoutParams as? WindowManager.LayoutParams)?.title == "Harness task status" && view.isAttachedToWindow
        }
        suspend fun awaitOne() = withTimeout(4000) {
            while (withContext(Dispatchers.Main) { windows().isEmpty() }) delay(30)
            withContext(Dispatchers.Main) { windows().single() }
        }
        try {
            withContext(Dispatchers.Main) {
                check(PhonePanel.progress == null && windows().isEmpty())
                PhonePanel.attach(island)
                island.setPanelShown(false)
            }
            val first = awaitOne()
            withContext(Dispatchers.Main) { wm.removeViewImmediate(first) }
            awaitOne() // No task progress, user input, or second show() drives this recovery.
            withContext(Dispatchers.Main) {
                island.setPanelShown(true)
                island.setPanelShown(false)
            }
            val visible = awaitOne()
            withContext(Dispatchers.Main) {
                wm.removeViewImmediate(visible)
                island.setPanelShown(true)
            }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) {
                assertTrue(windows().isEmpty())
                island.setPanelShown(false)
            }
            val last = awaitOne()
            withContext(Dispatchers.Main) {
                wm.removeViewImmediate(last)
                island.dismiss()
            }
            ins.waitForIdleSync()
            withContext(Dispatchers.Main) { assertTrue(windows().isEmpty()) }
        } finally {
            withContext(Dispatchers.Main) { island.dismiss() }
        }
    }

    @Test fun visibleProgressCanPauseReadChatResumeAndStop(): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        val ctx = ContextCompat.getContextForLanguage(ins.targetContext)
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val serviceInfo = ui.serviceInfo
        val originalFlags = serviceInfo.flags
        serviceInfo.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        ui.serviceInfo = serviceInfo
        ins.targetContext.startActivity(Intent().setComponent(ComponentName(ins.context, DragTimingProbeActivity::class.java.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        delay(750)
        val sessions = (ins.targetContext.applicationContext as HarnessApp).container.sessions
        val sid = sessions.createSession("Island result fixture", conversation = ConversationConfig(ConversationKind.PHONE))
        val launched = CompletableDeferred<String?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.component?.className == MainActivity::class.java.name) launched.complete(intent.getStringExtra(AgentService.EXTRA_SESSION_ID))
                return null
            }
        }
        ins.addMonitor(monitor)
        var opens = 0; var closes = 0; var stops = 0
        val island = PhoneTaskIsland(ctx, sid, sid, { opens++ }, { closes++ }, { stops++ })
        fun own() = ui.windows.firstOrNull { it.title?.toString() == "Harness task status" }
        fun find(n: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
            if (n.text?.toString() == label || n.contentDescription?.toString() == label) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { find(it, label) }?.let { return it }
            return null
        }
        suspend fun node(label: String): AccessibilityNodeInfo = withTimeout(4000) {
            var hit: AccessibilityNodeInfo? = null
            while (hit == null) { hit = own()?.root?.let { find(it,label) }; if (hit == null) delay(40) }
            hit
        }
        suspend fun click(label: Int) = assertTrue(node(ctx.getString(label)).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        try {
            withContext(Dispatchers.Main) { PhonePanel.attach(island); island.setPanelShown(false); island.step(2, 3) }
            val compact = ctx.getString(R.string.phone_island_details,ctx.getString(R.string.phone_island_batch,2,3))
            assertTrue(node(compact).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val stopButton = node(ctx.getString(R.string.phone_island_stop))
            ins.waitForIdleSync()
            delay(180) // SurfaceFlinger has presented the expanded frame, not just published its nodes.
            val bounds = Rect().also { own()!!.getBoundsInScreen(it) }
            val hit = Rect().also { stopButton.getBoundsInScreen(it) }
            assertTrue("Entire Stop control must fit within the card: $hit / $bounds", bounds.contains(hit))
            assertTrue("Card must leave a right margin: $bounds", bounds.right < ctx.resources.displayMetrics.widthPixels)
            ui.takeScreenshot()?.let { b ->
                File(ctx.getExternalFilesDir(null), "phone-island-expanded.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
                b.recycle()
            }
            val blocked = async { PhonePanel.withClearTarget(sid) {
                assertNull("An assistant overlay remained over the target during I/O", own())
            } }
            delay(130); assertFalse(blocked.isCompleted)
            click(R.string.phone_island_chat)
            withContext(Dispatchers.Main) {
                assertEquals(1, opens); assertEquals(sid,PhonePanel.reviewing.value)
                island.resume(); assertEquals(1,closes)
                assertFalse(blocked.isCompleted)
                island.panelLeft() // Android confirmed the requested hide
            }
            withTimeout(3000) { blocked.await() }
            assertTrue(node(compact).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            click(R.string.phone_island_stop)
            withContext(Dispatchers.Main) { assertEquals(1,stops); island.finish(false,true,false,emptyList()) }
            val end = node(ctx.getString(R.string.phone_island_details,ctx.getString(R.string.phone_island_stopped)))
            assertEquals(ins.context.packageName, ui.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }?.root?.packageName)
            assertFalse("Finishing must not open Chat", launched.isCompleted)
            ins.waitForIdleSync(); delay(120)
            ui.takeScreenshot()?.let { b ->
                File(ctx.getExternalFilesDir(null), "phone-island-finished.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
                b.recycle()
            }
            assertTrue(end.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            assertEquals(sid,withTimeout(4000) { launched.await() })
            withContext(Dispatchers.Main) { assertNull(PhonePanel.progress) }
        } finally {
            withContext(Dispatchers.Main) { island.dismiss() }
            ins.removeMonitor(monitor)
            sessions.session(sid)?.let { sessions.deleteSession(it) }
            ctx.getSystemService(android.app.NotificationManager::class.java).cancel(0x60701000 xor sid.hashCode())
            serviceInfo.flags = originalFlags; ui.serviceInfo = serviceInfo
        }
    }
}
