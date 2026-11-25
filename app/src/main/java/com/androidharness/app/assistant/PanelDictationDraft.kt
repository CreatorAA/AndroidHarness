package com.androidharness.app.assistant

/** A system recognizer may end a segment at any pause. A segment is not necessarily a message. */
internal class PanelDictationDraft(initial: String, autoSend: Boolean) {
    private var complete = initial
    private var partial = ""
    private val handsFree = autoSend && initial.isBlank()
    var finishing = false
        private set
    val text get() = append(complete, partial)
    val silenceEndsInput get() = handsFree

    fun preview(value: String) { partial = value }
    fun finish() { finishing = true }

    /** True only if this complete result can be submitted. Otherwise keep listening for another segment. */
    fun acceptFinal(value: String): Boolean {
        complete = append(complete, value)
        partial = ""
        return finishing || handsFree
    }

    private fun append(base: String, addition: String): String {
        val tail = addition.trim()
        if (tail.isEmpty()) return base
        if (base.isBlank()) return tail
        // Chinese dictation should still read “高德地图搜索纸月测试公园”; separate adjacent Latin words.
        val separator = if (base.last().isLetterOrDigit() && base.last().code < 128 &&
            tail.first().isLetterOrDigit() && tail.first().code < 128) " " else ""
        return base + separator + tail
    }
}
