package com.androidharness.app.assistant

import org.junit.Assert.*
import org.junit.Test

class PanelDictationDraftTest {
    @Test fun pausesKeepTheWholeCommandUntilThePersonFinishes() {
        val draft = PanelDictationDraft("", autoSend = false)
        assertFalse(draft.silenceEndsInput)
        draft.preview("帮我打")
        assertFalse(draft.acceptFinal("帮我打开高德地图"))
        assertFalse(draft.acceptFinal("搜索"))
        draft.preview("纸")
        draft.finish()
        assertTrue(draft.acceptFinal("纸月测试公园"))
        assertEquals("帮我打开高德地图搜索纸月测试公园", draft.text)
    }

    @Test fun recoveringOrEditingAPartialNeverMakesItAHandsFreeCommand() {
        val first = PanelDictationDraft("", autoSend = false)
        first.acceptFinal("我想")
        first.preview("去纸月测试")
        val retry = PanelDictationDraft(first.text, autoSend = true)
        assertFalse(retry.silenceEndsInput)
        assertFalse(retry.acceptFinal("公园"))
        assertEquals("我想去纸月测试公园", retry.text)
    }

    @Test fun enabledHandsFreeSendsOnlyTheCompleteResult() {
        val draft = PanelDictationDraft("", autoSend = true)
        assertTrue(draft.silenceEndsInput)
        draft.preview("wrong")
        assertTrue(draft.acceptFinal("right"))
        assertEquals("right", draft.text)
    }

    @Test fun englishSegmentsAndEditedWhitespaceArePreserved() {
        val draft = PanelDictationDraft("Find ", autoSend = false)
        draft.acceptFinal("PaperMoon")
        draft.acceptFinal("Park")
        assertEquals("Find PaperMoon Park", draft.text)
    }
}
