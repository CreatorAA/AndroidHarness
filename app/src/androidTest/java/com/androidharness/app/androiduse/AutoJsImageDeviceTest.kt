package com.androidharness.app.androiduse

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Exercises the real Android decoder without an external companion or device permissions. */
@RunWith(AndroidJUnit4::class)
class AutoJsImageDeviceTest {
    @Test fun typedJpegIsDeliveredAtItsActualDimensions() {
        val bitmap = Bitmap.createBitmap(48, 80, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try {
            bitmap.eraseColor(Color.CYAN)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output))
        } finally { bitmap.recycle() }
        val jpeg = output.toByteArray()
        fun result(width: Int, height: Int) = buildJsonObject {
            putJsonArray("content") {
                add(buildJsonObject { put("type", "image"); put("mimeType", "image/jpeg"); put("data", Base64.getEncoder().encodeToString(jpeg)) })
            }
            putJsonObject("structuredContent") {
                put("width", width); put("height", height); put("bytes", jpeg.size); put("mime", "image/jpeg")
            }
        }
        val image = autoJsImage(result(48, 80), 96, 160)
        assertEquals(48, image.width); assertEquals(80, image.height)
        assertArrayEquals(jpeg, image.bytes)
        val inaccurate = runCatching { autoJsImage(result(24, 40), 96, 160) }
        assertTrue(inaccurate.exceptionOrNull()?.message.orEmpty().contains("metadata disagree"))
    }
}
