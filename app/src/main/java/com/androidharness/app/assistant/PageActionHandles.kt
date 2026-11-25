package com.androidharness.app.assistant

import com.androidharness.app.androiduse.PageActionEntry
import java.util.UUID
import kotlinx.serialization.json.*

/** One OS reply. Re-query/replacement, invalidation or dispatch revokes all previous references. */
internal class PageActionHandles<A> {
    var revision = 0L
        private set
    private val byRef = mutableMapOf<String, A>()
    fun clear() { revision++; byRef.clear() }
    fun replace(actions: List<A>, id: (A) -> String): List<PageActionEntry> {
        clear()
        // An app can return two identical IDs. Android performs by ID, so neither is safe to dispatch.
        val counts = actions.groupingBy(id).eachCount()
        return actions.asSequence().filter { validPageKey(id(it)) && counts[id(it)] == 1 }.take(40).map {
            val ref = UUID.randomUUID().toString()
            byRef[ref] = it
            PageActionEntry(ref, id(it))
        }.toList()
    }
    fun take(ref: String): A? = byRef[ref]?.also { clear() }
}

internal fun validPageKey(key: String) = key.isNotBlank() && key.length <= 128 && !key.any(Char::isISOControl)

internal fun pageArguments(arguments: JsonObject): Map<String, Any> {
    require(arguments.size <= 16) { "At most 16 action arguments are supported." }
    return arguments.mapValues { (key, value) ->
        require(validPageKey(key) && value is JsonPrimitive && value !== JsonNull) { "Use named scalar action arguments." }
        when {
            value.isString -> value.content.also { require(it.length <= 1024) { "Action argument is too long." } }
            value.booleanOrNull != null -> value.boolean
            value.intOrNull != null -> value.int
            value.longOrNull != null -> value.long
            else -> value.double.also { require(it.isFinite()) { "Use a finite number." } }
        }
    }
}
