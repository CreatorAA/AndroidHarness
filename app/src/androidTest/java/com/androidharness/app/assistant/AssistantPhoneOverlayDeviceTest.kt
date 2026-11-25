package com.androidharness.app.assistant

import android.app.UiAutomation
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.agent.isPhoneControl
import com.androidharness.app.agent.isRiskyPhoneControl
import com.androidharness.app.androiduse.awaitFixturePhoneService
import com.androidharness.app.androiduse.fixtureTaskScope
import com.androidharness.app.core.Role
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okio.buffer
import okio.source
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt in on a disposable emulator with Harness elected as assistant and the phone-use fixture installed. */
@RunWith(AndroidJUnit4::class)
class AssistantPhoneOverlayDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val c get() = (ctx.applicationContext as HarnessApp).container
    // The default UiAutomation would disconnect the very AccessibilityService this scenario exercises.
    private val ui by lazy { ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val pkg = "com.androidharness.fixture.phoneuse"
    private val model = "local-overlay-fixture"
    private fun label(id: Int) = ContextCompat.getContextForLanguage(ctx).getString(id)

    @Test fun completedReplyKeepsContinuationAndNewQuestionOnly() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay") == "true")
        check(shell("getprop ro.kernel.qemu") == "1" || shell("getprop ro.boot.qemu") == "1")
        awaitAssistantService()
        val project = c.workspace.currentProjectOnce().id
        val before = c.phoneHistory.load(project)
        val settings = c.settings.settings.first()
        val sid = c.sessions.createSession("Fixture conversation", project, ConversationConfig(ConversationKind.PHONE))
        val receipt = "The calculator shows 171."
        try {
            c.settings.setAssistantVoiceFirst(false)
            c.sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "What is 9 × 19?"), "footer-turn")
            c.sessions.addMessage(sid, ChatMessage(role = Role.ASSISTANT, text = receipt), "footer-turn")
            c.phoneHistory.save(project, sid)
            fun openHistory() {
                shell("am start -W -n $pkg/.FixtureActivity")
                waitNode("fixture foreground") { it.packageName?.toString() == pkg }
                summon()
                text(receipt)
                text(label(R.string.assistant_continue_in_app))
                text(label(R.string.assistant_new_chat))
            }
            openHistory()
            assertNull(find { matches(it, label(R.string.assistant_phone_action)) })
            screenshot("reply-actions")
            tap(label(R.string.assistant_new_chat))
            text(label(R.string.assistant_phone_action)) // Only an empty panel offers this shortcut.
            withTimeout(5000) { while (c.phoneHistory.load(project) != null) kotlinx.coroutines.delay(50) }
            assertNull(find { matches(it, receipt) })
            assertTrue(c.sessions.messages(sid).any { it.text == receipt })
            dismissPanel()
            c.phoneHistory.save(project, sid)
            openHistory()
            tap(label(R.string.assistant_continue_in_app))
            waitFor("full app opened on the same conversation") {
                !c.runtimePolicy.assistantPanelVisible.value && find { matches(it, receipt) } != null
            }
            assertEquals(sid, c.settings.settings.first().lastActiveSessionId)
        } finally {
            if (c.runtimePolicy.assistantPanelVisible.value) dismissPanel()
            if (before == null) c.phoneHistory.clear(project) else c.phoneHistory.save(project, before)
            c.settings.setAssistantVoiceFirst(settings.assistantVoiceFirst)
            c.settings.setLastActiveSessionId(settings.lastActiveSessionId)
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun realAssistantReturnsToItsTurnAndSystemResummonTakesControl() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay") == "true")
        check(shell("getprop ro.kernel.qemu") == "1" || shell("getprop ro.boot.qemu") == "1") { "Requires a disposable emulator." }
        check(ComponentName.unflattenFromString(shell("settings get secure voice_interaction_service")) ==
            ComponentName(ctx, HarnessAssistantService::class.java)) { "Choose Harness as the default digital assistant first." }
        check(c.androidUse.settings.isAccessibilityEnabled()) { "Enable the built-in Harness phone accessibility service first." }
        check(shell("pm path $pkg").startsWith("package:")) { "Install tests/fixtures/phone-use first." }
        check(c.androidUse.activeSession.value == null) { "Another phone task is active." }
        check(c.codePhoneHandoff.current() == null) { "Another Code phone draft is pending." }
        awaitFixturePhoneService()
        awaitAssistantService()

        val old = c.settings.settings.first()
        val oldBackend = c.androidUse.settings.backend
        val sessions = mutableListOf<String>()
        var codeSource: String? = null
        OverlayServer().use { server ->
            val provider = c.providers.add("Disposable overlay test", ProviderType.OPENAI_COMPAT,
                "http://127.0.0.1:${server.port}/v1", model, "local-fixture-not-a-key")
            try {
                c.providers.saveCatalog(provider.id, listOf(ModelEntry(model, imageInput = true)))
                c.settings.setActiveSelection(provider.id, model)
                c.settings.setAssistantModel(provider.id, model)
                c.settings.setAssistantVoiceFirst(false)
                c.settings.setRunInBackground(true)
                c.androidUse.settings.backend = "builtin"
                withTimeout(10_000L) { c.chatModels.observe(null).first { it.selection.active?.providerId == provider.id } }

                fun start(prompt: String, fromCode: Boolean = false): String {
                    shell("am start -W -f 0x10008000 -n $pkg/.FixtureActivity")
                    waitFor("fixture visible") { ui.rootInActiveWindow?.packageName?.toString() == pkg }
                    summon()
                    openFixtureTaskReview()
                    text(pkg) // Consent names the app; a model-supplied package is not sufficient.
                    val field = waitNode("task instruction") { it.isEditable && it.packageName?.toString() == ctx.packageName }
                    if (fromCode) {
                        text(label(R.string.android_use_from_code))
                        assertEquals(prompt, field.text?.toString())
                    } else {
                        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                        }))
                    }
                    if (prompt == NORMAL) screenshot("consent")
                    tap(label(R.string.assistant_phone_allow_once))
                    waitFor("task lease") { c.androidUse.activeSession.value != null }
                    val sid = checkNotNull(c.androidUse.activeSession.value)
                    sessions += sid
                    waitFor("real app observation") {
                        server.failure?.let { throw AssertionError("Fixture model failed", it) }
                        if (server.observedPrompt == prompt) true else {
                            approveFixtureStep(sid)
                            false
                        }
                    }
                    return sid
                }
                val normal = start(NORMAL)
                waitFor("matching turn returned to the panel") {
                    server.failure?.let { throw AssertionError("Fixture model failed", it) }
                    approveFixtureStep(normal)
                    find { matches(it, DONE) } != null
                }
                assertEquals(normal, server.observedSession)
                assertTrue(server.verified)
                assertTrue("The actual assistant window was not restored", c.runtimePolicy.assistantPanelVisible.value)
                assertTrue(c.sessions.messages(normal).any { it.role == Role.ASSISTANT && DONE in it.text })
                screenshot("returned")
                dismissPanel()
                waitFor("target field retained action") { find { it.isEditable && it.text?.toString() == server.written } != null }

                val project = c.workspace.currentProjectOnce()
                val source = c.sessions.createSession("Overlay Code source", project.id)
                codeSource = source
                c.codePhoneHandoff.offer(source, project.id, TAKEOVER)
                val interrupted = start(TAKEOVER, fromCode = true)
                assertNotEquals(normal, interrupted)
                assertEquals(interrupted, c.sessions.latestPhoneTask(source))
                assertEquals(interrupted, server.observedSession)
                assertFalse(c.runtimePolicy.assistantPanelVisible.value)
                // The provider is waiting after a real observation. A system summon inspects the
                // same turn, gates further input, and leaves the actual stop decision to the person.
                summon()
                waitNode("paused task inspector") { matches(it, label(R.string.phone_island_paused)) }
                assertTrue(c.androidUse.isActive(interrupted))
                assertTrue(c.runManager.isRunning(interrupted))
                tap(label(R.string.phone_island_stop))
                waitFor("explicit stop revoked lease") { !c.androidUse.isActive(interrupted) }
                server.resume.countDown()
                waitFor("old run stopped") { !c.runManager.isRunning(interrupted) }
                dismissPanel()
                val after = waitNode("fixture after takeover") { it.packageName?.toString() == pkg && it.isEditable }
                assertNotEquals(LATE, after.text?.toString())
                assertTrue(c.sessions.messages(interrupted).none { it.role == Role.TOOL && it.toolName == "android_action" })
            } finally {
                server.resume.countDown()
                sessions.forEach { sid ->
                    if (c.androidUse.isActive(sid)) c.androidUse.end(sid)
                    c.runManager.stopAndJoin(sid)
                    c.deviceAccess.store.allowPhoneControl(sid, false)
                    c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                }
                codeSource?.let { source ->
                    c.codePhoneHandoff.cancel(source)
                    c.sessions.session(source)?.let { c.sessions.deleteSession(it) }
                }
                if (c.runtimePolicy.assistantPanelVisible.value) dismissPanel()
                c.androidUse.settings.backend = oldBackend
                c.settings.setAssistantModel(old.assistantProviderId, old.assistantModel)
                c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground)
                c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId)
                c.settings.setActiveModel(old.activeModel)
                c.providers.delete(provider.id)
            }
        }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }

    /** Instrumentation restarts its target. If Android kept the dead bind, re-elect only the exact
     * already-selected component on the disposable AVD and wait for the system-side Binder. */
    internal fun awaitAssistantService() {
        val component = ComponentName(ctx, HarnessAssistantService::class.java)
        val current = "mComponent=" + component.flattenToShortString()
        fun bound(state: String) = current in state && state.lineSequence().any {
            it.trim().startsWith("mBound=true mService=") && !it.trim().endsWith("mService=null")
        }
        if (bound(shell("dumpsys voiceinteraction"))) return
        val role = android.app.role.RoleManager.ROLE_ASSISTANT
        val roles = ctx.getSystemService(android.app.role.RoleManager::class.java)
        val user = shell("am get-current-user").toInt()
        check(roles.isRoleHeld(role) && ComponentName.unflattenFromString(shell("settings get secure voice_interaction_service")) == component)
        try {
            // 1 = MANAGE_HOLDERS_FLAG_DONT_KILL_APP. Keep the running instrumentation process alive.
            val removed = shell("cmd role remove-role-holder --user $user $role ${ctx.packageName} 1")
            check(!roles.isRoleHeld(role)) { "Could not release stale assistant role: $removed" }
        } finally { shell("cmd role add-role-holder --user $user $role ${ctx.packageName} 1") }
        waitFor("selected assistant service Binder after instrumentation restart") { bound(shell("dumpsys voiceinteraction")) }
    }
    // Android 17 restricts 'cmd voiceinteraction show' to a platform permission. A genuine
    // system assistant key goes through the same system UI entry point a person can invoke.
    internal fun summon() { shell("input keyevent KEYCODE_ASSIST") }
    internal fun dismissPanel() { shell("input keyevent KEYCODE_BACK") }
    private fun matches(n: AccessibilityNodeInfo, value: String) = n.isVisibleToUser &&
        (n.text?.contains(value) == true || n.contentDescription?.contains(value) == true)
    // Exercise both the per-chat phone gate and the ordinary tool approval in the emulator's chosen mode.
    // Only the two known fixture requests can be approved; no global permission setting is changed.
    internal fun approveFixtureStep(sid: String) {
        val pending = c.runManager.live(sid).value.pendingApproval ?: return
        if (pending.response.isCompleted) return
        check(pending.call.name in setOf("android_observe", "android_action")) { "Unexpected approval: ${pending.call.name}" }
        val button = label(when {
            pending.isRiskyPhoneControl -> R.string.phone_control_allow_once
            pending.isPhoneControl -> R.string.phone_control_allow
            else -> R.string.ui_allow
        })
        find { it.text?.toString() == button || it.contentDescription?.toString() == button }?.let(::click)
    }
    private fun find(test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            node ?: return null
            if (node.isVisibleToUser && test(node)) return node
            for (i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) ui.clearCache()
        return walk(ui.rootInActiveWindow)
    }
    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 24_000L
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(75)
        }
        screenshot("failure")
        error("Timed out: $what; active window=${ui.rootInActiveWindow?.packageName}, phone=${c.androidUse.activeSession.value}")
    }
    internal fun waitNode(what: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitFor(what) { node = find(predicate); node != null }
        return checkNotNull(node)
    }
    private fun text(value: String) = waitNode(value) { matches(it, value) }
    internal fun tap(value: String) {
        var target: AccessibilityNodeInfo?=null
        waitFor("enabled action: $value") {
            target=find { n->
                if(!matches(n,value))false else {
                    var action:AccessibilityNodeInfo?=n
                    while(action!=null && !action.isClickable) action=action.parent
                    action?.isEnabled==true
                }
            }
            target!=null
        }
        click(requireNotNull(target))
    }
    /** A completed conversation no longer shows Operate phone. These disposable scenarios begin a new task. */
    internal fun openFixtureTaskReview() {
        val fresh = label(R.string.assistant_phone_action)
        val restart = label(R.string.assistant_new_chat)
        // A restored message can contain "操作手机" verbatim. Match only the actual controls.
        fun control(n: AccessibilityNodeInfo, title: String) =
            n.text?.toString() == title || n.contentDescription?.toString() == title
        val entry = waitNode("new or resumed assistant panel") { control(it, fresh) || control(it, restart) }
        if (control(entry, restart)) {
            click(entry)
            click(waitNode("empty panel's phone shortcut") { control(it, fresh) })
        } else click(entry)
    }
    private fun click(node: AccessibilityNodeInfo) {
        var p: AccessibilityNodeInfo? = node
        while (p != null && !p.isClickable) p = p.parent
        check(p != null && p.isEnabled && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Control not clickable: ${node.text}" }
    }
    private fun screenshot(name: String) {
        val bitmap = ui.takeScreenshot() ?: return
        try {
            val dir = File(ctx.getExternalFilesDir(null), "overlay-qa").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    /** Only the provider wire is simulated. It selects a real editable node and requires its verified receipt. */
    internal inner class OverlayServer(private val customReply: (OverlayServer.(JsonObject) -> String)? = null) : AutoCloseable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        val resume = CountDownLatch(1)
        @Volatile var observedPrompt: String? = null
        @Volatile var observedSession: String? = null
        @Volatile var verified = false
        val written = "浮层回执 🐦 " + SystemClock.elapsedRealtime()
        private var initialFrame: JsonObject? = null
        @Volatile var failure: Throwable? = null
        private val thread = Thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    client.soTimeout = 30_000
                    val input = client.getInputStream().source().buffer()
                    val path = input.readUtf8Line().orEmpty()
                    var length = 0L
                    while (true) {
                        val header = input.readUtf8Line() ?: return@use
                        if (header.isBlank()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toLong()
                    }
                    val chat = path.contains("/chat/completions")
                    val data = if (chat) {
                        try {
                            val body = Json.parseToJsonElement(input.readUtf8(length)).jsonObject
                            customReply?.invoke(this, body) ?: reply(body)
                        }
                        catch (e: Exception) { failure = e; event(buildJsonObject { put("content", "Fixture failed") }, "stop") }
                    } else """{"data":[{"id":"$model"}]}"""
                    val bytes = data.toByteArray(Charsets.UTF_8)
                    val mime = if (chat) "text/event-stream" else "application/json"
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (_: java.io.IOException) { /* The cancelled run closes its connection. */ }
        }.apply { isDaemon = true; start() }
        private fun reply(body: JsonObject): String {
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            val prompt = messages.lastOrNull { it["role"]?.jsonPrimitive?.content == "user" }?.get("content").toString()
            val task = if (TAKEOVER in prompt) TAKEOVER else NORMAL
            val tool = messages.lastOrNull { it["role"]?.jsonPrimitive?.content == "tool" }
            if (tool == null) {
                check(body.getValue("tools").jsonArray.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "android_observe" })
                return function("observe_fixture", "android_observe", buildJsonObject {
                    put("taskScope",fixtureTaskScope("operate","edit",capture=task==NORMAL));put("image", task == NORMAL)
                })
            }
            val payload = tool.getValue("content")
            val content = (if (payload is JsonArray) payload.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "text" }.getValue("text").jsonPrimitive.content
                else payload.jsonPrimitive.content).substringBefore("\n[took ").trim()
            check(content.startsWith("{")) { "Tool ${tool["tool_call_id"]} failed: $content" }
            val result = Json.parseToJsonElement(content).jsonObject
            val call = tool["tool_call_id"]?.jsonPrimitive?.content
            if (call == "observe_fixture" || call == "readback_fixture") {
                check(result.getValue("packageName").jsonPrimitive.content == pkg)
                check(!c.runtimePolicy.assistantPanelVisible.value) { "Observe returned while the panel still owns the display" }
                observedSession = checkNotNull(c.androidUse.activeSession.value)
                observedPrompt = task
                val field = result.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["editable"]?.jsonPrimitive?.booleanOrNull == true }
                if (call == "readback_fixture") {
                    val before = checkNotNull(initialFrame)
                    val previous = before.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["editable"]?.jsonPrimitive?.booleanOrNull == true }
                    val evidence = "Action receipt: dispatched_unverified; one read-only follow-up.\n" +
                        "Before window=${before["window"]}, id=${previous["id"]}, bounds=${previous["screenBounds"]}, text=${previous["text"]}\n" +
                        "After window=${result["window"]}, id=${field["id"]}, bounds=${field["screenBounds"]}, text=${field["text"]}\n"
                    val dir = File(ctx.getExternalFilesDir(null), "overlay-qa").apply { mkdirs() }
                    File(dir, "readback.txt").writeText(evidence)
                    check(before["observationId"] != result["observationId"] && field["text"]?.jsonPrimitive?.content == written) {
                        "Fresh observation did not confirm the unique fixture editor. $evidence"
                    }
                    verified = true
                    return event(buildJsonObject { put("content", DONE) }, "stop")
                }
                if (task == NORMAL) {
                    checkModelPixels(messages, tool, result)
                    initialFrame = result
                    check(field["text"]?.jsonPrimitive?.content != written)
                }
                if (task == TAKEOVER) check(resume.await(45, TimeUnit.SECONDS)) { "No UI takeover" }
                return function("write_fixture", "android_action", buildJsonObject {
                    put("observationId", result.getValue("observationId")); put("kind","set_text"); put("node",field.getValue("id"))
                    put("text", if (task == NORMAL) written else LATE)
                })
            }
            check(task == NORMAL && call == "write_fixture")
            val status = result["status"]?.jsonPrimitive?.content
            if (status == "dispatched_unverified")
                return function("readback_fixture", "android_observe", buildJsonObject { put("image", false) })
            check(status == "verified") { "Fixture write failed: $content" }
            verified = true
            return event(buildJsonObject { put("content", DONE) }, "stop")
        }
        /** Inspect pixels that crossed the actual provider wire, not a second screenshot taken by the test. */
        private fun checkModelPixels(messages: List<JsonObject>, tool: JsonObject, frame: JsonObject) {
            val index = messages.indexOfLast { it === tool }
            val next = messages.getOrNull(index + 1)?.takeIf {
                it["role"]?.jsonPrimitive?.content == "user" && "tool_call_id=observe_fixture" in it["content"].toString()
            }
            val urls = listOfNotNull(tool, next).flatMap { (it["content"] as? JsonArray).orEmpty() }
                .map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "image_url" }
                .map { it.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content }
            val url = urls.singleOrNull()?.takeIf { it.startsWith("data:image/") && ";base64," in it }
                ?: error("Expected one inline image linked to this observation, got ${urls.size}.")
            val bytes = Base64.decode(url.substringAfter(','), Base64.DEFAULT)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Undecodable observation image" }
            try {
                val title = frame.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["text"]?.jsonPrimitive?.content == "第一步 · 填写留言" }
                val bounds = title.getValue("screenBounds").jsonArray.map { it.jsonPrimitive.int }
                val px = ((bounds[0] + (bounds[2]-bounds[0]) * 0.90) * bitmap.width / frame.getValue("width").jsonPrimitive.int).toInt()
                val py = ((bounds[1] + (bounds[3]-bounds[1]) * 0.50) * bitmap.height / frame.getValue("height").jsonPrimitive.int).toInt()
                val pixel = bitmap.getPixel(px, py)
                val dir = File(ctx.getExternalFilesDir(null), "overlay-qa").apply { mkdirs() }
                File(dir, "model-observation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                check(kotlin.math.abs(Color.red(pixel)-0x65)<18 && kotlin.math.abs(Color.green(pixel)-0xc6)<18 &&
                    kotlin.math.abs(Color.blue(pixel)-0xad)<18) {
                    "Model saw ${Integer.toHexString(pixel)} at fixture header ($px,$py), expected teal #65c6ad."
                }
            } finally { bitmap.recycle() }
        }
        internal fun function(id: String, name: String, args: JsonObject) = event(buildJsonObject {
            putJsonArray("tool_calls") { add(buildJsonObject {
                put("index", 0); put("id", id); put("type", "function")
                putJsonObject("function") { put("name", name); put("arguments", args.toString()) }
            }) }
        }, "tool_calls")
        internal fun event(delta: JsonObject, finish: String): String {
            fun frame(d: JsonObject, reason: JsonElement) = buildJsonObject {
                putJsonArray("choices") { add(buildJsonObject { put("index",0); put("delta",d); put("finish_reason",reason) }) }
            }
            return "data: ${frame(delta, JsonNull)}\n\ndata: ${frame(buildJsonObject {}, JsonPrimitive(finish))}\n\ndata: [DONE]\n\n"
        }
        override fun close() { resume.countDown(); socket.close(); thread.join(1500) }
    }
    private companion object {
        const val NORMAL = "Overlay fixture normal"
        const val TAKEOVER = "Overlay fixture takeover"
        const val DONE = "Overlay fixture completed."
        const val LATE = "late forbidden write"
    }
}
