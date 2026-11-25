package com.androidharness.app.androiduse

import android.app.UiAutomation
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only operate the disposable, independent two-page form, never a user's app. */
@RunWith(AndroidJUnit4::class)
class PhoneSequenceDeviceTest {
    @Test fun managedSequenceVerifiesTwoActionsStopsOnPageChangeAndHonorsRevocation() = runForm(managed=true)
    @Test fun requestedSequenceInApprovalModeWaitsForItsBatchGrant() = runForm(managed=false)
    private fun runForm(managed:Boolean): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ins = InstrumentationRegistry.getInstrumentation()
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val app = ins.targetContext.applicationContext as HarnessApp
        val phone = app.container.androidUse
        assumeTrue("A user task is active", phone.activeSession.value == null)
        awaitFixturePhoneService()
        val pkg = "com.androidharness.fixture.phoneuse"
        val sid = UUID.randomUUID().toString()
        val phrase = "编排测试" + "i".repeat(228) + sid.take(4)
        val ctx = ToolContext(FileFs(app.cacheDir), sessionId = sid, imageInputAllowed = false)
        val tools = phone.tools().associateBy { it.name }
        val observe = tools.getValue("android_observe")
        val sequence = tools.getValue("android_sequence")
        fun labels(root: AccessibilityNodeInfo? = ui.rootInActiveWindow): List<String> {
            if (root == null || root.packageName?.toString() != pkg) return emptyList()
            return buildList {
                fun visit(n: AccessibilityNodeInfo) {
                    n.text?.toString()?.let { add(it) }
                    for (i in 0 until n.childCount) n.getChild(i)?.let(::visit)
                }
                visit(root)
            }
        }
        suspend fun waitLabel(label: String) = withTimeout(5000) { while (labels().none { label in it }) delay(50) }
        var formWindow = -1
        suspend fun freshForm(): String {
            val replaced = ui.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.windowId
            ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("am start -W -f 0x10008000 -n $pkg/.FixtureActivity"))
                .use { it.readBytes() }
            return withTimeout(5000){
                while(true){
                    if(Build.VERSION.SDK_INT>=33)ui.clearCache()
                    val root = ui.rootInActiveWindow
                    if (root != null && root.packageName?.toString() == pkg && root.windowId != replaced) {
                        val page = labels(root)
                        if ("第一步 · 填写留言" in page)
                            page.singleOrNull { it.startsWith("提交次数：") }?.let {
                                formWindow = root.windowId
                                return@withTimeout it
                            }
                    }
                    delay(50)
                }
                @Suppress("UNREACHABLE_CODE") error("Not on the fixture form")
            }
        }
        fun body(r: ToolResult) = Json.parseToJsonElement(r.output).jsonObject
        suspend fun screen(first:Boolean = false): JsonObject {
            var offer: JsonElement? = null
            repeat(3) {
                val r = observe.execute(buildJsonObject {
                    put("image", false); put("driver", "builtin")
                    if(first && offer==null)put("taskScope",fixtureTaskScope("operate","edit"))
                    offer?.let { put("declineOffer", it); put("reason", "The task edits two specific native fixture controls.") }
                }, ctx)
                assertTrue(r.output, r.ok)
                val f = body(r)
                if (f.getValue("route").jsonPrimitive.content == "screen") {
                    assertEquals(pkg, f.getValue("packageName").jsonPrimitive.content)
                    return f
                }
                offer = f.getValue("observationId")
            }
            error("No screen route")
        }
        fun nodes(f: JsonObject) = f.getValue("nodes").jsonArray.map { it.jsonObject }
        // A CLEAR_TASK relaunch can briefly publish the old window, new splash and new form
        // together. Wait for the actual driver's target, not just the fixture's first text event.
        suspend fun formScreen(first: Boolean = false): JsonObject = withTimeout(8_000) {
            var previous: JsonObject? = null
            while (true) {
                val frame = screen(first && previous == null)
                val rows = nodes(frame)
                if (frame.getValue("window").jsonPrimitive.content.substringBefore(':') == formWindow.toString() &&
                    rows.any { it["editable"]?.jsonPrimitive?.booleanOrNull == true } &&
                    rows.count { it["text"]?.jsonPrimitive?.content == "选择" } == 2 &&
                    frame["window"] == previous?.get("window") && frame["nodes"] == previous?.get("nodes"))
                    return@withTimeout frame
                previous = frame
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE") error("No stable fixture form")
        }
        fun field(f: JsonObject) = nodes(f).single { it["editable"]?.jsonPrimitive?.booleanOrNull == true }
        fun second(f: JsonObject) = nodes(f).filter { it["text"]?.jsonPrimitive?.content == "选择" }
            .also { assertEquals(2, it.size) }.maxBy { it.getValue("screenBounds").jsonArray[1].jsonPrimitive.int }
        fun step(kind: String, node: JsonObject, value: String? = null) = buildJsonObject {
            put("kind", kind); put("node", node.getValue("id")); value?.let { put("text", it) }
            if(kind=="tap")put("intent","navigate")
        }
        fun args(f: JsonObject, vararg actions: JsonObject, expected: String? = null) = buildJsonObject {
            put("observationId", f.getValue("observationId")); put("steps", JsonArray(actions.toList()))
            expected?.let { put("expectedText", it) }
        }
        val instruction = "在验证表单填写“$phrase”，点下面的「选择」以核对白鹭，不提交。"
        val old = phone.settings.backend
        val oldEnabled = phone.settings.autoJsEnabled
        try {
            phone.settings.backend = "builtin"
            assertTrue(phone.begin(sid, pkg))
            assertFalse("The sequence must not be offered in ordinary phone tasks", sequence.isAvailable(ctx))
            phone.end(sid)
            assertTrue(phone.begin(sid, pkg, managed = managed, userInstruction = instruction))
            if(!managed)app.container.deviceAccess.store.allowPhoneControl(sid,true)
            withContext(Dispatchers.Main) { PhonePanel.bind(sid) { true } }
            assertTrue(sequence.isAvailable(ctx))
            val counter = freshForm()
            var first = formScreen(first = true)
            val value = "First command:\n\nCommand:\n" + "长值".repeat(180) + " · end"
            val type = step("set_text", field(first), value)
            fun preview(kind: String, vararg values: Pair<String, String>): String =
                requireNotNull(phone.submissionPreview(sid, buildJsonObject {
                    put("observationId", first.getValue("observationId")); put("kind", kind)
                    values.forEach { (key, v) -> put(key, v) }
                }))
            val single = preview("set_text", "node" to field(first).getValue("id").jsonPrimitive.content, "text" to value)
            val batch = phone.sequenceApproval(sid, args(first, type)).preview
            assertTrue(single.contains(value) && batch.contains(single.substringAfter('\n')))
            assertTrue(preview("share_text", "text" to value).contains(value))
            assertTrue(preview("open_link", "url" to "https://example.org/$value").endsWith("$value”"))
            assertTrue(preview("media_volume", "percent" to "37").contains("37%"))
            fun fill(f:JsonObject)=args(f,step("set_text",field(f),phrase),step("tap",second(f)),expected="第二步 · 核对")
            if(!managed){
                assertFalse("A fresh observation is not approval to run a batch",sequence.execute(fill(first),ctx).ok)
                waitLabel("第一步 · 填写留言")
                assertTrue(labels().none { phrase in it })
                first=formScreen()
            }
            val ok=fill(first)
            if(!managed)assertTrue(phone.sequenceApproval(sid,ok).confirm())
            val done = sequence.execute(ok, ctx)
            assertTrue(done.output, done.ok)
            assertEquals("verified", body(done).getValue("status").jsonPrimitive.content)
            assertEquals(2, body(done).getValue("completedSteps").jsonPrimitive.int)
            waitLabel("选项：白鹭\n留言：$phrase")
            assertFalse("Old observation must not replay", sequence.execute(ok, ctx).ok)

            val review = screen()
            val submit = nodes(review).single { it["text"]?.jsonPrimitive?.content == "确认提交" }
            val commit = buildJsonObject {
                put("kind","tap"); put("node",submit.getValue("id")); put("intent","submit"); put("effect","save")
            }
            assertFalse("A submit step cannot expand this edit-only sequence", sequence.execute(args(review, commit), ctx).ok)
            waitLabel("第二步 · 核对")
            freshForm()
            waitLabel(counter)
            if(!managed){
                // A successful prior approval is not an evergreen grant, even for the same form.
                val again=formScreen()
                assertFalse(sequence.execute(fill(again),ctx).ok)
                assertTrue(labels().none { phrase in it })
                ins.sendStatus(0, Bundle().apply { putString("confirmedBatchEvidence",
                    "${phrase.length}-character edit and second-row tap verified; batch grant not reusable on the new form.") })
                return@runBlocking
            }
            val changed = formScreen()
            val interrupted = sequence.execute(args(changed, step("tap", second(changed)), step("set_text", field(changed), phrase)), ctx)
            assertFalse(interrupted.output, interrupted.ok)
            assertEquals("stopped", body(interrupted).getValue("status").jsonPrimitive.content)
            assertEquals(1, body(interrupted).getValue("completedSteps").jsonPrimitive.int)
            waitLabel("选项：白鹭\n留言：")
            assertTrue("Never type into a replacement control", labels().none { phrase in it })

            freshForm()
            waitLabel(counter)
            val cancelled = formScreen()
            val late = args(cancelled, step("set_text", field(cancelled), phrase))
            // Stop an already-running sequence before the assistant window gives up input, not
            // only a fresh call after a session has ended. Release the callback to expose late sends.
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            withContext(Dispatchers.Main) { PhonePanel.bind(sid) { entered.complete(Unit); release.await(); true } }
            val pending = async { sequence.execute(late, ctx) }
            try {
                withTimeout(4000) { entered.await() }
                phone.end(sid)
            } finally { release.complete(Unit) }
            val stopped = try { !pending.await().ok } catch (_: CancellationException) { true }
            assertTrue("A cancelled in-flight sequence cannot report completion", stopped)
            assertFalse(sequence.isAvailable(ctx))
            assertFalse(sequence.execute(late, ctx).ok)
            assertTrue("No write after user takeover", labels().none { phrase in it })
            ins.sendStatus(0, Bundle().apply { putString("sequenceEvidence",
                "Two UI steps, exact ${phrase.length}-character note + 白鹭 verified; stale token, submit and revoked calls refused; page-change stopped after 1/2, counter unchanged.") })
        } finally {
            phone.end(sid)
            if(!managed)app.container.deviceAccess.store.allowPhoneControl(sid,false)
            withContext(Dispatchers.Main) { PhonePanel.unbind(sid) }
            phone.settings.backend = old
            phone.settings.autoJsEnabled = oldEnabled
        }
    }
}
