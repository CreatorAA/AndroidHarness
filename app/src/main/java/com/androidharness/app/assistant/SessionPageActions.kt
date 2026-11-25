package com.androidharness.app.assistant

import android.app.DirectAction
import android.os.Bundle
import android.os.CancellationSignal
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSession.ActivityId
import androidx.annotation.RequiresApi
import com.androidharness.app.androiduse.*
import java.util.concurrent.Executor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Operated on the session's main thread. Focus is an OS ActivityId, never an activity name. */
@RequiresApi(29)
internal class SessionPageActions(
    private val session: VoiceInteractionSession,
    private val executor: Executor,
    private val usable: (String) -> Boolean,
    private val reveal: () -> Unit,
) : PageActionSource {
    private class Page(val activity: ActivityId, val pkg: String)
    private class PageChanged : Exception("The page changed.")
    private val focused = MutableStateFlow<Page?>(null)
    private val handles = PageActionHandles<DirectAction>()
    private val calls = mutableSetOf<CompletableDeferred<*>>()
    private var visible = false
    var generation = 0L
        private set

    fun sourceFor(pkg: String): Any? = focused.value?.takeIf { visible && it.pkg == pkg }

    fun reset(showing: Boolean) {
        visible = showing
        generation++
        focused.value = null
        handles.clear()
        calls.toList().forEach { it.completeExceptionally(PageChanged()) }
    }

    fun focus(activity: ActivityId, pkg: String?) {
        if (!visible) return
        val old = focused.value
        if (old?.activity == activity && old.pkg == pkg) return
        reset(true)
        focused.value = pkg?.takeIf(String::isNotBlank)?.let { Page(activity, it) }
    }

    fun invisible(activity: ActivityId) {
        if (focused.value?.activity == activity) reset(visible)
    }
    fun invalidated(activity: ActivityId) {
        if (focused.value?.activity == activity) handles.clear()
    }
    fun endTask() {
        handles.clear()
        calls.toList().forEach { it.completeExceptionally(PageChanged()) }
    }

    private suspend fun acquire(pkg: String, allowed: () -> Boolean): Page? {
        if (!allowed() || !usable(pkg)) return null
        if (!visible || focused.value == null) reveal()
        val page = focused.value ?: withTimeoutOrNull(1_800L) { focused.first { it != null } }
        return page?.takeIf { visible && allowed() && usable(pkg) }
    }

    override suspend fun isCurrent(packageName: String, authorized: () -> Boolean): Boolean {
        if (!authorized() || !usable(packageName)) return false
        // Unlike performDirectAction, a public Intent is not tied to an ActivityId. Ask the OS
        // for fresh focus after approval instead of treating the original summons as current.
        val before = focused.value
        reveal()
        val page = withTimeoutOrNull(2_500L) { focused.first { it != null && it !== before } }
        return page?.pkg == packageName && visible && authorized() && usable(packageName)
    }

    private suspend fun <T> native(start: (CancellationSignal, (T) -> Unit) -> Unit): T {
        val signal = CancellationSignal()
        val answer = CompletableDeferred<T>()
        calls.add(answer)
        var received = false
        try {
            currentCoroutineContext().ensureActive()
            start(signal) { answer.complete(it) }
            return answer.await().also { received = true }
        } finally {
            calls.remove(answer)
            if (!received) signal.cancel()
            answer.cancel()
        }
    }

    /** Inspection in the panel returns only IDs, never creates or replaces a dispatch grant. */
    suspend fun inspect(pkg: String) = list(pkg, keep = false) { visible }

    override suspend fun discover(packageName: String, authorized: () -> Boolean) =
        list(packageName, keep = true, authorized)

    private suspend fun list(pkg: String, keep: Boolean, allowed: () -> Boolean): PageActionList {
        val page = acquire(pkg, allowed) ?: return PageActionList("unavailable")
        if (page.pkg != pkg) return PageActionList("wrong_target")
        val revision = handles.revision
        val raw = try {
            withTimeoutOrNull(3_000L) {
                native<List<DirectAction>> { signal, done ->
                    session.requestDirectActions(page.activity, signal, executor) { done(it) }
                }
            } ?: return PageActionList("timeout")
        } catch (e: CancellationException) { throw e }
        catch (_: PageChanged) { return PageActionList("changed") }
        catch (_: RuntimeException) { return PageActionList("unavailable") }
        if (!allowed() || !usable(pkg) || focused.value !== page || revision != handles.revision)
            return PageActionList("changed")
        val entries = if (keep) handles.replace(raw) { it.id }
            else PageActionHandles<DirectAction>().replace(raw) { it.id }.map { it.copy(ref = "") }
        return PageActionList(when { entries.isNotEmpty() -> "available"; raw.isEmpty() -> "empty"; else -> "unavailable" }, entries)
    }

    override suspend fun perform(packageName: String, ref: String, arguments: JsonObject, authorized: () -> Boolean): PageActionReply {
        val values = try { pageArguments(arguments) }
            catch (bad: IllegalArgumentException) { throw PhoneActionNotSent(bad) }
        val page = acquire(packageName, authorized)
        if (page?.pkg != packageName || !authorized()) pageNotSent("The approved page is no longer in front.")
        val action = handles.take(ref) ?: pageNotSent("Stale page action. Discover actions again.")
        val args = Bundle().apply {
            for ((key, value) in values) when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Double -> putDouble(key, value)
            }
        }
        // No suspension between the live checks, consuming this handle and making the platform call.
        val result = withTimeoutOrNull(5_000L) {
            native<Bundle> { signal, done ->
                if (!authorized() || !usable(packageName) || focused.value !== page)
                    pageNotSent("The approved page changed before dispatch.")
                session.performDirectAction(action, args, signal, executor) { done(it) }
            }
        } ?: error("The app did not acknowledge in time. It may already have performed the action.")
        if (!authorized()) error("The phone task ended after dispatch.")
        // Android also returns Bundle.EMPTY for an undeliverable request. A callback is not proof of success.
        return PageActionReply(!result.isEmpty, scalarResult(result))
    }

    @Suppress("DEPRECATION")
    private fun scalarResult(bundle: Bundle) = buildJsonObject {
        for (key in bundle.keySet().asSequence().filter(::validPageKey).take(16)) {
            when (val v = bundle.get(key)) {
                is String -> put(key, v.take(1024))
                is Boolean -> put(key, v)
                is Byte, is Short, is Int, is Long -> put(key, (v as Number).toLong())
                is Float, is Double -> (v as Number).toDouble().takeIf { it.isFinite() }?.let { put(key, it) }
            }
        }
    }
}
