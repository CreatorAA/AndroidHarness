package com.androidharness.app.agent

import com.androidharness.app.androiduse.AndroidUseSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** A missing task argument can pause a live foreground run. A completed chat never establishes this state. */
internal object PhoneClarifications {
    suspend fun await(
        grant: AndroidUseSession.Clarification, request: QuestionRequest,
        emit: suspend (AgentEvent) -> Unit, timeoutMs: Long = 5 * 60_000L,
    ): String {
        if (grant.ended.isCompleted) throw CancellationException("Phone task has ended.")
        emit(AgentEvent.QuestionNeeded(request))
        val reply = withTimeoutOrNull(timeoutMs) {
            select<String?> {
                grant.ended.onAwait { null }
                request.response.onAwait { it.trim() }
            }
        }
        if (reply.isNullOrBlank() || !grant.accept(reply)) {
            grant.revoke()
            throw CancellationException("Phone clarification expired, cancelled or no longer belongs to this task.")
        }
        return reply
    }
}
