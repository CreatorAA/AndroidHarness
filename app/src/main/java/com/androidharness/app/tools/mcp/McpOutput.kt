package com.androidharness.app.tools.mcp

import android.graphics.BitmapFactory
import com.androidharness.app.core.ImageRef
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.Base64

/** MCP image data is never a text fallback, even for servers without image storage. */
internal data class McpOutput(val text: String, val images: List<JsonObject>, val isError: Boolean) {
    companion object {
        fun parse(result: JsonObject): McpOutput {
            val content = result["content"]
            val parts = if (content is JsonArray) content.toList() else listOfNotNull(content)
            val images = mutableListOf<JsonObject>()
            val text = parts.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull part.toString()
                when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                    "image" -> { images += obj; null }
                    "audio" -> "[MCP audio omitted]"
                    "resource" -> if ((obj["resource"] as? JsonObject)?.containsKey("blob") == true)
                        "[MCP binary resource omitted]" else obj.toString()
                    else -> (obj["text"] as? JsonPrimitive)?.contentOrNull
                        ?: if ("data" in obj || "blob" in obj) "[MCP binary content omitted]" else obj.toString()
                }
            }.joinToString("\n")
            return McpOutput(text, images, (result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }

    fun deliver(acceptsImages: Boolean, save: ((JsonObject) -> ImageRef)?): ToolResult {
        if (images.isEmpty()) return ToolResult(!isError, text.ifEmpty { "(empty response)" })
        fun omitted(reason: String): ToolResult {
            val summary = text.ifBlank { if (isError) "The MCP tool reported an error." else "The MCP tool completed." }
            return ToolResult(!isError && text.isNotBlank(), "$summary\n$reason")
        }
        if (images.size != 1) return omitted("MCP returned ${images.size} images; none attached (one image per result).")
        if (!acceptsImages) return omitted("MCP image not attached: this model does not accept image input.")
        if (save == null) return omitted("MCP image not attached: no image store is configured.")
        return try {
            val ref = save(images.single())
            ToolResult(!isError, listOf(text, "MCP image attached (${ref.width} × ${ref.height}, ${ref.mime}).")
                .filter { it.isNotBlank() }.joinToString("\n"), image = ref)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { omitted("MCP image not attached: ${e.message ?: "could not read or save the image"}.") }
    }
}

internal data class McpRaster(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

/** Bound base64 first, inspect the encoded header next, then decode only bounded pixels. */
internal fun decodeMcpRaster(block: JsonObject, maxEdge: Int = 4096, maxPixels: Int = 4_194_304): McpRaster {
    val mime = (block["mimeType"] as? JsonPrimitive)?.contentOrNull
    if (mime != "image/png" && mime != "image/jpeg") throw ToolFailure("only PNG and JPEG are supported")
    val encoded = (block["data"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolFailure("missing image data")
    if (encoded.length !in 4..4*1024*1024) throw ToolFailure("base64 image must fit the 4 MiB encoded limit")
    val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { throw ToolFailure("invalid base64") }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth; val h = bounds.outHeight
    if (bounds.outMimeType != mime) throw ToolFailure("declared image MIME does not match the encoded header")
    if (w !in 1..maxEdge || h !in 1..maxEdge || w.toLong()*h > maxPixels)
        throw ToolFailure("image exceeds the limit of $maxEdge pixels per edge or $maxPixels total pixels")
    val preview = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw ToolFailure("invalid image data")
    preview.recycle()
    return McpRaster(bytes, mime, w, h)
}
