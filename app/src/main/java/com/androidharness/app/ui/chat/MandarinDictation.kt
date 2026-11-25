package com.androidharness.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.androidharness.app.R
import com.androidharness.app.data.audio.OfflineChineseSpeech
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun useBundledMandarin(context: Context): Boolean {
    val locale = ContextCompat.getContextForLanguage(context).resources.configuration.locales[0]
    return useBundledMandarin(locale)
}

internal fun useBundledMandarin(locale: Locale) = locale.language in setOf("zh", "cmn") &&
    locale.country !in setOf("HK", "MO") && !locale.toLanguageTag().contains("yue", ignoreCase = true)

@SuppressLint("MissingPermission") // The user-visible callers request RECORD_AUDIO; revocation is reported below.
internal fun mandarinMicrophone(): PcmInput {
    val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(min > 0)
    val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16_000))
    try {
        check(record.state == AudioRecord.STATE_INITIALIZED)
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
    } catch (e: Exception) { record.release(); throw e }
    return object : PcmInput {
        override fun read(buffer: ShortArray, offset: Int, length: Int) =
            record.read(buffer, offset, length, AudioRecord.READ_NON_BLOCKING)
        override fun close() { try { record.stop() } finally { record.release() } }
    }
}

/** Uses the same final/partial contract as system speech; a pause never commits a manual dictation. */
internal fun CoroutineScope.listenMandarin(
    context: Context, silenceMs: Long?, onText: (String, Boolean) -> Unit,
    onPhase: (NativeDictation.Phase) -> Unit, onLevel: (Float, Boolean) -> Unit,
    onClosed: (NativeDictation.Failure?) -> Unit, microphone: () -> PcmInput = ::mandarinMicrophone,
): NativeDictation {
    var job: Job? = null
    val stream = DictationAudioStream()
    val turn = NativeDictation(silenceMs, SystemClock::elapsedRealtime,
        { ms, task -> launch { delay(ms); task() } }, stream::finish,
        { reason -> job?.cancel(); onClosed(reason) }, onText, onPhase, resultTimeoutMs = 60_000)
    turn.start()
    job = launch {
        var noise = 80f
        try {
            val final = stream.run(microphone, { OfflineChineseSpeech.open(context.applicationContext) },
                ready = { withContext(Dispatchers.Main.immediate) { turn.ready() } },
                level = { rms ->
                    val spoken = rms > maxOf(220f, noise * 2.7f)
                    if (!spoken) noise = .95f * noise + .05f * rms
                    launch(Dispatchers.Main.immediate) {
                        if (spoken) turn.speech()
                        onLevel((rms / 3500f).coerceIn(0f, 1f), spoken)
                    }
                },
                preview = { text -> withContext(Dispatchers.Main.immediate) { turn.partial(text) } })
            turn.result(final)
        } catch (e: CancellationException) { throw e }
        catch (_: SecurityException) { turn.fail(NativeDictation.Failure.PERMISSION) }
        catch (_: Exception) { turn.fail(NativeDictation.Failure.LOCAL) }
        catch (_: LinkageError) { turn.fail(NativeDictation.Failure.LOCAL) }
    }
    return turn
}

internal fun NativeDictation.Failure.messageId() = when (this) {
    NativeDictation.Failure.NO_RESULT -> R.string.voice_no_result
    NativeDictation.Failure.NETWORK, NativeDictation.Failure.SERVICE -> R.string.voice_service_interrupted
    NativeDictation.Failure.PERMISSION -> R.string.assistant_mic_permission
    NativeDictation.Failure.LOCAL -> R.string.voice_local_stopped
}
