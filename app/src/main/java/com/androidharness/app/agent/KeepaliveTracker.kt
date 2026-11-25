package com.androidharness.app.agent

import com.androidharness.app.data.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Background services share the wake lease without advertising an active task. */
internal class KeepaliveTracker(
    private val onFirstLease: () -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private var tasks = 0
    private var background = 0
    private val leased = MutableStateFlow(false)

    /** A newly started service sees current demand, never an obsolete acquire command. */
    fun wakeLockDemand(settings: Flow<AppSettings>): Flow<Boolean> =
        combine(leased, settings) { held, prefs -> held && prefs.runInBackground && prefs.keepAlive }
            .distinctUntilChanged()

    @Synchronized fun acquire(activeTask: Boolean = true) {
        val wasIdle = tasks + background == 0
        if (activeTask) {
            if (tasks++ == 0) onActiveChanged(true)
        } else background++
        if (wasIdle) {
            leased.value = true
            onFirstLease()
        }
    }

    @Synchronized fun release(activeTask: Boolean = true) {
        if (activeTask) {
            if (tasks == 0) return
            if (--tasks == 0) onActiveChanged(false)
        } else {
            if (background == 0) return
            background--
        }
        if (tasks + background == 0) leased.value = false
    }
}
