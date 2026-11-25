package com.androidharness.app.ui.chat

import kotlinx.coroutines.Job

/** One native recognition request. Stopping the microphone is not a recognition result. */
internal class NativeDictation(
    private val silenceMs: Long?,
    private val now: () -> Long,
    private val after: (Long, () -> Unit) -> Job,
    private val stopAudio: () -> Unit,
    private val closed: (Failure?) -> Unit,
    private val onText: (String, Boolean) -> Unit,
    private val onPhase: (Phase) -> Unit,
    private val resultTimeoutMs: Long = 15_000L,
    private val timeoutFailure: Failure = Failure.NO_RESULT,
) {
    enum class Phase { STARTING, LISTENING, WAITING, CLOSED }
    enum class Failure { NO_RESULT, NETWORK, PERMISSION, LOCAL, SERVICE }

    private var phase = Phase.STARTING
        set(value) { field = value; onPhase(value) }
    private var lastSpeech: Long? = null
    private var timer: Job? = null

    fun start() {
        onPhase(Phase.STARTING)
        arm(resultTimeoutMs) { fail(timeoutFailure) }
    }

    fun ready() {
        if (phase != Phase.STARTING) return
        phase = Phase.LISTENING
        timer?.cancel()
        silenceMs?.takeIf { it > 0 }?.let { arm(it, ::checkSilence) }
    }

    fun speech() {
        if (phase == Phase.LISTENING) lastSpeech = now()
    }

    fun partial(text: String?) {
        // A service can refine its partial after onEndOfSpeech and then fail without a final.
        // Keep that latest text editable; it still cannot submit or extend the final-result timer.
        if (phase == Phase.CLOSED || text.isNullOrBlank()) return
        speech()
        onText(text.trim(), false)
    }

    private fun checkSilence() {
        val timeout = silenceMs ?: return
        val remaining = lastSpeech?.let { timeout - (now() - it) } ?: timeout
        if (remaining <= 0) finish() else arm(remaining, ::checkSilence)
    }

    /** User or silence timer ended input. Wait for the recognizer's full transcript. */
    fun finish() {
        if (!waitForFinal()) return
        try { stopAudio() } catch (_: Exception) { fail(Failure.NO_RESULT) }
    }

    /** The service itself ended input, so do not call stopListening() a second time. */
    fun endOfSpeech() { waitForFinal() }

    private fun waitForFinal(): Boolean {
        if (phase == Phase.WAITING || phase == Phase.CLOSED) return false
        phase = Phase.WAITING
        arm(resultTimeoutMs) { fail(timeoutFailure) }
        return true
    }

    fun result(raw: String?) {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) fail(Failure.NO_RESULT)
        else if (close(null)) onText(text, true)
    }

    fun fail(reason: Failure) { close(reason) }
    fun cancel() { close(null) }

    private fun close(reason: Failure?): Boolean {
        if (phase == Phase.CLOSED) return false
        phase = Phase.CLOSED
        timer?.cancel()
        closed(reason)
        return true
    }

    private fun arm(ms: Long, work: () -> Unit) {
        timer?.cancel()
        timer = after(ms, work)
    }

}
