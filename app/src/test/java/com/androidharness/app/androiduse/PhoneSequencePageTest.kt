package com.androidharness.app.androiduse

import org.junit.Assert.*
import org.junit.Test

class PhoneSequencePageTest {
    @Test fun aSharedToolbarDoesNotAuthorizeItsButtonOnAReplacementPage() {
        val toolbar = PhoneNode("0.0", "返回", "", false, true, 0, 0, 100, 60, "Button", "p:id/back")
        val field = PhoneNode("0.1", "", "", true, true, 0, 65, 300, 125, "EditText", "p:id/message")
        val page = PhoneFrame("p", "same activity window", 500, 900, 0, listOf(toolbar, field))
        assertTrue("Typing is not a page replacement", PhoneSequence.matchesPage(page,
            page.copy(nodes = listOf(toolbar, field.copy(text = "新留言")))))
        val review = page.copy(nodes = listOf(toolbar, field.copy(text = "确认", editable = false,
            viewClass = "Button", resource = "p:id/confirm")))
        assertEquals(toolbar, PhoneSequence.Step("tap", toolbar, null).resolve(review))
        assertFalse("The toolbar survived but the field was replaced by a different control", PhoneSequence.matchesPage(page, review))
    }
}
