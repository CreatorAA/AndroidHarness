package com.androidharness.app.data.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** On-device Mandarin dictation. Both models open directly from the installed APK. */
internal object OfflineChineseSpeech {
    private const val RATE = 16_000
    private const val DIR = "offline-speech"

    suspend fun open(context: Context): PcmTextDecoder {
        var opened: Decoder? = null
        try {
            return withContext(Dispatchers.IO) {
                val assets = context.applicationContext.assets
                val asr = OfflineRecognizer(assets, OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        paraformer = OfflineParaformerModelConfig(model = "$DIR/paraformer-zh-int8.onnx"),
                        tokens = "$DIR/tokens.txt", modelType = "paraformer", numThreads = 2,
                    ),
                ))
                try {
                    val vad = Vad(assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                        model = "$DIR/silero-vad.onnx", threshold = .30f,
                        minSilenceDuration = .65f, minSpeechDuration = .10f, maxSpeechDuration = 14f,
                    )))
                    Decoder(asr, vad).also { opened = it }
                } catch (e: Throwable) {
                    asr.release()
                    throw e
                }.also {
                    // 4.0.5/6 unpacked a read-only Vosk cache (65 MiB). No recordings lived here.
                    val root = File(context.noBackupFilesDir, DIR)
                    for (name in listOf("vosk-model-small-cn-0.22", "vosk-model-small-cn-0.22.installing")) {
                        val old = File(root, name)
                        try { if (old.isDirectory) old.deleteRecursively() } catch (_: SecurityException) { }
                    }
                }
            }
        } catch (e: Throwable) {
            // If cancellation wins on dispatch back to the caller, it never receives this decoder.
            opened?.close()
            throw e
        }
    }

    /** Paraformer decodes a phrase, not a stream: show revisable previews and commit at real pauses. */
    private class Decoder(private val asr: OfflineRecognizer, private val vad: Vad) : PcmTextDecoder {
        private val committed = StringBuilder()
        private val pcm = FloatArray(RATE * 20 + FRAME)
        private var size = 0
        private var speaking = false
        private var nextPreview = RATE
        private var visible = ""
        private var final: String? = null
        private var closed = false

        override fun accept(samples: ShortArray, length: Int): String {
            check(!closed && final == null)
            require(length in 1..samples.size)
            var pos = 0
            // The microphone delivers 100 ms; a recovered cloud recording may supply larger frames.
            while (pos < length) {
                val n = minOf(FRAME, length - pos)
                val frame = FloatArray(n) { samples[pos + it] / 32768f }
                frame.copyInto(pcm, size)
                size += n
                pos += n
                vad.acceptWaveform(frame)
                when {
                    !vad.empty() -> {
                        // Decode the buffered original, not VAD-trimmed phonemes.
                        while (!vad.empty()) vad.pop()
                        commit(size)
                    }
                    vad.isSpeechDetected() || speaking -> {
                        speaking = true
                        if (size >= RATE * 20) {
                            // Continuous speech: split at the quietest 32 ms in the last two seconds.
                            // Keep the unconsumed tail for the next phrase; no capture is dropped.
                            val split = quietBoundary()
                            commit(split)
                            vad.reset()
                            if (size != 0) vad.acceptWaveform(pcm.copyOf(size))
                            speaking = size != 0
                        } else if (size >= nextPreview) {
                            val partial = decode(size)
                            if (partial.isNotEmpty()) visible = MandarinNumbers.format(committed.toString() + partial)
                            nextPreview = size + maxOf(RATE * 3 / 2, size / 2)
                        }
                    }
                    size > PREROLL + FRAME -> {
                        pcm.copyInto(pcm, 0, size - PREROLL, size)
                        size = PREROLL
                    }
                }
            }
            return visible
        }

        private fun quietBoundary(): Int {
            var best = size
            var lowest = Double.POSITIVE_INFINITY
            for (i in (size - RATE * 2)..(size - 512) step 256) {
                var energy = 0.0
                for (j in i until i + 512) energy += pcm[j] * pcm[j]
                if (energy < lowest) { lowest = energy; best = i + 256 }
            }
            return best
        }

        private fun decode(count: Int): String {
            val stream = asr.createStream()
            try {
                stream.acceptWaveform(pcm.copyOf(count), RATE)
                asr.decode(stream)
                return asr.getResult(stream).text.trim()
            } finally { stream.release() }
        }

        private fun commit(count: Int) {
            committed.append(decode(count))
            visible = MandarinNumbers.format(committed.toString())
            pcm.copyInto(pcm, 0, count, size)
            size -= count
            nextPreview = RATE
            speaking = false
        }

        override fun finish(): String {
            check(!closed)
            final?.let { return it }
            vad.flush()
            if (size > 0 && (speaking || !vad.empty())) commit(size)
            return visible.also { final = it }
        }

        override fun close() {
            if (!closed) {
                closed = true
                try { vad.release() } finally { asr.release() }
            }
        }
        companion object {
            private const val FRAME = RATE / 10
            private const val PREROLL = RATE * 4 / 5
        }
    }
}
