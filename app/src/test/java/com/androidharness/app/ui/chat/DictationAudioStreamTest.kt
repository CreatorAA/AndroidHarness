package com.androidharness.app.ui.chat

import com.androidharness.app.data.audio.PcmTextDecoder
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class DictationAudioStreamTest {
    @Test fun coldDecoderReceivesOpeningWordsAndPartialTailBeforeFinal() = runBlocking {
        val stream = DictationAudioStream()
        val heard = CompletableDeferred<Unit>()
        val warm = CompletableDeferred<Unit>()
        var position = 0
        val closed = AtomicBoolean(false)
        val read = mutableListOf<Int>()
        val result = async {
            stream.run(microphone = { object : PcmInput {
                override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                    val n = minOf(length, 1647 - position)
                    for (i in 0 until n) buffer[offset + i] = (++position).toShort()
                    if (position == 1647) heard.complete(Unit)
                    return n
                }
                override fun close() { closed.set(true) }
            } }, decoder = {
                warm.await()
                object : PcmTextDecoder {
                    override fun accept(samples: ShortArray, length: Int): String {
                        read += samples.take(length).map { it.toInt() }
                        return "中间结果"
                    }
                    override fun finish() = "完成"
                    override fun close() {}
                }
            }, ready = {}, level = {}, preview = {})
        }
        withTimeout(3000) { heard.await() }
        stream.finish()
        warm.complete(Unit)
        assertEquals("完成", withTimeout(3000) { result.await() })
        assertTrue(closed.get())
        assertEquals((1..1647).toList(), read)
    }

    @Test fun cancellingStopsCaptureAndDoesNotFinalizeOrReopenTheMic() = runBlocking {
        val capturing = CompletableDeferred<Unit>()
        val decoding = CompletableDeferred<Unit>()
        val released = AtomicBoolean(false)
        val closed = AtomicBoolean(false)
        val stream = DictationAudioStream()
        val run = launch {
            stream.run(microphone = { object : PcmInput {
                override fun read(buffer: ShortArray, offset: Int, length: Int): Int { capturing.complete(Unit); return 0 }
                override fun close() { released.set(true) }
            } }, decoder = { object : PcmTextDecoder {
                init { decoding.complete(Unit) }
                override fun accept(samples: ShortArray, length: Int) = error("No speech")
                override fun finish() = error("Cancellation must not send")
                override fun close() { closed.set(true) }
            } }, ready = {}, level = {}, preview = {})
        }
        withTimeout(3000) { capturing.await(); decoding.await(); run.cancelAndJoin() }
        assertTrue(released.get()); assertTrue(closed.get())
    }
}
