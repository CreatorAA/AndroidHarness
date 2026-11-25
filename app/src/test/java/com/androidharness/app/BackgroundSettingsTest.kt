package com.androidharness.app

import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.SettingsBackupPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackgroundSettingsTest {
    @Test fun `older backups preserve the existing enabled behavior`() {
        val saved = Json.decodeFromString<AppSettings>("{}")
        assertTrue(saved.runInBackground)
        assertTrue(saved.notificationsEnabled)
    }

    @Test fun `portable settings keep the switches independent`() {
        for (background in listOf(false, true)) for (notifications in listOf(false, true)) {
            val original = AppSettings(runInBackground = background, notificationsEnabled = notifications)
            val portable = SettingsBackupPolicy.exportSettings(original, emptySet())
            val restored = Json.decodeFromString<AppSettings>(Json.encodeToString(portable))
            assertEquals(background, restored.runInBackground)
            assertEquals(notifications, restored.notificationsEnabled)
        }
    }

    @Test fun `notifications do not decide whether agents can execute`() {
        for (notifications in listOf(false, true)) {
            assertFalse(AppRuntimeState(notificationsEnabled = notifications).canRun)
            assertFalse(AppRuntimeState(ready = true, notificationsEnabled = notifications).canRun)
            assertTrue(AppRuntimeState(ready = true, visible = true, notificationsEnabled = notifications).canRun)
            assertTrue(AppRuntimeState(ready = true, runInBackground = true, notificationsEnabled = notifications).canRun)
        }
    }

    @Test fun `cold assistant binding waits for a visible entry to start the idle service`() {
        val cold = AppRuntimeState()
        val bound = AppRuntimeState(ready = true, runInBackground = true)
        val opened = bound.copy(visible = true)
        assertFalse(bound.shouldStartKeepaliveAfter(cold, serviceRunning = false, activeWork = false))
        assertTrue(opened.shouldStartKeepaliveAfter(bound, serviceRunning = false, activeWork = false))
        assertFalse(opened.shouldStartKeepaliveAfter(bound, serviceRunning = true, activeWork = false))
        assertFalse(opened.copy(notificationsEnabled = true).shouldStartKeepaliveAfter(opened, serviceRunning = false, activeWork = false))
        assertFalse(bound.shouldStartKeepaliveAfter(opened, serviceRunning = false, activeWork = false))
    }

    @Test fun `background preference still gates starts and active work can reacquire on opt in`() {
        val disabled = AppRuntimeState(ready = true)
        assertFalse(disabled.copy(visible = true).shouldStartKeepaliveAfter(disabled, serviceRunning = false, activeWork = true))
        assertTrue(disabled.copy(runInBackground = true).shouldStartKeepaliveAfter(disabled, serviceRunning = true, activeWork = true))
    }
}
