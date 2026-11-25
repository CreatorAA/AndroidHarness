package com.androidharness.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.LlmProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.llm.ToolSchema
import com.androidharness.app.tools.ToolRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class GoalContinuationDeviceTest {
    @Test fun completedCodeTurnIsJudgedIndependentlyAndContinuesUntilMet(): Unit = runBlocking {
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
        val root = File(c.appContext.cacheDir, "goal-test-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val goal = GoalState("List two primary colors", turnLimit = 3)
        val sid = c.sessions.createSession("Goal continuation fixture", project.id)
        val requests = CopyOnWriteArrayList<List<ChatMessage>>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                requests += messages.toList()
                val reply = when (requests.size) {
                    1 -> "Red."
                    2 -> """{"verdict":"continue","reason":"One color missing","next":"Name the second color"}"""
                    3 -> "Red and blue."
                    4 -> """{"verdict":"met","reason":"Both colors are listed","changed":"Added blue"}"""
                    else -> error("Unexpected extra goal request")
                }
                emit(StreamEvent.TextDelta(reply))
                // Only the two independent checks report usage. Their tokens and estimated cost
                // must survive the automatic next turn and count against the same goal budget.
                if (requests.size == 2 || requests.size == 4) emit(StreamEvent.Usage(100, 5))
                emit(StreamEvent.Done("stop"))
            }
        }
        val engine = AgentEngine({ provider }, ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val manager = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace, c.linuxEnv,
            c.settings, TodoStore(), c.providers)
        try {
            manager.controls.update(sid) { it.copy(goal = goal) }
            manager.startRun(sid, GoalJudge.kickoff(goal.text), emptyList(),
                ProviderConfig("goal-fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture"),
                "", PermissionMode.FULL_AUTO, AgentMode.ACT, 4_000, 100_000, ThinkingLevel.OFF, 5, notifyOnFinish = false)
            val end = withTimeout(30_000) {
                manager.controls.flow(sid).first { it.goal?.status == GoalState.Status.MET }
            }
            withTimeout(5_000) { manager.goalCheckSessionIds.first { sid !in it } }
            val reached = requireNotNull(end.goal)
            assertEquals(listOf(GoalCheck.Outcome.CONTINUE, GoalCheck.Outcome.MET), reached.history.map { it.outcome })
            assertEquals(2, reached.turnsUsed)
            assertEquals(210L, end.usedTokens)
            assertTrue(end.usedCost > 0)
            assertEquals(4, requests.size)
            assertTrue(requests[1].any { it.role == Role.ASSISTANT && it.text == "Red." })
            assertTrue(requests[2].last().text.contains("Name the second color"))
            assertTrue(requests[3].any { it.role == Role.ASSISTANT && it.text == "Red and blue." })
            // Judge replies are goal history, not extra assistant answers injected into the transcript.
            assertEquals(listOf("Red.", "Red and blue."), c.sessions.messages(sid).filter { it.role == Role.ASSISTANT }.map { it.text })
        } finally {
            manager.stopAllAndJoin()
            manager.controls.forget(sid)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project)
            root.deleteRecursively()
        }
    }
}
