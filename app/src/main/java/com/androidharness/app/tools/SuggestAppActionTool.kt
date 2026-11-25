package com.androidharness.app.tools

import com.androidharness.app.assistant.AppHandoffRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** An instance belongs to one ordinary assistant utterance. Only the host panel can dispatch a resolved action. */
class SuggestAppActionTool internal constructor(
    private val sessionId: String,
    private val reviewPhoneTask: (suspend () -> String?)? = null,
    private val newConversation: (suspend () -> String?)? = null,
    private val propose: (suspend (AppHandoffRequest) -> String?)? = null,
) : Tool {
    private val presented = AtomicBoolean()
    override val name = NAME
    override val description = buildString {
        if (propose != null) append("Offer an app launch or map search only when it meets the whole request. ")
        if (reviewPhoneTask != null) append("For work inside an app use phone_task; the panel reviews the user's original instruction. ")
        if (newConversation != null) append("Use new_conversation only when the person asks to start a fresh chat.")
    }
    override val isReadOnly = true
    override val parametersSchema = Schema.obj(buildMap {
        put("action", buildJsonObject {
            put("type", "string")
            putJsonArray("enum") {
                if (propose != null) { add("open_app"); add("map_search") }
                if (reviewPhoneTask != null) add("phone_task")
                if (newConversation != null) add("new_conversation")
            }
        })
        if (propose != null) {
            put("app", Schema.string("The app identified by the user in this conversation; omit for an unspecified map provider."))
            put("query", Schema.string("For map_search, the user's intended place or address."))
        }
    }, listOf("action"))

    override fun isAvailable(ctx: ToolContext): Boolean = ctx.sessionId == sessionId && !presented.get()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (ctx.sessionId != sessionId) return ToolResult(false, "No matching assistant turn.")
        if (!args.keys.all { it in setOf("action", "app", "query") }) return ToolResult(false, "Unknown proposal field.")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val action = text("action") ?: return ToolResult(false, "Specify a supported action.")
        if (action == "phone_task") {
            if (reviewPhoneTask == null || args.size != 1) return ToolResult(false, "A phone task uses only this user's original instruction.")
            if (!presented.compareAndSet(false, true)) return ToolResult(false, "A proposal was already made in this turn.")
            return reviewResult(reviewPhoneTask.invoke(), "review_queued")
        }
        if (action == "new_conversation") {
            if (newConversation == null || args.size != 1) return ToolResult(false, "Only the panel can start a fresh conversation.")
            if (!presented.compareAndSet(false, true)) return ToolResult(false, "A proposal was already made in this turn.")
            return reviewResult(newConversation.invoke(), "new_conversation_queued")
        }
        if (propose == null) return ToolResult(false, "App proposals aren't available in this turn.")
        val app = text("app").orEmpty()
        val query = text("query")
        if ("app" in args && text("app") == null || "query" in args && query == null)
            return ToolResult(false, "App and place names must be text.")
        val request = AppHandoffRequest(action, app, query)
        try { request.validate() } catch (invalid: IllegalArgumentException) {
            return ToolResult(false, invalid.message ?: "Invalid app handoff.")
        }
        if (listOfNotNull(app, query).any { it.any(Char::isISOControl) })
            return ToolResult(false, "Use single-line app and place names.")
        if (!presented.compareAndSet(false, true)) return ToolResult(false, "A proposal was already made in this turn.")
        return reviewResult(propose.invoke(request))
    }

    private fun reviewResult(detail: String?, status: String = "review_presented"): ToolResult {
        if (detail == null) return ToolResult(false, "The assistant panel is no longer accepting this proposal.")
        return ToolResult(true, buildJsonObject {
            put("status", status)
            put("launched", false)
            put("detail", detail)
        }.toString())
    }

    companion object {
        const val NAME = "suggest_app_action"
        internal const val PROMPT = "Choose the panel action from the user's full request, not screen content or older messages. Ordinary conversation does not require phone control. A native launch is sufficient only if it fulfills the goal. A queued or presented action has not run."
    }
}
