package com.androidharness.app.data.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class SpokenNumbersDeviceTest {
    @Test fun realMandarinAudioShowsDigitsInTheDraftBeforeChat() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val examples = mapOf(
            "standalone" to "120", "short" to "9 × 19", "calc" to "打开计算器然后计算9 × 19",
            "code" to "编号是00729", "phone" to "电话号码是13800138000",
            "signed" to "计算-12.5 ÷ 2.5", "plain" to "他们相识已久一起去五一广场",
        )
        for ((name, expected) in examples) {
            val wave = ins.context.assets.open("mandarin-numbers/$name.wav").use { it.readBytes() }
            // SAPI may include auxiliary RIFF chunks; only use the PCM16 data chunk.
            val b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12
            while (String(wave, offset, 4, Charsets.US_ASCII) != "data") {
                val size = b.getInt(offset + 4)
                offset += 8 + size + (size and 1)
            }
            val pcm = ByteBuffer.wrap(wave, offset+8, b.getInt(offset+4)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val chunk = ShortArray(1600)
            OfflineChineseSpeech.open(ins.targetContext).use { decoder ->
                while (pcm.hasRemaining()) {
                    val n = minOf(pcm.remaining(), chunk.size)
                    pcm.get(chunk, 0, n); decoder.accept(chunk, n)
                }
                assertEquals("Actual microphone-format recording: $name", expected, decoder.finish())
            }
        }
    }
}
