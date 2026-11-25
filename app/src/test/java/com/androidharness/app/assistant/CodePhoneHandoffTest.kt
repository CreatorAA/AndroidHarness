package com.androidharness.app.assistant

import org.junit.Assert.*
import org.junit.Test

class CodePhoneHandoffTest {
    @Test fun readingIsNotClaimingAndConfirmationIsSingleUse() {
        val handoff = CodePhoneHandoff { 0 }
        handoff.offer("code", "local", "你好 🐦")
        val shown = requireNotNull(handoff.current())
        assertSame(shown, handoff.current())
        assertEquals("你好 🐦", shown.instruction)
        assertTrue(handoff.claim(shown))
        assertNull(handoff.current())
        assertFalse(handoff.claim(shown))
    }

    @Test fun staleDialogCannotAcceptAReplacementAndOnlyTheOriginCanCancel() {
        val handoff = CodePhoneHandoff { 0 }
        handoff.offer("first", "local", "hello")
        val old = requireNotNull(handoff.current())
        handoff.offer("second", "local", "hello")
        assertFalse(handoff.claim(old))
        assertFalse(handoff.cancel("first"))
        assertEquals("second", handoff.current()?.source)
        assertTrue(handoff.cancel("second"))
        assertNull(handoff.current())
    }

    @Test fun confirmationExpiresEvenWhenTheDialogWasLeftOpen() {
        var now = 100L
        val handoff = CodePhoneHandoff { now }
        handoff.offer("code", "local", "draft")
        val shown = requireNotNull(handoff.current())
        now += 600_000L
        assertFalse(handoff.claim(shown))
        assertNull(handoff.current())
    }
}
