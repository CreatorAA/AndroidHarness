package com.androidharness.app.assistant

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhonePanelTaskTest {
    private var hides = 0
    private var returns = 0
    private var releases = 0
    private val task = PhonePanelTask("session", "turn-token", { hides++ }, { returns++ }, { releases++ }, { fail("lost window") })

    @Test fun driverWaitsForActualHideAndApprovalsReuseTheSamePanel() = runBlocking {
        val hidden = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { true } }
        assertEquals(1, hides)
        assertFalse(hidden.isCompleted)
        assertTrue(task.onHidden())
        assertTrue(hidden.await())
        assertTrue(task.awaitHidden { true })
        assertEquals(1, hides)
        task.requestReturn()
        assertEquals(1, returns)
        assertFalse(task.awaitHidden { true })
        assertFalse(task.onShown(null)) // a fresh summons is handled separately as human inspection
        assertFalse(task.onShown("earlier-turn"))
        assertTrue(task.onShown("turn-token"))
        assertEquals(0, releases)
        val next = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { true } }
        task.onHidden()
        assertTrue(next.await())
        task.finish()
        assertTrue(task.onShown("turn-token"))
        assertEquals(1, releases)
        assertFalse(task.awaitHidden { true })
    }

    @Test fun completionDuringHideCannotDispatchAndOnlyRestoresOnce() = runBlocking {
        val hidden = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { true } }
        task.finish()
        assertEquals(0, returns)
        task.onHidden()
        task.requestReturn()
        assertEquals(1, returns)
        assertFalse(hidden.await())
        assertTrue(task.onShown("turn-token"))
        assertEquals(1, releases)
    }

    @Test fun visibleResultIslandEndsLeaseWithoutCoveringTheTargetApp() = runBlocking {
        val hidden = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { true } }
        task.onHidden()
        assertTrue(hidden.await())
        task.finish(keepTargetVisible = true)
        assertEquals(0, returns); assertEquals(1, releases)
        assertFalse(task.awaitHidden { true })
    }

    @Test fun islandCompletionDuringHideWaitsForAndroidAndDoesNotPopChatBackUp() = runBlocking {
        val hidden = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { true } }
        task.finish(keepTargetVisible = true)
        assertTrue(task.onHidden())
        assertFalse(hidden.await()); assertEquals(0,returns); assertEquals(1,releases)
    }

    @Test fun explicitStopCancelsPendingHideWithoutReopeningOrReplaying() = runBlocking {
        val hidden = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { true } }
        task.close()
        assertFalse(task.onHidden())
        assertFalse(hidden.await())
        task.close()
        task.finish()
        assertEquals(1, releases)
        assertEquals(0, returns)
    }

    @Test fun revocationBetweenHideRequestAndWindowCallbackStopsTheAction() = runBlocking {
        var authorized = true
        val hidden = async(start = CoroutineStart.UNDISPATCHED) { task.awaitHidden { authorized } }
        authorized = false
        task.onHidden()
        assertFalse(hidden.await())
    }

    @Test fun userDismissesBeforeAnyDeviceUseAndResultDoesNotReopenPanel() = runBlocking {
        assertTrue(task.onHidden())
        assertTrue(task.backgrounded)
        assertTrue(task.awaitHidden { true })
        assertEquals(0, hides)
        task.finish()
        assertEquals(0, returns)
        assertEquals(1, releases)
    }

    @Test fun independentSummonsCanInspectAndDismissTheSameTurn() = runBlocking {
        task.onHidden()
        task.inspect()
        assertFalse(task.backgrounded)
        assertEquals(0, releases)
        assertTrue(task.onHidden())
        assertTrue(task.awaitHidden { true })
        task.requestReturn()
        task.returnUnavailable()
        assertTrue(task.backgrounded)
        task.requestReturn()
        assertTrue(task.onShown("turn-token"))
        assertEquals(0, releases)
    }
}
