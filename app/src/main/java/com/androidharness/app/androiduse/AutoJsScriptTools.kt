package com.androidharness.app.androiduse

import com.androidharness.app.tools.Schema
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal fun autoJsScriptTools(phone: AndroidUseSession, includeRunner: Boolean): List<Tool> = buildList {
    add(object : Tool {
        override val name = AutoJsUiScripts.LIBRARY
        override val description = "Manage the private AutoJs6 UI script library. test is a local syntax/step preview: no phone operation. An invalid draft can be saved disabled. Reading or editing does not run a script."
        override val isReadOnly = false
        override val parametersSchema = Schema.obj(mapOf(
            "action" to buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { listOf("list","read","save","test","enable","disable","delete").forEach { add(it) } }
            },
            "name" to Schema.string("Exact saved script name."),
            "source" to Schema.string("UI script source. For test, supply either source or a saved name."),
            "enabled" to Schema.boolean("For save. Omit to preserve the enabled flag; new scripts default to enabled."),
        ), listOf("action"))
        override fun isAvailable(ctx: ToolContext) = phone.settings.autoJsEnabled
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = safe {
            check(isAvailable(ctx)) { "AutoJs6 is disabled." }
            val store = phone.scripts
            fun name() = args.string("name") ?: error("Specify the script name.")
            fun saved() = store.get(name()) ?: error("Script not found.")
            when (args.string("action")) {
                "list" -> ToolResult(true, buildJsonArray { store.entries.value.forEach { s -> add(buildJsonObject {
                    put("name", s.name); put("enabled", s.enabled)
                }) } }.toString())
                "read" -> saved().let { s -> ToolResult(true, buildJsonObject {
                    put("name", s.name); put("enabled", s.enabled); put("source", s.source)
                }.toString()) }
                "test" -> {
                    require(("source" in args) xor ("name" in args)) { "Use either source or saved name." }
                    val lines = AutoJsUiScripts.check(args.string("source") ?: saved().source)
                    ToolResult(true, buildJsonObject {
                        put("status", "validated_no_execution"); putJsonArray("steps") { lines.forEach { add(it) } }
                    }.toString())
                }
                "save" -> {
                    val n = name()
                    val enabled = if ("enabled" in args) args["enabled"]?.jsonPrimitive?.booleanOrNull
                        ?: error("enabled must be boolean.") else store.get(n)?.enabled ?: true
                    store.save(n, args.string("source") ?: error("Specify the source."), enabled)
                    ToolResult(true,"Saved locally without running.")
                }
                "enable", "disable" -> { store.setEnabled(name(), args.string("action") == "enable"); ToolResult(true,"Updated.") }
                "delete" -> { store.delete(name()); ToolResult(true,"Deleted from the library.") }
                else -> error("Unknown library action.")
            }
        }
    })
    if (includeRunner) add(object : Tool {
        override val name = AutoJsUiScripts.RUN
        override val description = "Run a saved enabled AutoJs6 UI script or an anonymous one in this task's authorized foreground app. Call only for the user's current request. A failed step stops the run. Do not retry already dispatched actions."
        override val isReadOnly = false
        override val parametersSchema = Schema.obj(mapOf(
            "name" to Schema.string("An enabled saved script; omit source."),
            "source" to Schema.string("Anonymous source; omit name. One bounded selector operation per line."),
        ))
        override fun isAvailable(ctx: ToolContext) = phone.isAutoJsScriptSession(ctx.sessionId)
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = safe {
            check(isAvailable(ctx)) { "Start a foreground phone task with AutoJs6 selected." }
            require(args.keys == setOf("name") || args.keys == setOf("source")) { "Specify exactly one saved name or anonymous source." }
            val saved = args.string("name")
            val source = saved?.let { n -> phone.scripts.get(n)?.takeIf { it.enabled }?.source ?: error("Script missing or disabled.") }
                ?: args.string("source") ?: error("Missing script source.")
            phone.runAutoJsScript(requireNotNull(ctx.sessionId), source, saved, requireScreenRoute = true)
        }
    })
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private suspend inline fun safe(action: suspend () -> ToolResult): ToolResult = try { action() }
catch (e: CancellationException) { throw e }
catch (e: Exception) { ToolResult(false, e.message ?: "Script operation failed.") }
