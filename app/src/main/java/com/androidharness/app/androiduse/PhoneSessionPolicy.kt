package com.androidharness.app.androiduse

/** Rules shared by the confirmation preview and the phone action that follows it. */
internal object PhoneSessionPolicy {
    private const val MAX_FRAME_AGE_MS = 90_000L

    fun isFresh(frameId: String, requestedId: String?, capturedAt: Long, now: Long): Boolean =
        frameId == requestedId && now >= capturedAt && now - capturedAt <= MAX_FRAME_AGE_MS

    /** Android reports exclusive right/bottom edges. The companion reports a point for some leaves. */
    fun hitAt(frame: PhoneFrame, x: Int, y: Int): PhoneNode? {
        if (x !in 0 until frame.width || y !in 0 until frame.height) return null
        fun PhoneNode.containsPoint(): Boolean =
            if (left == right && top == bottom) x == left && y == top
            else x in left until right && y in top until bottom
        val hits = frame.nodes.filter { it.containsPoint() }
        val front = hits.lastOrNull() ?: return null
        // Do not discard an unlabelled foreground control and then call it by a label behind it.
        if (front.clickable || front.editable) return front
        if (front.text.isBlank() && front.description.isBlank()) return null
        // A visible text child can label the actual button underneath it in the native hierarchy.
        val parent = hits.dropLast(1).lastOrNull { it.clickable || it.editable } ?: return null
        if (!front.id.startsWith(parent.id + ".")) return null
        val parentLabel = listOf(parent.text, parent.description).filter { it.isNotBlank() }.joinToString(" ")
        return if (parentLabel.isEmpty()) front
        else front.copy(description = listOf(front.description, parentLabel).filter { it.isNotBlank() }.joinToString(" "))
    }

    /** An unchanged package or a label that was already present does not establish what a tap did. */
    fun verifies(before: PhoneFrame, after: PhoneFrame, action: PhoneAction, expectedText: String?,
                 expectedPackage: String?, targetPackage: String?, submit: Boolean): Boolean {
        if (after.packageName != targetPackage || after.displayId != before.displayId ||
            expectedPackage != null && after.packageName != expectedPackage) return false
        fun PhoneFrame.shows(text: String) = nodes.any { text in it.text || text in it.description ||
            (action.kind == "set_text" && it.id == action.node && text == action.text && it.hasEditorValue(text)) }
        val text = expectedText?.takeIf(String::isNotBlank)
        if (text != null && !after.shows(text)) return false
        val newText = text != null && !before.shows(text)
        val newPackage = before.packageName != after.packageName &&
            (expectedPackage == after.packageName || action.kind == "launch" && action.packageName == after.packageName)
        if (action.kind != "set_text") return newText || newPackage

        // Match the designated editor. Re-used node indices or another field containing the same value
        // are insufficient evidence. Changed layouts can be inspected again as an unverified action.
        val old = before.nodes.singleOrNull { it.id == action.node && it.editable } ?: return false
        val value = action.text ?: return false
        val intended = after.nodes.singleOrNull { it.id == old.id && it.editable } ?: return false
        if (before.windowKey != after.windowKey || before.rotation != after.rotation ||
            old.hasEditorValue(value) || !intended.hasEditorValue(value) || old.left != intended.left || old.top != intended.top) return false
        return !submit || newText || newPackage
    }
}
