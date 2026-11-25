package com.androidharness.app.agent

import com.androidharness.app.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepaliveTrackerTest {
    private val events = mutableListOf<String>()
    private val tracker = KeepaliveTracker(
        { events += "start" }, { events += "active=$it" },
    )
    private val settings = MutableStateFlow(AppSettings(runInBackground = true, keepAlive = true))
    private val demand = tracker.wakeLockDemand(settings)
    private fun needsLock() = runBlocking { demand.first() }

    @Test fun `background service never signals an active task`() {
        tracker.acquire(false)
        tracker.acquire(false)
        tracker.release(false)
        assertTrue(needsLock())
        assertEquals(listOf("start"), events)
        tracker.release(false)
        assertFalse(needsLock())
    }

    @Test fun `task finishes while background service keeps its wake lease`() {
        tracker.acquire(false)
        tracker.acquire()
        tracker.release()
        assertEquals(listOf("start", "active=true", "active=false"), events)
        assertTrue(needsLock())
        tracker.release(false)
        assertFalse(needsLock())
    }

    @Test fun `background service stopping does not hide active work`() {
        tracker.acquire()
        tracker.acquire(false)
        tracker.release(false)
        assertEquals(listOf("active=true", "start"), events)
        assertTrue(needsLock())
        tracker.release()
        assertEquals(listOf("active=true", "start", "active=false"), events)
        assertFalse(needsLock())
    }

    @Test fun `parallel tasks stay active until the last task finishes`() {
        tracker.acquire()
        tracker.acquire()
        tracker.release()
        assertEquals(listOf("active=true", "start"), events)
        assertTrue(needsLock())
        tracker.release()
        assertEquals(listOf("active=true", "start", "active=false"), events)
        assertFalse(needsLock())
    }

    @Test fun `unmatched releases do not consume the other kind of lease`() {
        tracker.release()
        tracker.acquire(false)
        tracker.release()
        assertEquals(listOf("start"), events)
        assertTrue(needsLock())
        tracker.release(false)
        tracker.release(false)
        assertFalse(needsLock())
        tracker.acquire()
        tracker.release(false)
        assertTrue(needsLock())
        tracker.release()
        assertEquals(listOf("start", "active=true", "start", "active=false"), events)
        assertFalse(needsLock())
    }

    @Test fun `service starting after the last release does not acquire a wake lock`() {
        tracker.acquire()
        tracker.release()
        assertEquals(listOf("active=true", "start", "active=false"), events)
        // The service attaches only now, after its asynchronous start has been delivered.
        assertFalse(needsLock())
    }

    @Test fun `settings apply to an existing passive lease without restarting work`() {
        tracker.acquire(false)
        settings.value = settings.value.copy(keepAlive = false)
        assertFalse(needsLock())
        settings.value = settings.value.copy(keepAlive = true)
        assertTrue(needsLock())
        settings.value = settings.value.copy(runInBackground = false)
        assertFalse(needsLock())
        tracker.release(false)
        settings.value = settings.value.copy(runInBackground = true)
        assertFalse(needsLock())
        assertEquals(listOf("start"), events)
    }
}
