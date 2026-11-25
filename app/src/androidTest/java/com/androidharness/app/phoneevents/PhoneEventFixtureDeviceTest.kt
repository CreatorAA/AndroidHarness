package com.androidharness.app.phoneevents

import android.app.NotificationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in, dedicated emulator only. Enable Harness notification access before running this test. */
@RunWith(AndroidJUnit4::class)
class PhoneEventFixtureDeviceTest {
    @Test fun ordinaryNoticeBecomesOneContentFreeReminderUntilDismissed() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneEventFixture") == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 30 && (Build.HARDWARE.startsWith("ranchu") || Build.HARDWARE.startsWith("goldfish")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val hub = PhoneEvents.get(context)
        assumeTrue("Enable access to notifications on the dedicated AVD", hub.enabledNotificationAccess())
        // Do not disturb an existing user's rules.
        assumeTrue(!hub.policy.value.enabled && hub.policy.value.rules.isEmpty())
        val rule = EventRule("com.android.shell", PhoneEventKind.NOTIFICATION)
        val tag = "HarnessEventFixture" + System.currentTimeMillis()
        val marker = "SyntheticPrivateValue" + System.currentTimeMillis()
        val container = (context as HarnessApp).container
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Enable Android notifications on the dedicated emulator", manager.areNotificationsEnabled())
        val alerts = container.settings.settings.first().notificationsEnabled
        fun post(suffix: String = "") {
            val descriptor = instrumentation.uiAutomation.executeShellCommand("cmd notification post $tag$suffix $marker")
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        try {
            container.settings.setNotificationsEnabled(false)
            withTimeout(5_000) { container.runtimePolicy.state.first { it.ready && !it.notificationsEnabled } }
            hub.setRule(rule)
            hub.enable(true)
            withTimeout(8_000) { while (!hub.listenerConnected.value) delay(100) }
            post()
            withTimeout(8_000) { while (hub.policy.value.pending.none { it.ruleId == rule.id }) delay(100) }
            val pending = hub.policy.value.pending
            val notices = hub.notices.value
            assertFalse(manager.activeNotifications.any { it.tag == rule.id })
            assertTrue(notices.any { it.app == rule.app })
            assertFalse(notices.toString().contains(marker))
            assertFalse(File(context.noBackupFilesDir, "phone-events.json").readText().contains(marker))
            post()
            delay(400)
            assertEquals(pending, hub.policy.value.pending)
            hub.dismiss(pending.single())
            post()
            delay(400)
            assertTrue(hub.policy.value.pending.isEmpty())
            container.settings.setNotificationsEnabled(true)
            withTimeout(5_000) { container.runtimePolicy.state.first { it.notificationsEnabled } }
            post("Fresh")
            withTimeout(8_000) { while (manager.activeNotifications.none { it.tag == rule.id }) delay(100) }
            hub.enable(false)
            assertTrue(hub.notices.value.isEmpty())
        } finally {
            hub.enable(false)
            hub.remove(rule)
            container.settings.setNotificationsEnabled(alerts)
        }
    }
}
