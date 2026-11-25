package com.androidharness.app.tools.mcp

import com.androidharness.app.core.ImageRef
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpOutputTest {
    private val block = buildJsonObject { put("type","image"); put("mimeType","image/png"); put("data","secret-pixels") }
    private val text = buildJsonObject { put("type","text"); put("text","caption") }
    private fun output(vararg blocks: JsonObject, error: Boolean = false) = McpOutput.parse(buildJsonObject {
        put("content",JsonArray(blocks.toList())); put("isError",error)
    })

    @Test fun textAndImageKeepIndependentErrorStatus() {
        val result = output(text, block, error = true).deliver(true) { ImageRef("picture.png","image/png",width=4,height=2) }
        assertFalse(result.ok)
        assertEquals("picture.png",result.image!!.name)
        assertTrue(result.output.contains("caption"))
        assertFalse(result.output.contains("secret-pixels"))
    }

    @Test fun incapableModelAndMissingStorePreserveTextWithoutTryingToDecode() {
        val payload = output(text,block)
        val noModel = payload.deliver(false) { error("should not decode") }
        val noStore = payload.deliver(true,null)
        for (result in listOf(noModel,noStore)) {
            assertTrue(result.ok); assertNull(result.image); assertTrue(result.output.startsWith("caption"))
            assertFalse(result.output.contains("secret-pixels"))
        }
        assertTrue(noModel.output.contains("model")); assertTrue(noStore.output.contains("store"))
        assertFalse(output(block).deliver(false,null).ok)
    }

    @Test fun multipleImagesAreNotSilentlyTruncatedAndPlainTextNeedsNoStorage() {
        val result = output(block,block).deliver(true) { error("should not decode") }
        assertFalse(result.ok); assertNull(result.image); assertTrue(result.output.contains("2 images"))
        val plain = output(text).deliver(false,null)
        assertTrue(plain.ok); assertEquals("caption",plain.output)
    }

    @Test fun mimeAndWireSizeAreCheckedBeforeDecode() {
        val oversized = JsonObject(block + ("data" to JsonPrimitive("A".repeat(4*1024*1024+1))))
        val unsupported = JsonObject(block + ("mimeType" to JsonPrimitive("image/svg+xml")))
        for ((value, reason) in listOf(oversized to "4 MiB", unsupported to "PNG and JPEG", block to "invalid base64")) {
            val result = output(value).deliver(true) { decodeMcpRaster(it); error("unexpected success") }
            assertFalse(result.ok); assertTrue(result.output,result.output.contains(reason))
            assertFalse(result.output.contains("secret-pixels"))
        }
    }
}
