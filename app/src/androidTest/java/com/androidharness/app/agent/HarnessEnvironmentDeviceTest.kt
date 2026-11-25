package com.androidharness.app.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.androiduse.fixtureTaskScope
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.env.*
import com.androidharness.app.llm.*
import com.androidharness.app.skills.SkillStore
import com.androidharness.app.tools.*
import com.androidharness.app.workspace.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HarnessEnvironmentDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
    private val config = ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture")
    private data class Request(val prompt: String, val names: Set<String>)
    private fun tool(toolName: String, readOnly: Boolean = true, available: () -> Boolean = { true },
        action: suspend () -> Unit = {}) = object : Tool {
        override val name = toolName
        override val description = "Environment fixture"
        override val parametersSchema = Schema.obj(emptyMap())
        override val isReadOnly = readOnly
        override fun isAvailable(ctx: ToolContext) = available()
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            action(); return ToolResult(true, "Fixture completed")
        }
    }
    private fun engine(provider: LlmProvider, registry: ToolRegistry, skills: SkillStore = c.skills,
        settings: AppSettings = AppSettings()) = AgentEngine({ provider }, registry, c.checkpoints, c.images,
        c.linuxEnv, c.shizuku, skills, cavemanSettings = { settings }, deviceAccess = c.deviceAccess)

    private suspend fun run(engine: AgentEngine, fs: WorkspaceFs, policy: ConversationConfig = ConversationConfig(),
        mode: AgentMode = AgentMode.ACT, providerConfig: ProviderConfig = config): List<AgentEvent> {
        val sid = c.sessions.createSession("Environment fixture", conversation = policy)
        return try {
            engine.run(sid, "environment-turn", providerConfig, "", listOf(ChatMessage(Role.USER, "Check this environment")),
                { PermissionMode.FULL_AUTO }, mutableSetOf(), fs, RequestOptions(), 100_000, mode,
                maxIterations = 5, repoMapEnabled = false, conversation = policy, searchDepth = SearchDepth.OFF).toList()
                .also { events -> assertFalse(events.toString(), events.any { it is AgentEvent.Error || it is AgentEvent.ApprovalNeeded || it is AgentEvent.EnvironmentNeeded }) }
        } finally { c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
    }
    private fun provider(requests: MutableList<Request>, reply: (Int) -> List<StreamEvent> = {
        listOf(StreamEvent.TextDelta("Fixture answer"), StreamEvent.Done("stop"))
    }) = object : LlmProvider {
        override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
            messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
            requests += Request(systemPrompt, tools.map { it.name }.toSet())
            reply(requests.size).forEach { emit(it) }
        }
    }

    @Test fun codeLoadsTheBundledGuideAndRefreshesToolAvailabilityBetweenRequests() = runBlocking {
        val root = File(c.appContext.cacheDir, "environment-${System.nanoTime()}").apply { mkdirs() }
        val requests = mutableListOf<Request>()
        var connected = true
        val savedMode = c.deviceAccess.store.preferences.first().mode
        val registry = ToolRegistry(listOf(tool("change_environment", action = {
            connected = false
            c.deviceAccess.store.setMode(AccessMode.NORMAL)
        }),
            tool("mcp__fixture__old", available = { connected }), tool("mcp__fixture__new", available = { !connected })))
        val fixture = provider(requests) { round -> if (round == 1) listOf(
            StreamEvent.ToolCallReady(ToolCallData("change", "change_environment", "{}")), StreamEvent.Done("tool_calls"))
            else listOf(StreamEvent.TextDelta("Updated environment"), StreamEvent.Done("stop")) }
        try {
            c.deviceAccess.store.setMode(AccessMode.AUTO)
            run(engine(fixture, registry), FileFs(root))
            assertEquals(2, requests.size)
            assertTrue(requests[0].prompt.contains("Access mode: Auto"))
            assertTrue(requests[1].prompt.contains("Access mode: Normal"))
            for (request in requests) {
                assertTrue(request.prompt.contains("## Live AndroidHarness environment"))
                assertTrue(request.prompt.contains("## AndroidHarness operating guide"))
                assertTrue(request.prompt.contains("Working inside AndroidHarness"))
                assertTrue(request.prompt.contains(root.path))
                assertTrue(request.prompt.contains("app-owned workspace uses the app UID"))
                assertTrue(request.prompt.contains("snapshot, not an execution receipt"))
                val toolIndex = request.prompt.substringAfter("## Tools available for this request")
                assertEquals(request.names, Regex("\"([^\"]+)\"").findAll(toolIndex).map { it.groupValues[1] }.toSet())
            }
            assertTrue(requests[0].names.contains("mcp__fixture__old"))
            assertFalse(requests[0].prompt.substringAfter("## Tools available for this request").contains("mcp__fixture__new"))
            assertFalse(requests[1].names.contains("mcp__fixture__old"))
            assertTrue(requests[1].names.contains("mcp__fixture__new"))
        } finally { c.deviceAccess.store.setMode(savedMode); root.deleteRecursively() }
    }

    @Test fun chatOnlyReceivesItsConversationToolsAndNeverCodeEnvironmentPaths() = runBlocking {
        val root = File(c.appContext.cacheDir, "chat-environment-${System.nanoTime()}").apply { mkdirs() }
        val requests = mutableListOf<Request>()
        try {
            run(engine(provider(requests), ToolRegistry(listOf(tool("shell", false), tool("web_search"),
                tool("web_fetch"), tool("skill_view")))), FileFs(root), ConversationConfig(ConversationKind.CHAT))
            val request = requests.single()
            assertEquals(setOf("web_fetch"), request.names)
            assertTrue(request.prompt.contains("Section: Chat"))
            assertFalse(request.prompt.contains("## AndroidHarness operating guide"))
            assertFalse(request.prompt.contains(root.path))
            assertFalse(request.prompt.contains(c.linuxEnv.prefix.path))
            assertFalse(request.prompt.substringAfter("## Tools available for this request").contains("web_search"))
        } finally { root.deleteRecursively() }
    }

    @Test fun onlyExplicitAskUserWaitsAndResumesTheSamePhoneRun() = runBlocking {
        val question = "计算器已打开。是指 9 × 19 吗？请确认一下算式。"
        for (nativeQuestion in listOf(false, true)) {
            val input = "打开计算器并在里面计算九成已久"
            val policy = ConversationConfig(ConversationKind.PHONE)
            val sid = c.sessions.createSession("Phone question fixture", conversation = policy)
            val dir = File(c.appContext.cacheDir, "phone-clarify-${System.nanoTime()}").apply { mkdirs() }
            var round = 0
            val model = object : LlmProvider {
                override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                    messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                    assertTrue("Phone tools must survive the clarification", tools.any { it.name == "android_action" })
                    assertTrue(tools.any { it.name == "ask_user" })
                    when (++round) {
                        1 -> if (nativeQuestion) {
                            emit(StreamEvent.ToolCallReady(ToolCallData("clar", "ask_user", buildJsonObject { put("question",question) }.toString())))
                            emit(StreamEvent.Done("tool_calls"))
                        } else { emit(StreamEvent.TextDelta(question)); emit(StreamEvent.Done("stop")) }
                        2 -> {
                            check(nativeQuestion) { "The wording of a completed reply started another request" }
                            assertTrue(messages.any { it.role == Role.TOOL && it.toolCallId=="clar" && "对" in it.text })
                            val scope = buildJsonObject { put("taskScope",fixtureTaskScope("operate","edit", apps=listOf("计算器"))) }
                            emit(StreamEvent.ToolCallReady(ToolCallData("caps", "android_capabilities", scope.toString())))
                            emit(StreamEvent.Done("tool_calls"))
                        }
                        else -> { emit(StreamEvent.TextDelta("任务参数已确认。")); emit(StreamEvent.Done("stop")) }
                    }
                }
            }
            try {
                assertTrue(c.androidUse.begin(sid, "com.androidharness.fixture.phoneuse", managed = true, userInstruction = input))
                val agent = AgentEngine({ model }, ToolRegistry(emptyList()), c.checkpoints, c.images, c.linuxEnv, c.shizuku,
                    c.skills, deviceAccess = c.deviceAccess, phone = c.androidUse)
                val events = agent.run(sid, "task-fixture", config, "", listOf(ChatMessage(Role.USER, input)), { PermissionMode.CONFIRM_RISKY },
                    mutableSetOf(), FileFs(dir), RequestOptions(), 100_000, AgentMode.ACT, maxIterations = 5, repoMapEnabled = false,
                    conversation = policy, searchDepth = SearchDepth.OFF, androidUse = true).onEach { e ->
                    when (e) {
                        is AgentEvent.QuestionNeeded -> {
                            assertTrue("Natural-language reply opened a question", nativeQuestion)
                            assertTrue(e.request.phoneClarification)
                            e.request.response.complete("对")
                        }
                        is AgentEvent.ApprovalNeeded -> error("Unexpected second permission request: ${e.request.toolDescription}")
                        is AgentEvent.Error -> error(e.toString())
                        else -> Unit
                    }
                }.toList()
                assertEquals(if(nativeQuestion) 3 else 1, round)
                assertEquals(if(nativeQuestion) 1 else 0, events.count { it is AgentEvent.QuestionNeeded })
                assertEquals(nativeQuestion,events.filterIsInstance<AgentEvent.ToolFinished>().any { it.callId == "caps" && it.result.ok })
                assertTrue(c.androidUse.isManaged(sid))
            } finally { c.androidUse.end(sid); c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }; dir.deleteRecursively() }
        }
        Unit
    }

    @Test fun phoneModelOnlyReceivesAutoJsGuidanceWhenOptedIn() = runBlocking {
        val root = File(c.appContext.cacheDir, "phone-environment-${System.nanoTime()}").apply { mkdirs() }
        val requests = mutableListOf<Request>()
        val extension = c.androidUse.settings
        val wasEnabled = extension.autoJsEnabled
        val agent = AgentEngine({ provider(requests) }, ToolRegistry(emptyList()), c.checkpoints, c.images,
            c.linuxEnv, c.shizuku, c.skills, deviceAccess = c.deviceAccess, phone = c.androidUse)
        try {
            for (enabled in listOf(false, true, false)) {
                extension.autoJsEnabled = enabled
                run(agent, FileFs(root), ConversationConfig(ConversationKind.PHONE))
                val request = requests.last()
                assertTrue(request.prompt.contains("Section: Phone assistant"))
                assertFalse(request.prompt.contains("Section: Chat"))
                assertEquals(enabled, request.names.contains("autojs_scripts"))
                assertEquals(enabled, request.prompt.contains("AutoJs6 is an optional local extension"))
                assertFalse(request.names.contains("autojs_run")) // No live phone task.
            }
        } finally { extension.autoJsEnabled = wasEnabled; root.deleteRecursively() }
    }

    @Test fun planModeAndDisabledSkillKeepTheirRestrictions() = runBlocking {
        val root = File(c.appContext.cacheDir, "plan-environment-${System.nanoTime()}").apply { mkdirs() }
        val requests = mutableListOf<Request>()
        val guide = c.skills.view("androidharness-environment").getOrThrow().content
        val disabled = SkillStore(mapOf("androidharness-environment" to SkillStore.BundledSkill("androidharness-environment", guide)),
            File(root, "skills"), { null }, { setOf("androidharness-environment") })
        try {
            run(engine(provider(requests), ToolRegistry(listOf(tool("read_file"), tool("write_file", false))), disabled), FileFs(root), mode = AgentMode.PLAN)
            val request = requests.single()
            assertEquals(setOf("read_file"), request.names)
            assertTrue(request.prompt.contains("agent mode: PLAN"))
            assertFalse(request.prompt.contains("## AndroidHarness operating guide"))
            assertTrue(request.prompt.contains("Only read-only tools are exposed"))
        } finally { root.deleteRecursively() }
    }

    @Test fun documentTreeAndSelectedReadOnlyWorkspaceExposeTheGuideButNoCommands() = runBlocking {
        val root = File(c.appContext.cacheDir, "docs-environment-${System.nanoTime()}").apply { mkdirs() }
        val disk = FileFs(root)
        val documents = object : WorkspaceFs by disk {
            override val shellRoot: File? = null
            override val isSaf = true
            override val displayPath = "content://fixture/document/tree"
        }
        val requests = mutableListOf<Request>()
        try {
            val selected = ChatWorkspaceFs("docs", listOf(ChatWorkspaceBinding("docs", "Document fixture", false, documents)))
            val registry = ToolRegistry(listOf(tool("shell", false), tool("git_status"), ReadFileTool(),
                SkillViewTool(c.skills), SkillsListTool(c.skills)))
            run(engine(provider(requests), registry), selected)
            val request = requests.single()
            assertEquals(setOf("read_file", "skill_view", "skills_list"), request.names)
            assertTrue(request.prompt.contains("Document-tree workspace"))
            assertTrue(request.prompt.contains("access: read only"))
            val result = SkillViewTool(c.skills).execute(buildJsonObject {
                put("name", "androidharness-environment"); put("file_path", "references/device-access.md")
            }, ToolContext(selected))
            assertTrue(result.output, result.ok)
            assertTrue(result.output.contains("Shizuku"))
        } finally { root.deleteRecursively() }
    }

    @Test fun localMinimalContextDoesNotAcquireEnvironmentOrTools() = runBlocking {
        val root = File(c.appContext.cacheDir, "minimal-environment-${System.nanoTime()}").apply { mkdirs() }
        val requests = mutableListOf<Request>()
        try {
            val local = config.copy(id = "local-model:qwen-05b")
            assertTrue(com.androidharness.app.local.LocalModelCatalog.isLocal(local.id))
            run(engine(provider(requests), ToolRegistry(listOf(tool("read_file"))),
                settings = AppSettings(localModelAgentContext = false)), FileFs(root), providerConfig = local)
            assertTrue(requests.single().prompt.isEmpty())
            assertTrue(requests.single().names.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun nestedResearchGetsTheSameGuideWithItsOwnRestrictedToolList() = runBlocking {
        val root = File(c.appContext.cacheDir, "nested-environment-${System.nanoTime()}").apply { mkdirs() }
        val requests = mutableListOf<Request>()
        val fixture = provider(requests) { round -> when (round) {
            1 -> listOf(StreamEvent.ToolCallReady(ToolCallData("task-fixture", "task", "{\"prompt\":\"Inspect environment\"}")), StreamEvent.Done("tool_calls"))
            else -> listOf(StreamEvent.TextDelta("Environment checked"), StreamEvent.Done("stop"))
        } }
        try {
            run(engine(fixture, ToolRegistry(listOf(tool("task"), tool("ask_user"), tool("read_file"), tool("write_file", false)))), FileFs(root))
            assertEquals(3, requests.size)
            val nested = requests[1]
            assertTrue(nested.prompt.contains("read-only research subagent"))
            assertTrue(nested.prompt.contains("## AndroidHarness operating guide"))
            assertTrue(nested.prompt.contains("agent mode: PLAN"))
            assertEquals(setOf("read_file"), nested.names)
        } finally { root.deleteRecursively() }
    }

    @Test fun readingSnapshotDoesNotSpendANextActionRootAllowance() = runBlocking {
        val access = DeviceAccessManager(c.appContext, c.shizuku, c.linuxEnv)
        val saved = access.store.preferences.first()
        val project = "environment-root-fixture"
        try {
            access.store.setTemporaryRoot(true)
            access.store.allowProject(project, true)
            access.enableTemporaryUse(RootUse.NEXT_ACTION, "fixture-chat")
            repeat(3) { assertTrue(access.environmentForAgent(project, "fixture-chat").rootAllowed) }
            assertTrue(access.rootUseActive("fixture-chat", access.store.preferences.first()))
            assertFalse(access.environmentForAgent(project, "another-chat").rootReady)
            assertNull(access.root.service)
        } finally {
            access.disableRootUse()
            access.store.setTemporaryRoot(saved.temporaryRoot)
            if (project !in saved.rootProjects) access.store.allowProject(project, false)
        }
    }
}
