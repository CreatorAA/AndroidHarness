package com.androidharness.app.assistant

/** Only an instruction for the confirmation form. Neither staging nor reading it grants phone access. */
internal class CodePhoneHandoff(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    class Draft(val source: String, val project: String, val instruction: String, val expires: Long)
    private var pending: Draft? = null

    @Synchronized fun offer(source: String, project: String, instruction: String) {
        pending = Draft(source, project, instruction, now() + 10 * 60_000L)
    }
    @Synchronized fun current(): Draft? = pending?.takeIf { now() < it.expires }
        .also { if (it == null) pending = null }

    @Synchronized fun claim(shown: Draft): Boolean {
        if (current() !== shown) return false
        pending = null
        return true
    }
    @Synchronized fun cancel(source: String?): Boolean = current()?.let { it.source == source && claim(it) } == true
}
