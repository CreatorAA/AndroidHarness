package com.androidharness.app.data.audio

import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.VoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptionModelTest {
    @Test fun providerSelectionTakesPrecedenceAndOnlyGroqInheritsItsLegacySetting() {
        val groq = VoiceProviderConfig("groq", "Groq", VoiceProtocol.OPENAI_AUDIO, "", sttModel = "preset")
        val custom = groq.copy(id = "other")
        val old = AppSettings(groqWhisperModel = "old-choice")
        val saved = old.copy(voiceSelections = mapOf("groq" to VoiceSelection(sttModel = "turbo")))
        assertEquals("turbo", groq.transcriptionModel(saved))
        assertEquals("old-choice", groq.transcriptionModel(old))
        assertEquals("preset", custom.transcriptionModel(saved))
        assertEquals("preset", groq.transcriptionModel(old.copy(groqWhisperModel = "")))
    }
}
