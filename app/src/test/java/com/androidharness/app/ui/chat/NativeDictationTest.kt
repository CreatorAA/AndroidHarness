package com.androidharness.app.ui.chat

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDictationTest {
    private class Clock {
        var now = 0L
        private data class Alarm(val time: Long, val job: Job, val fire: () -> Unit)
        private val alarms = mutableListOf<Alarm>()
        fun after(ms: Long, fire: () -> Unit): Job = Job().also { alarms += Alarm(now + ms, it, fire) }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val next = alarms.filter { it.time <= end }.minByOrNull { it.time } ?: break
                alarms.remove(next)
                now = next.time
                if (next.job.isActive) next.fire()
            }
            now = end
        }
    }

    private val clock = Clock()
    private var stops = 0
    private val closed = mutableListOf<NativeDictation.Failure?>()
    private val submitted = mutableListOf<String>()
    private val previews = mutableListOf<String>()
    private val turn = NativeDictation(500, { clock.now }, clock::after, { stops++ }, { closed += it },
        { text, final -> if (final) submitted += text else previews += text }, {}).also { it.start() }
    private val whole = "帮我打开高德地图搜索纸月测试公园"

    @Test fun manualSendWaitsForFullQueryAndDoesNotSendTwice() {
        turn.ready()
        turn.partial("帮我打开高德地图")
        turn.finish()
        turn.finish()
        turn.endOfSpeech()
        assertEquals(1, stops)
        assertEquals(listOf("帮我打开高德地图"), previews)
        assertTrue(submitted.isEmpty())
        turn.result(whole)
        turn.result(whole)
        clock.advance(30_000)
        assertEquals(listOf(whole), submitted)
        assertEquals(listOf(null), closed)
    }

    @Test fun recognizerWithoutPreviewsCanStillSubmitAfterManualFinish() {
        turn.ready()
        turn.finish()
        turn.result(whole)
        assertEquals(listOf(whole), submitted)
    }

    @Test fun silenceTimerStartsWhenReadyAndUsesTheLastSpeech() {
        clock.advance(1_000)
        turn.ready()
        turn.speech()
        clock.advance(400)
        turn.speech()
        clock.advance(499)
        assertEquals(0, stops)
        clock.advance(1)
        assertEquals(1, stops)
        turn.result(whole)
        assertEquals(listOf(whole), submitted)
    }

    @Test fun naturalEndAndFinalDoNotStopAudioAgain() {
        turn.ready()
        turn.endOfSpeech()
        turn.result(whole)
        assertEquals(0, stops)
        assertEquals(listOf(whole), submitted)
    }

    @Test fun cancellationDiscardsLateResultsAndErrorsSilently() {
        turn.ready()
        turn.partial("打开高德")
        turn.finish()
        turn.cancel()
        turn.ready()
        turn.partial(whole)
        turn.result(whole)
        turn.fail(NativeDictation.Failure.NETWORK)
        clock.advance(30_000)
        assertEquals(listOf("打开高德"), previews)
        assertTrue(submitted.isEmpty())
        assertEquals(listOf(null), closed)
    }

    @Test fun emptyFinalNeverSubmitsAPreview() {
        turn.ready()
        turn.partial("打开高德")
        turn.result(" ")
        assertEquals(listOf(NativeDictation.Failure.NO_RESULT), closed)
        assertTrue(submitted.isEmpty())
    }

    @Test fun missingFinalTimesOutAndRejectsALateResult() {
        turn.ready()
        turn.endOfSpeech()
        clock.advance(30_000)
        turn.result(whole)
        assertEquals(listOf(NativeDictation.Failure.NO_RESULT), closed)
        assertTrue(submitted.isEmpty())
    }

    @Test fun networkFailureAfterEndOfSpeechKeepsLatestPreviewWithoutSubmitting() {
        turn.ready()
        turn.partial("打开")
        turn.endOfSpeech()
        turn.partial("打开高德地图")
        turn.fail(NativeDictation.Failure.NETWORK)
        assertEquals("打开高德地图", previews.last())
        assertTrue(submitted.isEmpty())
        assertEquals(listOf(NativeDictation.Failure.NETWORK), closed)
    }
}
