package com.androidharness.app.data.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OfflineChineseSpeechDeviceTest {
    @Test fun recognizesBothSidesOfLongPauseFromBundledMandarinModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Fixed 16-kHz mono PCM: two Huihui utterances and 40,000 samples of silence in between.
        val wav = instrumentation.context.assets.open("offline-speech/zh-short-pause.wav").use { it.readBytes() }
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val chunk = ShortArray(1600)
        var sawFirst = false
        OfflineChineseSpeech.open(instrumentation.targetContext).use { decoder ->
            while (pcm.hasRemaining()) {
                val count = minOf(chunk.size, pcm.remaining())
                pcm.get(chunk, 0, count)
                if (decoder.accept(chunk, count).contains("3点")) sawFirst = true
            }
            assertTrue("Missing live transcription of the first phrase", sawFirst)
            assertEquals("今天下午3点我们一起去公园散步", decoder.finish())
            assertEquals("今天下午3点我们一起去公园散步", decoder.finish())
        }
    }
}
