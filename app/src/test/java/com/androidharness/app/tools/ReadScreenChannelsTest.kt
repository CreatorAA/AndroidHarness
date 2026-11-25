package com.androidharness.app.tools

import com.androidharness.app.core.ImageRef
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReadScreenChannelsTest {
    private val sid = "snapshot-channel-test"
    private val image = ImageRef("invocation.png", "image/png")
    private suspend fun read(args: JsonObject, vision: Boolean = true) = ReadScreenTool().execute(
        args, ToolContext(FileFs(File(".")), sessionId = sid, imageInputAllowed = vision))

    @After fun clear() = SharedScreens.clear(sid)

    @Test fun structuredTextDoesNotSaveOrRecognizePixelsEvenForATextOnlyModel() = runBlocking {
        SharedScreens.put(sid, SharedScreen {
            SharedScreen.Content("Tuesday", { error("Image was not requested") }, { error("OCR was not requested") })
        })
        for (vision in listOf(false, true)) {
            val result = read(buildJsonObject { put("image", false) }, vision)
            assertTrue(result.ok)
            assertEquals("Tuesday", result.output)
            assertNull(result.image)
        }
    }

    @Test fun screenshotOnlyDoesNotSubstituteTheAppTree() = runBlocking {
        SharedScreens.put(sid, SharedScreen { SharedScreen.Content("private tree text", { image }, { "printed label" }) })
        val onlyShot = buildJsonObject { put("text", false) }
        val pixels = read(onlyShot)
        assertEquals(image, pixels.image)
        assertFalse(pixels.output.contains("private tree text"))
        val ocr = read(onlyShot, vision = false)
        assertNull(ocr.image)
        assertEquals(ReadScreenTool.screenshotTextBlock("printed label"), ocr.output)
        SharedScreens.put(sid, SharedScreen { SharedScreen.Content("private tree text", null) })
        val missing = read(onlyShot)
        assertFalse(missing.ok)
        assertFalse(missing.output.contains("private tree text"))
    }

    @Test fun defaultKeepsBothChannelsForExistingCallers() = runBlocking {
        SharedScreens.put(sid, SharedScreen { SharedScreen.Content("Tuesday", { image }) })
        val result = read(buildJsonObject {})
        assertTrue(result.ok)
        assertEquals("Tuesday", result.output)
        assertEquals(image, result.image)
    }
}
