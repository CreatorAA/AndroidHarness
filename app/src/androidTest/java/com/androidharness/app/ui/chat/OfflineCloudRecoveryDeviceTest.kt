package com.androidharness.app.ui.chat

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.audio.OfflineChineseSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class OfflineCloudRecoveryDeviceTest {
    /** Actual on-device decoder; fixed synthetic Mandarin, not a recording of a human talking. */
    @Test fun oneMinuteOfMandarinKeepsFirstMiddleAndLastPhrasesUntilFinish() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val wav = ins.context.assets.open("offline-speech/zh-short-pause.wav").use { it.readBytes() }
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val pcm = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frame = ShortArray(1600)
        val silent = ShortArray(1600)
        var samples = 0L
        val pair = Regex("下午.*?公园")
        fun pairs(text: String) = pair.findAll(text).count()
        OfflineChineseSpeech.open(ins.targetContext).use { decoder ->
            var partial = ""
            repeat(7) { group ->
                pcm.rewind()
                while (pcm.hasRemaining()) {
                    val n = minOf(frame.size, pcm.remaining())
                    pcm.get(frame, 0, n)
                    partial = decoder.accept(frame, n)
                    samples += n
                }
                if (group < 6) repeat(10) {
                    partial = decoder.accept(silent, silent.size)
                    samples += silent.size
                }
                if (group == 0 || group == 3) assertEquals("Cumulative preview after group " + (group + 1) + ": " + partial,
                    group + 1, pairs(partial))
            }
            val final = decoder.finish()
            assertTrue("First and last words: " + final, final.startsWith("今天下午") && final.endsWith("散步"))
            assertEquals("Final must include all seven ordered pairs: " + final, 7, pairs(final))
            assertEquals(1_017_914L, samples)
            ins.sendStatus(0, Bundle().apply { putString("offlineLongEvidence",
                "Synthetic Mandarin: " + samples + " mono samples / 16000 Hz = " + samples / 16000.0 +
                    " s; cumulative pairs 1, 4, 7 and one final result.") })
        }
    }

    @Test fun cloudTimeoutRecoversBothSidesOfPauseFromTheSameAacClip() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val clip = File.createTempFile("offline-test-", ".m4a", ins.targetContext.cacheDir)
        try {
            ins.context.assets.open("offline-speech/cloud-clip.m4a").use { input -> clip.outputStream().use { input.copyTo(it) } }
            var attempts = 0
            var switched = false
            val result = transcribeVoiceClip(clip, mandarin = true, provider = {
                attempts++; assertSame(clip, it); Result.failure(SocketTimeoutException("fixture"))
            }, handoff = { switched = true }, offline = {
                assertSame(clip, it)
                transcribeRecordedMandarin(ins.targetContext, it)
            }).getOrThrow()
            assertEquals(1, attempts); assertTrue(switched)
            assertTrue(result, "下午" in result && "公园" in result && result.indexOf("下午") < result.indexOf("公园"))
            ins.sendStatus(0, Bundle().apply { putString("offlineTranscript", result) })
        } finally { clip.delete() }
    }

    @Test fun successProviderChoiceAndCancellationNeverFallBack() = runBlocking {
        val file = File("never-read")
        suspend fun run(mandarin: Boolean, selected: Result<String>) = transcribeVoiceClip(file, mandarin,
            provider = { selected }, handoff = { error("Unexpected handoff") }, offline = { error("Unexpected decoder") })
        assertEquals("chosen provider", run(true, Result.success("chosen provider")).getOrThrow())
        assertTrue(run(false, Result.failure(SocketTimeoutException())).exceptionOrNull() is SocketTimeoutException)
        try { run(true, Result.failure(CancellationException())); fail("Cancellation was swallowed") }
        catch (_: CancellationException) {}
    }
}
