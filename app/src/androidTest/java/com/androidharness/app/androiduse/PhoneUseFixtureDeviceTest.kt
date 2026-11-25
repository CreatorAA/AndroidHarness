package com.androidharness.app.androiduse

import android.content.ComponentName
import android.app.UiAutomation
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run with -e phoneUseFixture true after installing the independent fixture and enabling the service. */
@RunWith(AndroidJUnit4::class)
class PhoneUseFixtureDeviceTest {
    private val pkg = "com.androidharness.fixture.phoneuse"

    @Before fun keepUserEnabledServicesRunning() {
        if (InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true") {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            if (InstrumentationRegistry.getArguments().getString("phoneUseFixtureBackend", "builtin") == "builtin") runBlocking {
                awaitFixturePhoneService()
            }
        }
    }

    /** The local provider chooses controls from actual observations. The engine, driver and target are real. */
    @Test fun assistantRunReadsTypesSelectsAndSubmitsOnce() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true")
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
        val chat = c.sessions.createSession("Phone Use fixture", conversation = ConversationConfig(ConversationKind.CHAT))
        val backend = InstrumentationRegistry.getArguments().getString("phoneUseFixtureBackend", "builtin")
        val original = c.androidUse.settings.backend
        val workspace = java.io.File(c.appContext.cacheDir, "phone-test-$chat").also { it.mkdirs() }
        var phoneImage: ImageData? = null
        var headerSample: Pair<Double,Double>? = null
        var round = 0
        var engineFailure: String? = null
        val requestedText = "你好 Android 🐦"
        var initialCount = -1
        var receiptSeen = false
        var submitApprovals = 0
        val observations = mutableSetOf<String>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                check(tools.any { it.name == "android_observe" }) { "Phone observation was not advertised." }
                check(tools.none { it.name == "shell" }) { "Ordinary shell reached phone Chat." }
                val step = round
                val latest = messages.lastOrNull { it.role == Role.TOOL }
                fun body(tool: String): JsonObject {
                    val result = requireNotNull(latest)
                    check(result.toolName == tool && !result.isError) { result.text }
                    return Json.parseToJsonElement(result.text.substringBefore("\n[took ")).jsonObject
                }
                fun observe(image: Boolean) = ToolCallData("observe-$step","android_observe","""{"image":$image}""")
                fun action(frame: JsonObject, node: JsonObject, kind: String, more: JsonObjectBuilder.() -> Unit): ToolCallData =
                    ToolCallData("action-$step", "android_action", buildJsonObject {
                        put("observationId", frame.getValue("observationId")); put("kind", kind)
                        put("node", node.getValue("id")); more()
                    }.toString())
                fun observed(): JsonObject = body("android_observe").also { f ->
                    check(f["packageName"]?.jsonPrimitive?.content == pkg)
                    check(observations.add(f.getValue("observationId").jsonPrimitive.content)) { "Repeated frame id" }
                }
                fun nodes(f: JsonObject) = f.getValue("nodes").jsonArray.map { it.jsonObject }
                fun label(n: JsonObject) = n["text"]?.jsonPrimitive?.content.orEmpty()
                val call = when (step) {
                    0 -> observe(true)
                    1 -> {
                        val f = observed(); val ns = nodes(f)
                        phoneImage = requireNotNull(latest).imageData.single()
                        val title = ns.single { label(it) == "第一步 · 填写留言" }
                        val b = title.getValue("screenBounds").jsonArray.map { it.jsonPrimitive.int }
                        headerSample = ((b[0] + (b[2] - b[0]) * 0.90) / f.getValue("width").jsonPrimitive.int) to
                            ((b[1] + (b[3] - b[1]) * 0.50) / f.getValue("height").jsonPrimitive.int)
                        initialCount = Regex("提交次数：(\\d+)").find(ns.joinToString(" ") { label(it) })!!.groupValues[1].toInt()
                        action(f, ns.single { it["editable"]?.jsonPrimitive?.booleanOrNull == true }, "set_text") { put("text", requestedText) }
                    }
                    2, 4, 6 -> {
                        check(body("android_action").getValue("status").jsonPrimitive.content == "verified") { latest?.text.orEmpty() }
                        observe(false)
                    }
                    3 -> {
                        val f = observed(); val ns = nodes(f)
                        check(ns.any { it["editable"]?.jsonPrimitive?.booleanOrNull == true && label(it) == requestedText })
                        val matches = ns.filter { label(it) == "选择" && it["clickable"]?.jsonPrimitive?.booleanOrNull == true }
                        check(matches.size == 2)
                        val lower = matches.maxBy { it.getValue("screenBounds").jsonArray[1].jsonPrimitive.int }
                        action(f,lower,"tap") { put("expectedText","第二步 · 核对") }
                    }
                    5 -> {
                        val f = observed(); val ns = nodes(f)
                        check(ns.any { label(it).contains("选项：白鹭\n留言：$requestedText") })
                        action(f,ns.single { label(it) == "确认提交" },"tap") {
                            put("intent","submit"); put("expectedText","第三步 · 已收到")
                        }
                    }
                    7 -> {
                        val ns = nodes(observed())
                        check(ns.any { label(it).contains("提交次数：${initialCount+1}\n选项：白鹭\n留言：$requestedText") })
                        receiptSeen = true
                        null
                    }
                    else -> error("Unexpected extra model request")
                }
                round++
                if (call != null) {
                    emit(StreamEvent.ToolCallBatch(listOf(call))); emit(StreamEvent.Done("tool_calls"))
                } else {
                    emit(StreamEvent.TextDelta("已选择白鹭，留言已提交一次。")); emit(StreamEvent.Done("stop"))
                }
            }
        }
        try {
            c.androidUse.settings.backend = backend
            c.appContext.startActivity(Intent().setComponent(ComponentName(pkg, "$pkg.FixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val driver: PhoneDriver = if (backend == "autojs") AutoJsPhoneDriver(c.appContext, c.androidUse.settings.port, c.androidUse.settings.token)
                else BuiltinPhoneDriver(c.appContext)
            try { awaitFrame(driver) { it.has("第一步 · 填写留言") } } finally { driver.close() }
            assertTrue(c.androidUse.begin(chat, pkg))
            // No floating panel is displayed by this instrumentation-only engine instance.
            withContext(Dispatchers.Main) { PhonePanel.bind(chat) { true } }
            val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills,
                deviceAccess = c.deviceAccess, phone = c.androidUse)
            withTimeout(90_000) {
                engine.run(chat, "fixture", ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.com", "gpt-4o"),
                    "fixture", listOf(ChatMessage(Role.USER, "在测试应用填入“$requestedText”，选下面的白鹭并提交一次。")), { PermissionMode.FULL_AUTO },
                    mutableSetOf(), FileFs(workspace), RequestOptions(), 100_000, AgentMode.ACT, maxIterations = 10,
                    conversation = ConversationConfig(ConversationKind.CHAT), androidUse = true).collect { event ->
                    if (event is AgentEvent.Error) engineFailure = event.message.take(500)
                    if (event is AgentEvent.ToolFinished && !event.result.ok)
                        throw AssertionError("Fixture ${event.callId} failed: ${event.result.output.take(500)}")
                    if (event is AgentEvent.ApprovalNeeded) {
                        if (event.request.isRiskyPhoneControl) submitApprovals++
                        event.request.response.complete(true)
                    }
                }
            }
            assertTrue("Mock provider stopped on request $round: ${engineFailure ?: "no fixture receipt"}", receiptSeen)
            assertEquals(1, submitApprovals)
            assertEquals(4, observations.size)
            val bytes = Base64.decode(requireNotNull(phoneImage) { "The provider never received screen pixels" }.base64, Base64.DEFAULT)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertTrue(bitmap.width > 100 && bitmap.height > 100)
                // AutoJs6 compact text lists only the centre for some leaf nodes; use the header colour's
                // presence as the common pixel oracle instead of assuming that every backend supplies exact bounds.
                val expected = intArrayOf(0x65, 0xc6, 0xad)
                fun swatch(pixel: Int) = listOf(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel),
                    android.graphics.Color.blue(pixel)).mapIndexed { index, v -> kotlin.math.abs(v - expected[index]) }.all { it < 18 }
                val (x,y) = requireNotNull(headerSample)
                if (!swatch(bitmap.getPixel((x * bitmap.width).toInt().coerceIn(0, bitmap.width-1),
                        (y * bitmap.height).toInt().coerceIn(0, bitmap.height-1)))) {
                    var matches = 0
                    for (row in 0 until bitmap.height step 8) for (column in 0 until bitmap.width step 8)
                        if (swatch(bitmap.getPixel(column,row))) matches++
                    assertTrue("The provider image is not the fixture's teal first page.", matches > 48)
                }
            } finally { bitmap.recycle() }
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("fixtureEvidence", "Four fresh observations, initial page colour, approved once; counter $initialCount -> ${initialCount+1}")
            })
        } finally {
            c.androidUse.end(chat)
            withContext(Dispatchers.Main) { PhonePanel.unbind(chat) }
            c.androidUse.settings.backend = original
            c.sessions.session(chat)?.let { c.sessions.deleteSession(it) }
            workspace.delete()
        }
    }

    @Test fun selectedDriverTypesUnicodeChoosesSecondMatchAndDoesNotReplaySubmission() = runBlocking {
        assumeTrue("Opt-in device fixture required", InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val driver: PhoneDriver = when (val backend = InstrumentationRegistry.getArguments().getString("phoneUseFixtureBackend", "builtin")) {
            "builtin" -> BuiltinPhoneDriver(context)
            "autojs" -> {
                val settings = (context.applicationContext as HarnessApp).container.androidUse.settings
                require(settings.token.isNotBlank()) { "Paste the companion token into Harness settings before this test." }
                AutoJsPhoneDriver(context, settings.port, settings.token)
            }
            else -> error("Unknown fixture backend: $backend")
        }
        context.startActivity(Intent().setComponent(ComponentName(pkg, "$pkg.FixtureActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            val first = stableFrame(driver) { it.has("第一步 · 填写留言") }
            val previous = count(first)
            val field = first.nodes.single { it.editable }
            val message = "你好 Android 🐦\n第二行 'quoted'"
            driver.dispatch(first, PhoneAction("set_text", node = field.id, text = message))
            val filled = stableFrame(driver) { f -> f.nodes.any { it.editable && it.text == message } }
            val options = filled.nodes.filter { it.text == "选择" && it.clickable }.sortedBy { it.top }
            assertEquals("Both identically named controls should remain addressable", 2, options.size)
            driver.dispatch(filled, PhoneAction("tap", node = options.last().id))
            val review = awaitFrame(driver) { it.has("第二步 · 核对") }
            assertTrue(review.has("选项：白鹭\n留言：$message"))
            val image = requireNotNull(awaitFrame(driver, includeImage = true) { it.has("第二步 · 核对") }.image)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size))
            try {
                assertEquals(image.width, decoded.width)
                assertEquals(image.height, decoded.height)
                assertTrue(decoded.width > 100 && decoded.height > 100)
            } finally { decoded.recycle() }

            // The latest observation supplies node ids; image capture may have replaced the older ids.
            val confirmation = stableFrame(driver) { it.has("确认提交") }
            val submit = PhoneAction("tap", node = confirmation.nodes.single { it.text == "确认提交" }.id)
            driver.dispatch(confirmation, submit)
            val receipt = awaitFrame(driver) { it.has("第三步 · 已收到") }
            assertEquals(previous + 1, count(receipt))
            assertTrue("Fixture receipt nodes: " + receipt.nodes.filter { it.text.isNotBlank() }, receipt.has("选项：白鹭\n留言：$message"))
            assertNotNull("A submission from the old page must be rejected",
                runCatching { driver.dispatch(confirmation, submit) }.exceptionOrNull())
            assertEquals(previous + 1, count(awaitFrame(driver) { it.has("第三步 · 已收到") }))
        } finally { driver.close() }
    }

    // Wait for a settled tree before a direct low-level dispatch. Never retry a dispatch.
    private suspend fun stableFrame(driver: PhoneDriver, check: (PhoneFrame) -> Boolean): PhoneFrame {
        var previous: PhoneFrame? = null
        return awaitFrame(driver) { frame ->
            val steady = previous == frame
            previous = frame
            steady && check(frame)
        }
    }

    private suspend fun awaitFrame(driver: PhoneDriver, includeImage: Boolean = false, check: (PhoneFrame) -> Boolean): PhoneFrame {
        val until = android.os.SystemClock.elapsedRealtime() + 12_000
        var last = "no observation"
        var firstFailure: String? = null
        do {
            val f = try { driver.observe(includeImage) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                last = e.message.orEmpty()
                if (firstFailure == null) firstFailure = last
                null
            }
            if (f != null) {
                if (f.packageName == pkg && check(f)) return f
                last = if (f.packageName == pkg) "fixture labels: " + f.nodes.map { it.text }.filter(String::isNotBlank).take(8)
                    else "foreground: " + f.packageName
            }
            delay(150)
        } while (android.os.SystemClock.elapsedRealtime() < until)
        error("Timed out observing the fixture with ${driver.label}: $last" +
            (firstFailure?.takeIf { it != last }?.let { " (first failure: $it)" } ?: ""))
    }

    private fun PhoneFrame.has(fragment: String) = nodes.any { fragment in it.text }
    private fun count(frame: PhoneFrame): Int = Regex("提交次数：(\\d+)").find(
        frame.nodes.joinToString("\n") { it.text }
    )?.groupValues?.get(1)?.toInt() ?: error("Fixture did not expose its submission counter")
}
