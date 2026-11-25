package com.androidharness.app.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.androidharness.app.AppContainer
import com.androidharness.app.R
import com.androidharness.app.data.audio.VoiceGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

enum class VoiceInputMode {
    INBUILT,
    GROQ_WHISPER,
}

enum class GroqRecordState {
    IDLE,
    HOLDING,
    LOCKED,
    TRANSCRIBING,
}

class VoiceInputController(
    private val context: Context,
    private val container: AppContainer,
    private val scope: CoroutineScope,
    /**
     * Transcription lambda, injectable for tests. Production leaves it null and
     * goes through [com.androidharness.app.data.audio.VoiceGateway], which picks
     * the client from the provider's protocol.
     */
    private val transcribe: (suspend (File, String, String) -> Result<String>)? = null,
) {
    // Inbuilt state
    private var nativeTurn: PhoneSpeechSession? = null
    private var preparingNative: Job? = null
    private var nativePhase by mutableStateOf(NativeDictation.Phase.CLOSED)
    val isListeningInbuilt get() = nativePhase == NativeDictation.Phase.LISTENING
    internal val nativePending get() = nativePhase == NativeDictation.Phase.STARTING || nativePhase == NativeDictation.Phase.WAITING

    // Groq Whisper state
    var groqRecordState by mutableStateOf(GroqRecordState.IDLE)
        private set

    var rmsDb by mutableFloatStateOf(0f)
        private set

    var recordingDurationMs by mutableLongStateOf(0L)
        private set

    var levels by mutableStateOf<List<Float>>(emptyList())
        private set

    var cancelArmed by mutableStateOf(false)

    var errorMessage by mutableStateOf<String?>(null)
        private set

    internal fun clearError() { errorMessage = null }
    internal var offlineHandoff by mutableStateOf<Int?>(null)
        private set
    internal fun acknowledgeOfflineHandoff() { offlineHandoff = null }
    internal var canContinueOffline by mutableStateOf(false)
        private set

    val isListening: Boolean
        get() = isListeningInbuilt || groqRecordState == GroqRecordState.HOLDING || groqRecordState == GroqRecordState.LOCKED

    val isTranscribing: Boolean
        get() = groqRecordState == GroqRecordState.TRANSCRIBING

    private var mediaRecorder: MediaRecorder? = null
    /** A recorder still being started in the background; cleared to discard it. */
    private var startingRecorder: MediaRecorder? = null
    private var currentAudioFile: File? = null
    private var amplitudeJob: Job? = null
    private var transcriptionJob: Job? = null
    private var recordStartTime = 0L
    private var groqHasSpoken = false
    private var groqLastSpeechTime = 0L
    private var groqLastPeak = 0
    private var groqSilenceFired = false

    val isNativeAvailable: Boolean
        get() = useBundledMandarin(context) || SpeechRecognizer.isRecognitionAvailable(context)

    // -----------------------------------------------------------------------
    // Native Inbuilt SpeechRecognizer
    // -----------------------------------------------------------------------

    fun startNativeListening(onResult: (text: String, isFinal: Boolean) -> Unit, silenceTimeoutMs: Long? = null,
                             offlineMandarin: Boolean = false) {
        stopNativeListening()
        errorMessage = null
        canContinueOffline = false
        val strings = ContextCompat.getContextForLanguage(context)
        offlineHandoff = null
        nativePhase = NativeDictation.Phase.STARTING
        preparingNative = scope.launch {
            val onlyOffline = offlineMandarin || container.settings.settings.first().offlineMandarinOnly
            val turn = PhoneSpeechSession(context, scope, silenceTimeoutMs, onResult, { nativePhase = it },
                level = { value, _ -> rmsDb = value * 12f - 2f },
                switched = { offlineHandoff = R.string.voice_live_handoff },
                closed = { reason ->
                    nativeTurn = null; nativePhase = NativeDictation.Phase.CLOSED; rmsDb = 0f
                    if (reason != null) errorMessage = strings.getString(reason.messageId())
                })
            nativeTurn = turn
            turn.start(onlyOffline)
        }
    }

    internal fun finishNativeListening() {
        if (nativeTurn != null) nativeTurn?.finish() else stopNativeListening()
    }

    fun stopNativeListening() {
        preparingNative?.cancel(); preparingNative = null
        nativeTurn?.cancel(); nativeTurn = null
        nativePhase = NativeDictation.Phase.CLOSED
    }

    // -----------------------------------------------------------------------
    // Groq Whisper Recording & Transcription
    // -----------------------------------------------------------------------

    fun startGroqRecording(locked: Boolean = false, autoSilenceTimeoutMs: Long = 0L, onSilenceTimeout: (() -> Unit)? = null): Boolean {
        if (groqRecordState != GroqRecordState.IDLE) return false
        errorMessage = null
        offlineHandoff = null
        canContinueOffline = false
        cancelArmed = false
        levels = emptyList()
        groqHasSpoken = false
        groqSilenceFired = false
        groqLastPeak = 0
        groqLastSpeechTime = SystemClock.elapsedRealtime()

        val audioDir = File(context.cacheDir, "audio_recordings").apply { mkdirs() }
        val audioFile = File(audioDir, "rec_${System.currentTimeMillis()}.m4a")
        currentAudioFile = audioFile

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        // Preparing and starting the recorder waits on the media server for 100 ms or more, which
        // froze whatever was animating. It starts on a background thread; the UI shows recording
        // at once, and a stop or cancel that comes first simply discards it.
        startingRecorder = mr
        groqRecordState = if (locked) GroqRecordState.LOCKED else GroqRecordState.HOLDING
        recordStartTime = SystemClock.elapsedRealtime()
        recordingDurationMs = 0L
        scope.launch {
            val started = withContext(Dispatchers.IO) {
                runCatching {
                    mr.apply {
                        setAudioSource(MediaRecorder.AudioSource.MIC)
                        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                        setAudioChannels(1)
                        setAudioSamplingRate(44_100)
                        setAudioEncodingBitRate(128_000)
                        setOutputFile(audioFile.absolutePath)
                        prepare()
                        start()
                    }
                }
            }
            if (startingRecorder !== mr) {
                withContext(Dispatchers.IO) {
                    if (started.isSuccess) runCatching { mr.stop() }
                    runCatching { mr.release() }
                }
                return@launch
            }
            startingRecorder = null
            started.exceptionOrNull()?.let { e ->
                runCatching { mr.release() }
                currentAudioFile?.delete()
                currentAudioFile = null
                groqRecordState = GroqRecordState.IDLE
                errorMessage = ContextCompat.getContextForLanguage(context).getString(R.string.live_error_microphone, e.localizedMessage ?: e.javaClass.simpleName)
                return@launch
            }
            mediaRecorder = mr
            recordStartTime = SystemClock.elapsedRealtime()

            amplitudeJob = scope.launch {
                while (isActive && (groqRecordState == GroqRecordState.HOLDING || groqRecordState == GroqRecordState.LOCKED)) {
                    val maxAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    // Normalize amplitude 0..32767 to 0f..1f. Speech floor ~0.03
                    // (some OEMs return cumulative peak and never drop — handled below).
                    val scaled = (maxAmp / 32767f).coerceIn(0f, 1f)
                    rmsDb = scaled
                    levels = (levels + scaled).takeLast(40)
                    recordingDurationMs = SystemClock.elapsedRealtime() - recordStartTime
                    // Silence detection for auto-send (locked hands-free).
                    if (autoSilenceTimeoutMs > 0 && onSilenceTimeout != null && groqRecordState == GroqRecordState.LOCKED && !groqSilenceFired) {
                        val now = SystemClock.elapsedRealtime()
                        // Speech: loud sample OR new peak (cumulative-device fallback).
                        val isSpeech = scaled > 0.03f
                        val isNewPeak = maxAmp > groqLastPeak
                        if (isSpeech || isNewPeak) {
                            groqLastSpeechTime = now
                            if (maxAmp > 983) groqHasSpoken = true
                        }
                        if (isNewPeak) groqLastPeak = maxAmp
                        if (groqHasSpoken && now - groqLastSpeechTime >= autoSilenceTimeoutMs) {
                            groqSilenceFired = true
                            withContext(Dispatchers.Main) { onSilenceTimeout() }
                        }
                    }
                    delay(60)
                }
            }
        }
        return true
    }

    fun lockGroqRecording() {
        if (groqRecordState == GroqRecordState.HOLDING) {
            groqRecordState = GroqRecordState.LOCKED
            cancelArmed = false
        }
    }

    fun cancelGroqRecording() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        cleanupGroqRecorder()
        groqRecordState = GroqRecordState.IDLE
        cancelArmed = false
    }

    fun stopAndTranscribeGroq(
        model: String,
        onTranscription: (text: String) -> Unit,
    ) = stopAndTranscribe(model, onTranscription = onTranscription)

    /**
     * Stops the recorder and sends the clip to the configured speech-to-text
     * provider. [providerId] is the id saved in settings; a blank id falls back
     * to the first configured provider that supports transcription.
     */
    fun stopAndTranscribe(
        model: String = "",
        providerId: String? = null,
        onTranscription: (text: String) -> Unit,
    ) {
        if (groqRecordState != GroqRecordState.HOLDING && groqRecordState != GroqRecordState.LOCKED) {
            return
        }

        val duration = SystemClock.elapsedRealtime() - recordStartTime
        val file = currentAudioFile

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {
            // Stopped too quickly before frames written
            cleanupGroqRecorder()
            groqRecordState = GroqRecordState.IDLE
            missingClip()
            return
        }

        cleanupGroqRecorder(keepFile = true)

        if (file == null || !file.exists() || file.length() < 1024 || duration < 300) {
            file?.delete()
            groqRecordState = GroqRecordState.IDLE
            missingClip()
            return
        }

        groqRecordState = GroqRecordState.TRANSCRIBING
        transcriptionJob = scope.launch {
            try {
                val all = container.voiceProviders.current()
                val provider = if (providerId.isNullOrBlank()) container.voiceProviders.resolve(all, null, forTts = false)
                    else all.firstOrNull { it.id == providerId && it.supportsStt }
                if (provider == null) {
                    withContext(Dispatchers.Main) {
                        groqRecordState = GroqRecordState.IDLE
                        errorMessage = ContextCompat.getContextForLanguage(context).getString(
                            if (providerId.isNullOrBlank()) R.string.settings_voice_no_providers else R.string.settings_voice_status_missing_body,
                        )
                    }
                    return@launch
                }
                val apiKey = container.voiceProviders.apiKeyFor(provider.id).orEmpty()
                if (apiKey.isBlank() && provider.requiresKey) {
                    withContext(Dispatchers.Main) {
                        groqRecordState = GroqRecordState.IDLE
                        errorMessage = ContextCompat.getContextForLanguage(context).getString(R.string.settings_voice_status_attention_body, provider.name)
                    }
                    return@launch
                }
                val chosenModel = model.ifBlank { provider.transcriptionModel(container.settings.settings.first()) }
                val injected = transcribe
                val result = transcribeVoiceClip(file, useBundledMandarin(context), provider = { clip ->
                    if (injected != null) injected(clip, apiKey, chosenModel)
                    else VoiceGateway.transcribe(provider, apiKey, clip, chosenModel)
                }, handoff = { offlineHandoff = R.string.voice_clip_handoff }, offline = { transcribeRecordedMandarin(context, it) })
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    groqRecordState = GroqRecordState.IDLE
                    result.onSuccess { text ->
                        if (text.isNotBlank()) onTranscription(text)
                    }.onFailure { err ->
                        if (err is UnrecoveredVoiceClip) missingClip()
                        else errorMessage = err.localizedMessage ?: ContextCompat.getContextForLanguage(context).getString(R.string.live_error_transcription)
                    }
                }
            } finally {
                file.delete()
                if (currentAudioFile == file) currentAudioFile = null
            }
        }
    }

    private fun missingClip() {
        canContinueOffline = useBundledMandarin(context)
        errorMessage = ContextCompat.getContextForLanguage(context).getString(R.string.voice_clip_unavailable)
    }

    private fun cleanupGroqRecorder(keepFile: Boolean = false) {
        amplitudeJob?.cancel()
        amplitudeJob = null
        startingRecorder = null
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (_: Exception) { }
        mediaRecorder = null
        rmsDb = 0f
        recordingDurationMs = 0L

        if (!keepFile) {
            currentAudioFile?.delete()
            currentAudioFile = null
        }
    }

    fun releaseAll() {
        stopNativeListening()
        cancelGroqRecording()
    }
}

@Composable
fun rememberVoiceInputController(
    container: AppContainer,
): VoiceInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, container) {
        VoiceInputController(context, container, scope)
    }
    DisposableEffect(controller) {
        onDispose {
            controller.releaseAll()
        }
    }
    return controller
}
