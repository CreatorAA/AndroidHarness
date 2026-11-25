package com.androidharness.app.androiduse

import org.junit.Assert.*
import org.junit.Test

class PhoneSessionPolicyTest {
    private val policy = PhoneSessionPolicy
    private val app = "example.target"
    private val first = PhoneFrame(app, "window-1", 100, 200, 0, emptyList())
    private val next = "example.next"
    private val tap = PhoneAction("tap", x = 20, y = 40)
    private fun node(id: String, label: String = "", clickable: Boolean = false, editable: Boolean = false,
                     left: Int = 0, top: Int = 0, right: Int = 100, bottom: Int = 100) =
        PhoneNode(id, label, "", editable, clickable, left, top, right, bottom)

    @Test fun aFrameIsUsableOnlyForItsOwnIdAndOntimeMonotonicClock() {
        assertTrue(policy.isFresh("second", "second", 10, 90_010))
        assertFalse(policy.isFresh("second", "first", 10, 20))
        assertFalse(policy.isFresh("second", null, 10, 20))
        assertFalse(policy.isFresh("second", "second", 10, 90_011))
        assertFalse(policy.isFresh("second", "second", 10, 9))
    }

    @Test fun unlabelledForegroundControlCannotBorrowTheLabelBehindIt() {
        val behind = node("0.1", "Browse", clickable = true)
        val front = node("0.1.1", clickable = true, left = 16, top = 32, right = 40, bottom = 80)
        val page = first.copy(nodes = listOf(behind, front))
        val actual = policy.hitAt(page, 20, 40)
        assertEquals(front, actual)
        assertEquals("", actual!!.text + actual.description)
        assertEquals(behind, policy.hitAt(page, 40, 40)) // Rect.right is exclusive.
    }

    @Test fun coordinatesMustIdentifyTheLabelOfTheActualButton() {
        val button = node("0.1", clickable = true).copy(description = "Delete")
        val child = node("0.1.0", "Draft", left = 15, top = 35, right = 25, bottom = 45)
        val actual = policy.hitAt(first.copy(nodes = listOf(button, child)), 20, 40)
        assertEquals("Draft", actual?.text)
        assertTrue("Do not hide the destructive parent's label", "Delete" in actual!!.description)
        // The same rectangle does not establish that a text overlay belongs to the button.
        assertNull(policy.hitAt(first.copy(nodes = listOf(button, child.copy(id = "0.2"))), 20, 40))
        val point = node("#n3", "Button", clickable = true, left = 20, right = 20, top = 40, bottom = 40)
        assertEquals(point, policy.hitAt(first.copy(nodes = listOf(point)), 20, 40))
        assertNull(policy.hitAt(first.copy(nodes = listOf(point)), 21, 40))
    }

    @Test fun aNoopTapCannotBeVerifiedByTheCurrentPackageOrPreexistingLabel() {
        val before = first.copy(nodes = listOf(node("0", "Settings")))
        val after = before.copy(nodes = before.nodes + node("1", "Other"))
        for (isSubmit in listOf(false, true)) {
            assertFalse(policy.verifies(before, after, tap, null, app, app, isSubmit))
            assertFalse(policy.verifies(before, after, tap, "Settings", app, app, isSubmit))
            assertTrue(policy.verifies(before, after, tap, "Other", app, app, isSubmit))
            assertFalse(policy.verifies(before, after, tap, "Missing", app, app, isSubmit))
            assertFalse(policy.verifies(before, after.copy(displayId = 2), tap, "Other", app, app, isSubmit))
        }
    }

    @Test fun approvalToLaunchOnlyVerifiesTheSpecificNewPackage() {
        val launch = PhoneAction("launch", packageName = next)
        assertTrue(policy.verifies(first, first.copy(packageName = next), launch, null, null, next, false))
        assertFalse(policy.verifies(first, first, launch, null, next, next, false))
        assertFalse(policy.verifies(first, first.copy(packageName = "unrelated"), launch, null, next, next, false))
        assertFalse(policy.verifies(first, first, launch.copy(packageName = app), null, app, app, false))
    }

    @Test fun textReadbackMustBeFromTheIntendedEditorNotMerelyTheSamePage() {
        val field = node("0.1", "Before", editable = true)
        val sibling = node("0.2", "After", editable = true, left = 10)
        val before = first.copy(nodes = listOf(field, sibling))
        val action = PhoneAction("set_text", node = field.id, text = "After")
        val changed = field.copy(text = "After", bottom = 140) // multiline input grew.
        val after = before.copy(nodes = listOf(changed, sibling))
        assertTrue(policy.verifies(before, after, action, null, null, app, false))
        assertFalse(policy.verifies(before, before, action, null, app, app, false))
        assertFalse(policy.verifies(before, after.copy(windowKey = "dialog"), action, null, null, app, false))
        assertFalse(policy.verifies(before, after.copy(nodes = listOf(changed.copy(left = 10))), action, null, null, app, false))
        assertFalse(policy.verifies(before, after, action, null, null, app, true))
    }

    @Test fun longEditorVerificationIsExactWithoutExtendingTheExportedLabel() {
        val prefix = "x".repeat(220)
        fun editor(actual: String) = node("0.1", actual.take(220), editable = true).readEditorValue(actual)
        val before = first.copy(nodes = listOf(editor(prefix + "old")))
        val value = prefix + "new"
        val action = PhoneAction("set_text", node = "0.1", text = value)
        val after = before.copy(nodes = listOf(editor(value)))
        assertTrue(policy.verifies(before, after, action, value, null, app, false))
        assertFalse(policy.verifies(after, after, action, null, null, app, false))
        assertFalse(policy.verifies(before, before.copy(nodes = listOf(editor(value + "!"))), action, null, null, app, false))
        assertFalse(editor(value).hasEditorValue(prefix))
        assertFalse(editor("x".repeat(8200)).hasEditorValue("x".repeat(8192)))
        assertFalse(editor(value).observationJson().toString().contains("new"))
    }

    @Test fun previewIdentifiesOtherwiseUnlabelledTargetsByTypeAndBounds() {
        val first = node("0.1", clickable = true).copy(viewClass = "android.widget.ImageButton")
        assertTrue(first.approvalLabel().contains("ImageButton"))
        assertTrue(first.approvalLabel().contains("[${first.left},${first.top}]"))
        assertNotEquals(first.approvalLabel(), first.copy(top = 81, bottom = 162).approvalLabel())
    }
}
