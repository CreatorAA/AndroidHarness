package com.androidharness.app.assistant

import org.junit.Assert.*
import org.junit.Test

class AutoJsScriptDraftsTest {
    @Test fun previewIsLocalAndAConfirmationNamesOneExactSnapshot() {
        val drafts = AutoJsScriptDrafts { 5L }
        val source = """text("Next").findOne(1500).click();"""
        val first = drafts.stage(source, "First")
        assertEquals(source, first.source)
        assertEquals(1, first.steps.size)
        assertEquals("First", first.savedName)
        val anonymous = drafts.stage("""text("Done").waitFor();""", null)
        assertNull(anonymous.savedName)
        assertFalse(drafts.claim(first))
        assertSame(anonymous, drafts.pending.value)
        assertTrue(drafts.claim(anonymous))
        assertFalse(drafts.claim(anonymous))
    }

    @Test fun expiryOrCancellingOnlyTheShownReviewPreventsRunningIt() {
        var now = 10L
        val drafts = AutoJsScriptDrafts { now }
        val first = drafts.stage("""text("Done").waitFor();""", null)
        val second = drafts.stage(first.source, null)
        drafts.discard(first)
        assertSame(second, drafts.pending.value)
        now += 600_000L
        assertFalse(drafts.claim(second))
        val third = drafts.stage(first.source, null)
        drafts.clear()
        assertFalse(drafts.claim(third))
    }

    @Test fun invalidDraftCannotReplaceAValidTrial() {
        val drafts = AutoJsScriptDrafts()
        val ticket = drafts.stage("""text("Done").waitFor();""", null)
        assertThrows(IllegalArgumentException::class.java) { drafts.stage("unknown();", null) }
        assertSame(ticket, drafts.pending.value)
    }
}
