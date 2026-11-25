package com.androidharness.app.androiduse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PhoneWindowPrivacyDeviceTest {
    @Test fun keepTheAppButNotTheKeyboardOrDisplayMargins() {
        val app = Json.parseToJsonElement("""{"layer":1,"bounds":{"left":0,"top":16,"right":128,"bottom":112}}""").jsonObject
        val keyboard = Json.parseToJsonElement("""{"layer":2,"bounds":{"left":0,"top":80,"right":128,"bottom":112}}""").jsonObject
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val bytes = ByteArrayOutputStream()
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes) } finally { bitmap.recycle() }
        val frame = PhoneImage(bytes.toByteArray(), "image/png", 64, 64)
        val preview = requireNotNull(maskAutoJsWindow(frame, 128, 128, app, listOf(app, keyboard)))
        val result = BitmapFactory.decodeByteArray(preview.bytes, 0, preview.bytes.size)
        try {
            assertEquals(64, result.height)
            assertTrue(Color.red(result.getPixel(32, 4)) < 10)
            assertTrue(Color.red(result.getPixel(32, 24)) > 245)
            assertTrue(Color.red(result.getPixel(32, 47)) < 10)
            assertTrue(Color.red(result.getPixel(32, 61)) < 10)
        } finally { result.recycle() }
        val unknown = Json.parseToJsonElement("""{"layer":2}""").jsonObject
        assertNull(maskAutoJsWindow(frame, 128, 128, app, listOf(app, unknown)))
    }
}
