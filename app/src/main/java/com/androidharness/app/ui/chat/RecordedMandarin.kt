package com.androidharness.app.ui.chat

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.androidharness.app.data.audio.OfflineChineseSpeech
import java.io.File
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Downsample a mono PCM stream, carrying fractional sample boundaries across codec buffers. */
internal class Mono16k(private val sourceHz: Int) {
    init { require(sourceHz >= 16_000) }
    private var phase = 0
    private var sum = 0
    private var count = 0
    fun accept(input: ShortBuffer): ShortArray {
        val output = ShortArray(input.remaining())
        var n = 0
        while (input.hasRemaining()) {
            sum += input.get(); count++; phase += 16_000
            if (phase >= sourceHz) {
                output[n++] = (sum / count).toShort()
                phase -= sourceHz; sum = 0; count = 0
            }
        }
        return output.copyOf(n)
    }
}

/** Reuse this app's completed mono AAC recording. No second upload or reopened microphone. */
internal suspend fun transcribeRecordedMandarin(context: Context, file: File): String = withContext(Dispatchers.IO) {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            OfflineChineseSpeech.open(context.applicationContext).use { model ->
                var source: Mono16k? = null
                var inputDone = false
                val info = MediaCodec.BufferInfo()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val slot = codec.dequeueInputBuffer(10_000)
                        if (slot >= 0) {
                            val input = requireNotNull(codec.getInputBuffer(slot))
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                inputDone = true
                                codec.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            } else {
                                codec.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val slot = codec.dequeueOutputBuffer(info, 10_000)
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || slot >= 0 && source == null) {
                        val pcm = codec.outputFormat
                        check(pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1 &&
                            (!pcm.containsKey(MediaFormat.KEY_PCM_ENCODING) || pcm.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_16BIT))
                        source = Mono16k(pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                    }
                    if (slot < 0) continue
                    try {
                        if (info.size > 0) {
                            val output = requireNotNull(codec.getOutputBuffer(slot))
                            output.position(info.offset); output.limit(info.offset + info.size)
                            val samples = requireNotNull(source).accept(output.order(ByteOrder.nativeOrder()).asShortBuffer())
                            if (samples.isNotEmpty()) model.accept(samples, samples.size)
                        }
                    } finally { codec.releaseOutputBuffer(slot, false) }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
                currentCoroutineContext().ensureActive()
                model.finish()
            }
        } finally { codec.release() }
    } finally { extractor.release() }
}
