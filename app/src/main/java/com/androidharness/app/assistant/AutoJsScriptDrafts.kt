package com.androidharness.app.assistant

import com.androidharness.app.androiduse.AutoJsUiScripts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A local editor snapshot for review in front of a target app. Staging and preview never touch the device. */
internal class AutoJsScriptDrafts(private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    class Draft internal constructor(val source: String, val savedName: String?, val steps: List<String>, val until: Long)
    private val mutable = MutableStateFlow<Draft?>(null)
    val pending = mutable.asStateFlow()

    @Synchronized fun stage(source: String, savedName: String?): Draft =
        Draft(source, savedName, AutoJsUiScripts.check(source).toList(), now() + 10 * 60_000L).also { mutable.value = it }

    @Synchronized fun remaining(draft: Draft): Long =
        if (mutable.value === draft) (draft.until - now()).coerceAtLeast(0L) else 0L

    @Synchronized fun claim(draft: Draft): Boolean {
        if (mutable.value !== draft) return false
        mutable.value = null
        return now() < draft.until
    }

    @Synchronized fun discard(draft: Draft) { if (mutable.value === draft) mutable.value = null }
    @Synchronized fun clear() { mutable.value = null }
}
