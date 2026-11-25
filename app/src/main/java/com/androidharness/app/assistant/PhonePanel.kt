package com.androidharness.app.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The single built-in assistant panel must leave the target display before a driver can use it. */
internal object PhonePanel {
    private class Owner(val session: String, var startStatus: (() -> Unit)?, val hide: suspend () -> Boolean)
    private var owner: Owner? = null
    internal var progress: PhoneTaskIsland? = null
        private set
    private val viewing = MutableStateFlow<String?>(null)
    val reviewing = viewing.asStateFlow()
    fun setReview(session: String?) { viewing.value = session }
    fun attach(island: PhoneTaskIsland) { progress?.dismiss(); progress = island }
    fun detach(island: PhoneTaskIsland) {
        if (progress === island) { progress = null; viewing.value = null }
    }
    suspend fun step(sessionId: String, current: Int, total: Int) = withContext(Dispatchers.Main.immediate) {
        progress?.takeIf { it.sid == sessionId }?.step(current, total)
        Unit
    }
    /** Serialize inspection versus device I/O on Main; the original caller still owns the I/O context. */
    suspend fun <T> withClearTarget(sessionId: String, block: suspend () -> T): T {
        val island = withContext(Dispatchers.Main.immediate) {
            progress?.takeIf { it.sid == sessionId && it.active }?.also { it.beforeInput() }
        }
        return try { block() } finally {
            if (island != null) withContext(NonCancellable + Dispatchers.Main.immediate) { island.afterInput() }
        }
    }
    private val suppress = MutableStateFlow(false)
    val suppressEdge = suppress.asStateFlow()

    // All ownership changes run on the session's main thread.
    fun bind(session: String, startStatus: (() -> Unit)? = null, hide: suspend () -> Boolean) {
        owner = Owner(session, startStatus, hide)
        suppress.value = startStatus == null
    }

    suspend fun noteUse(sessionId: String) = withContext(Dispatchers.Main.immediate) {
        owner?.takeIf { it.session == sessionId }?.let { current ->
            val start = current.startStatus
            current.startStatus = null
            if (start != null) { suppress.value = true; start() }
        }
        Unit
    }
    fun unbind(session: String) {
        if (owner?.session != session) return
        owner = null
        suppress.value = false
    }

    suspend fun awaitHidden(sessionId: String): Boolean = withContext(Dispatchers.Main.immediate) {
        noteUse(sessionId)
        val bound = owner?.takeIf { it.session == sessionId } ?: return@withContext false
        progress?.takeIf { it.sid == sessionId }?.let { if (!it.awaitResume()) return@withContext false }
        bound.hide() && owner === bound
    }
}
