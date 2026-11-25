package com.androidharness.app.ui.chat

import com.androidharness.app.data.audio.OpenAiAudioClient
import com.androidharness.app.data.audio.MandarinNumbers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

internal class UnrecoveredVoiceClip : Exception()

/** The selected provider gets the recording once. A transport outage can reuse the same local bytes. */
internal suspend fun transcribeVoiceClip(
    clip: File, mandarin: Boolean, provider: suspend (File) -> Result<String>,
    handoff: () -> Unit, offline: suspend (File) -> String,
): Result<String> {
    val first = try { provider(clip) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    currentCoroutineContext().ensureActive()
    val error = first.exceptionOrNull() ?: return if (mandarin) first.map(MandarinNumbers::format) else first
    if (error is CancellationException) throw error
    val outage = when (error) {
        is OpenAiAudioClient.VoiceApiException -> error.status in 500..599 || error.status == 408 || error.status == 429
        else -> error is IOException
    }
    if (!mandarin || !outage) return first
    if (!clip.isFile || clip.length() == 0L) return Result.failure(UnrecoveredVoiceClip())
    handoff()
    return try {
        val text = offline(clip)
        currentCoroutineContext().ensureActive()
        if (text.isBlank()) Result.failure(UnrecoveredVoiceClip()) else Result.success(text)
    } catch (e: CancellationException) { throw e }
      catch (_: Exception) { Result.failure(UnrecoveredVoiceClip()) }
      catch (_: LinkageError) { Result.failure(UnrecoveredVoiceClip()) }
}
