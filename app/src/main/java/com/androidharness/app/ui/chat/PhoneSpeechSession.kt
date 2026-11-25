package com.androidharness.app.ui.chat

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.androidharness.app.assistant.SpeechRecognizers
import com.androidharness.app.data.audio.MandarinNumbers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** System recognizers do not hand us the original audio. Keep their latest words as a prefix
 * when switching microphones, never report them as a newly recognized final result. */
internal class SpeechHandoff {
    var draft = ""
        private set
    private var accepting = true
    fun heard(words: String) { draft = words }
    fun stop() { accepting = false }
    fun mayContinue(failure: NativeDictation.Failure?) = accepting && when (failure) {
        NativeDictation.Failure.NETWORK, NativeDictation.Failure.SERVICE, NativeDictation.Failure.NO_RESULT -> true
        else -> false
    }
    fun joined(next: String): String {
        if (draft.isEmpty() || next.isEmpty()) return draft + next
        val space = draft.last().code in 33..126 && next.first().code in 33..126
        return if (space) "$draft $next" else draft + next
    }
}

internal class SpeechCooldown(private val now: () -> Long) {
    private var until = 0L
    fun recentlyFailed() = now() < until
    fun failed() { until = now() + 60_000 }
}

internal interface SystemSpeechInput {
    fun start(intent: Intent, listener: RecognitionListener)
    fun stop()
    fun close()
}

private fun androidSpeech(ctx: Context): SystemSpeechInput? {
    if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return null
    val sr = SpeechRecognizers.create(ctx)
    return object : SystemSpeechInput {
        override fun start(intent: Intent, listener: RecognitionListener) { sr.setRecognitionListener(listener); sr.startListening(intent) }
        override fun stop() = sr.stopListening()
        override fun close() = sr.destroy()
    }
}

/** Main-thread only. A single turn can switch from a failed system service to a local microphone once. */
internal class PhoneSpeechSession(
    private val ctx: Context, private val scope: CoroutineScope, private val silence: Long?,
    private val onText: (String, Boolean) -> Unit, private val phase: (NativeDictation.Phase) -> Unit,
    private val level: (Float, Boolean) -> Unit, private val switched: () -> Unit,
    private val closed: (NativeDictation.Failure?) -> Unit,
    private val system: (Context) -> SystemSpeechInput? = ::androidSpeech,
    private val microphone: () -> PcmInput = ::mandarinMicrophone,
    private val backoff: SpeechCooldown = sharedCooldown,
) {
    private var active: NativeDictation? = null
    private val handoff = SpeechHandoff()
    fun start(onlyOffline: Boolean) {
        if (onlyOffline) { local(); return }
        val mandarin = useBundledMandarin(ctx)
        if (mandarin && (backoff.recentlyFailed() || ctx.getSystemService(ConnectivityManager::class.java).activeNetwork == null)) {
            local(); return
        }
        fun unavailable(failure: NativeDictation.Failure) {
            if (mandarin && handoff.mayContinue(failure)) {
                // An OEM may end listening before the speaker begins. Continue this turn locally,
                // but silence alone says nothing about whether the service is broken next time.
                if (failure != NativeDictation.Failure.NO_RESULT) backoff.failed()
                switched(); local()
            }
            else closed(failure)
        }
        val sr = try {
            system(ctx) ?: run { unavailable(NativeDictation.Failure.SERVICE); return }
        } catch (_: SecurityException) { closed(NativeDictation.Failure.PERMISSION); return }
          catch (_: RuntimeException) { unavailable(NativeDictation.Failure.SERVICE); return }
        val turn = NativeDictation(silence, SystemClock::elapsedRealtime,
            { ms, action -> scope.launch { delay(ms); action() } },
            { handoff.stop(); sr.stop() },
            { failure ->
                active = null
                runCatching { sr.close() }
                if (failure != null) unavailable(failure) else closed(null)
            }, { raw, final ->
                val words = if (mandarin) MandarinNumbers.format(raw) else raw
                if (!final) handoff.heard(words)
                onText(words, final)
            }, phase, timeoutFailure = NativeDictation.Failure.SERVICE)
        active = turn
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (mandarin) putExtra(RecognizerIntent.EXTRA_LANGUAGE,
                ContextCompat.getContextForLanguage(ctx).resources.configuration.locales[0].toLanguageTag())
            silence?.takeIf { it > 0 }?.let {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, it)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, it / 2)
                putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", it)
                putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", it / 2)
            }
        }
        try {
            turn.start()
            sr.start(intent, object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = turn.ready()
                override fun onBeginningOfSpeech() = turn.speech()
                override fun onEndOfSpeech() = turn.endOfSpeech()
                override fun onRmsChanged(rmsdB: Float) {
                    if (active !== turn) return
                    if (rmsdB > 8f) turn.speech()
                    level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f), rmsdB > 8f)
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) = turn.fail(when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> NativeDictation.Failure.PERMISSION
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> NativeDictation.Failure.NETWORK
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NativeDictation.Failure.NO_RESULT
                    else -> NativeDictation.Failure.SERVICE
                })
                override fun onPartialResults(partialResults: Bundle?) = turn.partial(partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onResults(results: Bundle?) = turn.result(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            })
        } catch (_: SecurityException) { turn.fail(NativeDictation.Failure.PERMISSION) }
          catch (_: RuntimeException) { turn.fail(NativeDictation.Failure.SERVICE) }
    }
    private fun local() {
        active = scope.listenMandarin(ctx, silence, { words, final -> onText(handoff.joined(words), final) },
            phase, level, closed, microphone)
    }
    fun finish() { handoff.stop(); active?.finish() }
    fun cancel() { handoff.stop(); active?.cancel(); active = null }
    companion object { private val sharedCooldown = SpeechCooldown(SystemClock::elapsedRealtime) }
}
