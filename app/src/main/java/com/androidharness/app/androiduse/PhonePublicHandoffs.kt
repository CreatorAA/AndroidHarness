package com.androidharness.app.androiduse

import kotlinx.serialization.json.*

/** Identity is the actual Intent payload (e.g. timestamps in milliseconds), not model JSON spelling. */
internal data class PublicHandoffKey(
    val target: String, val id: String, val data: String?, val extras: Map<String, Any?>,
)

internal class PhonePublicHandoffs {
    private val opened = linkedMapOf<PublicHandoffKey, JsonObject>()

    @Synchronized fun requireNew(key: PublicHandoffKey) {
        require(key !in opened) {
            "This exact request was already handed to the app. Do not repeat it to verify the result. " +
                "Decline the current offer to inspect the page, or use genuinely different arguments for a new user-requested step."
        }
    }

    @Synchronized fun record(key: PublicHandoffKey, args: JsonObject) { opened[key] = args }

    @Synchronized fun available(pkg: String, actions: JsonArray) = buildJsonArray {
        for (item in actions) {
            val action = item.jsonObject
            val id = action.getValue("id").jsonPrimitive.content
            val last = opened.entries.lastOrNull { it.key.target == pkg && it.key.id == id }
            if (last == null) add(action)
            else if (action.getValue("parameters").jsonObject.isNotEmpty()) add(buildJsonObject {
                action.forEach { (k, v) -> put(k, v) }
                put("afterHandoff", "Use only for a different request. To inspect or continue the previous handoff, decline this offer.")
                // Long editor drafts were already in the approved tool call; don't duplicate them in chat history.
                if (last.value.toString().length <= 300) put("lastHandedOffArguments", last.value)
            })
        }
    }
}
