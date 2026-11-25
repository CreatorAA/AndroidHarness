package com.androidharness.app.agent

import com.androidharness.app.androiduse.AndroidUseSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneClarificationsTest {
    @Test fun onlyActualReplyToTheWaitingLeaseIsAccepted() = runBlocking {
        val end = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val grant = AndroidUseSession.Clarification(end, { seen.add(it) }, { error("not cancelled") })
        // “Cancel” is the requested literal button label, not a control signal.
        val req = QuestionRequest("q", "Quel libellé veux-tu vérifier ?", emptyList(), phoneClarification = true)
        val answer = PhoneClarifications.await(grant, req, { ev ->
            assertSame(req, (ev as AgentEvent.QuestionNeeded).request)
            req.response.complete(" Cancel ")
        })
        assertEquals("Cancel", answer)
        assertEquals(listOf("Cancel"), seen)
    }

    @Test fun stoppedLeaseTimeoutAndEmptyDismissalDoNotBecomeAnswers() = runBlocking {
        for (mode in listOf("stop", "timeout", "dismiss")) {
            val ended = CompletableDeferred<Unit>()
            var revoked = false
            val req = QuestionRequest("q", "用哪一个？", emptyList(), phoneClarification = true)
            val grant = AndroidUseSession.Clarification(ended, { error("This is not a task answer") }, { revoked = true })
            try {
                PhoneClarifications.await(grant, req, {
                    if (mode == "stop") ended.complete(Unit)
                    if (mode == "dismiss") req.response.complete("")
                }, timeoutMs = 80L)
                fail("A cancelled clarification cannot resume the phone loop")
            } catch (_: CancellationException) { assertTrue(revoked) }
        }
    }
}
