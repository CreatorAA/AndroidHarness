package com.androidharness.app.ui.chat

import com.androidharness.app.data.audio.PcmTextDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.sqrt

/** A 16 kHz mono PCM16 microphone. A nonblocking read returns zero when it needs more time. */
internal interface PcmInput : Closeable {
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
}

/** Capture immediately, including while the model opens. Only a deliberate finish flushes a final. */
internal class DictationAudioStream {
    @Volatile private var finishing = false
    fun finish() { finishing = true }

    suspend fun run(
        microphone: () -> PcmInput,
        decoder: suspend () -> PcmTextDecoder,
        ready: suspend () -> Unit,
        level: (rms: Float) -> Unit,
        preview: suspend (String) -> Unit,
    ): String = coroutineScope {
        // At most ~1.9 MB / 60 seconds of pending audio. Never silently drop a phrase if decoding stalls.
        val queue = Channel<ShortArray>(600)
        launch(Dispatchers.IO) {
            try {
                microphone().use { mic ->
                    ready()
                    var frame = ShortArray(1600)
                    var fill = 0
                    fun emit(chunk: ShortArray) {
                        check(queue.trySend(chunk).isSuccess) { "Audio decoder fell behind." }
                        level(sqrt(chunk.sumOf { it.toDouble() * it } / chunk.size).toFloat())
                    }
                    while (isActive && !finishing) {
                        val n = mic.read(frame, fill, frame.size - fill)
                        check(n >= 0) { "Audio input stopped." }
                        if (n == 0) { delay(10); continue }
                        fill += n
                        if (fill == frame.size) { emit(frame); frame = ShortArray(1600); fill = 0 }
                    }
                    if (isActive && fill > 0) emit(frame.copyOf(fill))
                }
            } finally { queue.close() }
        }
        withContext(Dispatchers.IO) {
            decoder().use { model ->
                var previous = ""
                for (frame in queue) {
                    val text = model.accept(frame, frame.size)
                    if (text.isNotBlank() && text != previous) { previous = text; preview(text) }
                }
                model.finish()
            }
        }
    }
}
