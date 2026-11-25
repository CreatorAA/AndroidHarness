package com.androidharness.app.androiduse

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal data class PageActionEntry(val ref: String, val id: String)
internal data class PageActionList(val status: String, val actions: List<PageActionEntry> = emptyList())
internal data class PageActionReply(val acknowledged: Boolean, val values: JsonObject)

/** A handle into a live VoiceInteractionSession, never a package-wide catalog. */
internal interface PageActionSource {
    suspend fun isCurrent(packageName: String, authorized: () -> Boolean): Boolean
    suspend fun discover(packageName: String, authorized: () -> Boolean): PageActionList
    suspend fun perform(packageName: String, ref: String, arguments: JsonObject, authorized: () -> Boolean): PageActionReply
}

internal fun pageNotSent(message: String): Nothing = throw PhoneActionNotSent(IllegalStateException(message))

/** Only the currently bound assistant task can address its activity's OS handles. */
internal object PageActions {
    private class Binding(val sid: String, val source: PageActionSource, val active: () -> Boolean)
    private var owner: Binding? = null

    // All accesses run on Android's main thread, together with session and permission callbacks.
    fun bind(sid: String, source: PageActionSource, active: () -> Boolean) { owner = Binding(sid, source, active) }
    fun unbind(sid: String, source: PageActionSource) {
        if (owner?.sid == sid && owner?.source === source) owner = null
    }
    private fun Binding.live() = owner === this && active()

    suspend fun isCurrent(sessionId: String, packageName: String): Boolean = withContext(Dispatchers.Main.immediate) {
        val b = owner?.takeIf { it.sid == sessionId && it.live() } ?: return@withContext false
        b.source.isCurrent(packageName) { b.live() } && b.live()
    }

    suspend fun discover(sessionId: String, packageName: String): PageActionList = withContext(Dispatchers.Main.immediate) {
        val b = owner?.takeIf { it.sid == sessionId && it.live() } ?: return@withContext PageActionList("unavailable")
        val result = b.source.discover(packageName) { b.live() }
        if (b.live()) result else PageActionList("changed")
    }

    suspend fun perform(sessionId: String, packageName: String, ref: String, arguments: JsonObject = JsonObject(emptyMap())): PageActionReply =
        withContext(Dispatchers.Main.immediate) {
            val b = owner?.takeIf { it.sid == sessionId && it.live() } ?: pageNotSent("No live assistant task.")
            b.source.perform(packageName, ref, arguments) { b.live() }
        }
}
