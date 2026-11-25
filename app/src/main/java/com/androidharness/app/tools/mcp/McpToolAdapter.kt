package com.androidharness.app.tools.mcp

import com.androidharness.app.data.ImageStore
import kotlinx.coroutines.CancellationException
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Bridges one MCP server tool into the harness Tool surface. The exposed name
 * is the sanitized `mcp__<server>__<tool>`; the ORIGINAL name is what goes on
 * the wire. Always non-read-only, so every call goes through the harness's
 * normal permission gating.
 */
class McpToolAdapter internal constructor(
    private val serverName: String,
    private val info: McpToolInfo,
    private val connection: McpConnection,
    private val imageStore: ImageStore?,
    private val onDead: () -> Unit,
) : Tool {
    constructor(serverName: String, info: McpToolInfo, connection: McpConnection, onDead: () -> Unit = {}) :
        this(serverName, info, connection, null, onDead)

    override val name: String = McpNames.toolName(serverName, info.name)
    override val description: String =
        "[MCP server: $serverName] " + info.description.ifBlank {
            "Tool provided by the '$serverName' MCP server."
        }
    override val parametersSchema: JsonObject = normalizeMcpSchema(info.inputSchema)
    override val isReadOnly: Boolean = false
    override fun isAvailable(ctx: ToolContext): Boolean = connection.isAlive

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        if (!connection.isAlive) {
            onDead()
            return ToolResult(
                false,
                "MCP server '$serverName' is not connected (it exited or crashed). " +
                    "It will be restarted before the next run; retry then, or tell the user " +
                    "to check the server configuration in Settings → MCP servers.",
            )
        }
        return try {
            val result = McpOutput.parse(connection.callToolResult(info.name, args, 60_000))
            result.deliver(ctx.imageInputAllowed, imageStore?.let { store -> { block ->
                val image = decodeMcpRaster(block)
                store.saveEncodedImage(image.bytes, image.mime, image.width, image.height, "mcp").copy(origin = "mcp")
            } })
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolFailure) {
            if (!connection.isAlive) onDead()
            ToolResult(false, e.message ?: "MCP tool '${info.name}' failed")
        } catch (e: Exception) {
            if (!connection.isAlive) onDead()
            ToolResult(false, "MCP tool '${info.name}' failed: ${e.message}")
        }
    }
}

/** Guarantees the schema providers require: an object type at the root. */
internal fun normalizeMcpSchema(raw: JsonObject): JsonObject = when {
    raw.isEmpty() -> buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }
    raw.containsKey("type") -> raw
    else -> buildJsonObject {
        put("type", "object")
        raw.forEach { (k, v) -> put(k, v) }
    }
}
