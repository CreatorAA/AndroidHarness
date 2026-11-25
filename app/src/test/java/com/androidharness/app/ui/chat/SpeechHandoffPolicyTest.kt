package com.androidharness.app.ui.chat

import org.junit.Assert.*
import org.junit.Test

class SpeechHandoffPolicyTest {
    @Test fun selectedCloudWritesMandarinNumbersWithoutOpeningTheOfflineDecoder() = kotlinx.coroutines.runBlocking {
        val text = transcribeVoiceClip(java.io.File("not-opened"), true, provider = { Result.success("编号零零二") },
            handoff = { error("Cloud succeeded") }, offline = { error("Unexpected second transcription") })
        assertEquals("编号002", text.getOrThrow())
    }
    @Test fun nextLocalSegmentIsAppendedToTheLastSystemRevisionOnly() {
        val h = SpeechHandoff()
        h.heard("去公"); h.heard("去公园")
        assertEquals("去公园散步", h.joined("散步"))
        assertTrue(h.mayContinue(NativeDictation.Failure.NETWORK))
        assertTrue(h.mayContinue(NativeDictation.Failure.SERVICE))
        assertFalse(h.mayContinue(NativeDictation.Failure.PERMISSION))
        h.stop()
        assertFalse(h.mayContinue(NativeDictation.Failure.NETWORK))
    }
    @Test fun laterDictationsRetryThePlatformWhenTheCooldownExpires() {
        var now = 7L
        val cooldown = SpeechCooldown { now }
        assertFalse(cooldown.recentlyFailed())
        cooldown.failed(); now += 59_999
        assertTrue(cooldown.recentlyFailed())
        now++; assertFalse(cooldown.recentlyFailed())
    }
}
