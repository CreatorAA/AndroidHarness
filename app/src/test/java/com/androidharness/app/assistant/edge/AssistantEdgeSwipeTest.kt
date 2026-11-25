package com.androidharness.app.assistant.edge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEdgeSwipeTest {
    private val swipe = AssistantEdgeSwipe(distance = 48f)

    @Test fun inwardDragOpensOnReleaseOnlyOnce() {
        swipe.start(350f, 380f)
        assertTrue(swipe.finish(302f, 395f))
        assertFalse(swipe.finish(270f, 395f))
    }

    @Test fun tapsShortDragsAndVerticalOrOutwardDragsDoNotOpen() {
        listOf(350f to 380f, 303f to 380f, 350f to 300f, 290f to 305f, 400f to 380f)
            .forEach { (x, y) ->
                swipe.start(350f, 380f)
                assertFalse("Unexpected open at ($x, $y)", swipe.finish(x, y))
            }
    }

    @Test fun canceledGestureDoesNotOpenAndTheNextGestureStillWorks() {
        swipe.start(350f, 380f)
        swipe.cancel()
        assertFalse(swipe.finish(250f, 380f))
        swipe.start(350f, 380f)
        assertTrue(swipe.finish(250f, 380f))
    }

    @Test fun verticalDragLocksPlacementEvenIfTheFingerEndsFarInward() {
        swipe.start(350f, 380f)
        org.junit.Assert.assertNull(swipe.verticalOffset(349f, 384f))
        org.junit.Assert.assertEquals(28f, swipe.verticalOffset(348f, 408f)!!, 0f)
        org.junit.Assert.assertEquals(24f, swipe.verticalOffset(250f, 404f)!!, 0f)
        assertFalse(swipe.finish(250f, 400f))
        swipe.start(350f, 380f)
        assertTrue(swipe.finish(275f, 380f))
    }
}
