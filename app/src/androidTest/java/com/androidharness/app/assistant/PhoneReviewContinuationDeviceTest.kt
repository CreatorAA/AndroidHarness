package com.androidharness.app.assistant

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.androiduse.awaitFixturePhoneService
import com.androidharness.app.androiduse.fixtureTaskScope
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** No account, real conversations or external model. A normal user follow-up, not the mock's
 * proposal, must start a managed observation on this specific fixture page. */
class PhoneReviewContinuationDeviceTest {
    @Test fun explicitFollowUpContinuesPresentedHumanTaskWithoutPressingStart() = runTrial(followUp = true)
    @Test fun currentReactionOpensItsOwnQuoteDespiteSimilarOlderScreenshot() = runTrial(followUp = false)

    private fun runTrial(followUp: Boolean): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ctx = ins.targetContext
        val c = (ctx.applicationContext as HarnessApp).container
        check(c.androidUse.activeSession.value == null && c.codePhoneHandoff.current() == null)
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val panel = AssistantPhoneOverlayDeviceTest()
        val pkg = "com.androidharness.fixture.phoneuse"
        val first = if (followUp) "这个人在惊讶什么" else "帮我自动化分析一下这个人在惊讶什么"
        val next = "你倒是自动化分析啊"
        val old = c.settings.settings.first()
        val managed = c.androidUse.settings.autoManageUserTasks
        val project = c.workspace.currentProjectOnce().id
        val history = c.phoneHistory.load(project)
        val verified = AtomicBoolean()
        val corrected = AtomicBoolean()
        var paddingPoint: Pair<Int,Int>?=null
        var intendedNode: JsonElement?=null
        val oldGroup = "合成旧群：远足路线"
        fun send(text: String) {
            val editor = panel.waitNode("normal assistant composer") { it.packageName == ctx.packageName && it.isEditable }
            assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
            val sendLabel = ContextCompat.getContextForLanguage(ctx).getString(R.string.assistant_send)
            var control: AccessibilityNodeInfo? = panel.waitNode("composer send button") {
                it.packageName == ctx.packageName && it.isVisibleToUser && it.contentDescription?.toString() == sendLabel
            }
            while (control != null && !control.isClickable) control = control.parent
            assertTrue("Composer's Send action", control?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        awaitFixturePhoneService()
        panel.awaitAssistantService()
        panel.OverlayServer { body ->
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            val user = messages.last { it["role"]?.jsonPrimitive?.content == "user" &&
                (first in it.getValue("content").toString() || next in it.getValue("content").toString()) }.getValue("content").toString()
            val receipt = messages.lastOrNull { it["role"]?.jsonPrimitive?.content == "tool" }
            check(messages.any { oldGroup in it.getValue("content").toString() }) { "Lost earlier conversation" }
            if (followUp && next !in user) {
                check(first in user && c.androidUse.activeSession.value?.let(c.androidUse::isManaged) == true)
                return@OverlayServer event(buildJsonObject { put("content","可以核对当前页面的引用。") }, "stop")
            }
            val sid = checkNotNull(c.androidUse.activeSession.value) { "Follow-up was sent as ordinary chat, without a lease" }
            check(messages.any { first in it["content"].toString() } && c.androidUse.isManaged(sid))
            check("Page at this request" in user && pkg in user && oldGroup !in user &&
                "image_url" !in user && "read_screen" !in user) { "History was passed as this turn's source" }
            val id = receipt?.get("tool_call_id")?.jsonPrimitive?.content.orEmpty()
            if (!id.startsWith("look") && !id.startsWith("detail") && id !in setOf("missing_target","open_quote"))
                return@OverlayServer function("look1","android_observe",buildJsonObject {
                    put("taskScope",fixtureTaskScope("inspect", capture=!followUp)); put("image",!followUp)
                })
            val content = receipt!!.getValue("content")
            val output = if (content is JsonArray) content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.jsonObject.getValue("text").jsonPrimitive.content
                else content.jsonPrimitive.content
            val observation = Json.parseToJsonElement(output.substringBefore("\n[took ").trim()).jsonObject
            fun matchingChoice(scope: JsonObject) = scope.getValue("candidates").jsonArray.map { it.jsonObject }.single { item ->
                item.getValue("evidence").jsonArray.any { it.jsonObject["text"]?.jsonPrimitive?.content == "群聊的聊天记录" }
            }
            if (id == "open_quote") {
                if (!followUp) check(observation["resolvedFrom"]?.jsonPrimitive?.content == "delivered_point_in_read_target" &&
                    observation["tapNode"] == intendedNode) { "Padding was not dispatched to the observed card: $observation" }
                return@OverlayServer function("detail1","android_observe",buildJsonObject { put("image",false) })
            }
            if (id == "missing_target") {
                check(observation.getValue("status").jsonPrimitive.content == "not_dispatched" &&
                    observation.getValue("reason").jsonPrimitive.content == "unknown_observed_target")
                if (followUp) check(observation.getValue("completedSteps").jsonPrimitive.int == 0 &&
                    observation.getValue("failedStep").jsonPrimitive.int == 2)
                val choice = matchingChoice(observation.getValue("readScope").jsonObject)
                corrected.set(true)
                // Fresh explicit selection using only the correction. Harness did not substitute a tap.
                return@OverlayServer function("open_quote", if (followUp) "android_sequence" else "android_action",buildJsonObject {
                    put("observationId", observation.getValue("observationId"))
                    if (followUp) putJsonArray("steps") { add(buildJsonObject { put("kind","tap"); put("intent","navigate"); put("node",choice.getValue("tapNode")) }) }
                    else {
                        check(choice.getValue("tapNode")==intendedNode)
                        val point=checkNotNull(paddingPoint)
                        put("kind","tap"); put("x",point.first); put("y",point.second); put("intent","navigate")
                    }
                })
            }
            if (observation["route"]?.jsonPrimitive?.content != "screen") {
                val stage = id.dropLast(1)
                check(id.last() < '3')
                return@OverlayServer function(stage + (id.last().digitToInt() + 1),"android_observe",buildJsonObject {
                    put("image",!followUp && id.startsWith("look")); put("declineOffer",observation.getValue("observationId"))
                    put("reason","The offered commands do not identify the quoted reaction in this synthetic conversation.")
                })
            }
            check(observation.getValue("packageName").jsonPrimitive.content == pkg)
            val nodes = observation.getValue("nodes")
            if (id.startsWith("look")) {
                check("掌中设备修复小组" in nodes.toString() && "真有啊" in nodes.toString())
                val choice = matchingChoice(observation.getValue("readScope").jsonObject)
                val references = observation.getValue("actionTargets").jsonArray.map { it.jsonObject }
                val reference = references.single { it.getValue("tapNode") == choice.getValue("tapNode") }
                check(reference.getValue("inReadScope").jsonPrimitive.boolean)
                val byId = nodes.jsonArray.map { it.jsonObject }.associateBy { it.getValue("id") }
                val title = reference.getValue("evidenceNodes").jsonArray.map(byId::getValue)
                    .single { it["text"]?.jsonPrimitive?.content == "群聊的聊天记录" }
                val card = byId.getValue(reference.getValue("tapNode"))
                check(title.getValue("text").jsonPrimitive.content == "群聊的聊天记录" &&
                    title["clickable"]?.jsonPrimitive?.booleanOrNull != true)
                check(card !== title && card.getValue("clickable").jsonPrimitive.boolean &&
                    card["description"]?.jsonPrimitive?.content.isNullOrEmpty() && card["text"]?.jsonPrimitive?.content.isNullOrEmpty())
                val wrong = byId.values.single { it["description"]?.jsonPrimitive?.content == "谷雨的头像" &&
                    it["clickable"]?.jsonPrimitive?.booleanOrNull == true && it !== card }
                check(!references.single { it.getValue("tapNode") == wrong.getValue("id") }.getValue("inReadScope").jsonPrimitive.boolean)
                // A suggested list is not an allowlist. This target demonstrably does not exist
                // in the current frame, so even a two-step sequence must dispatch nothing.
                val missing="absent:" + wrong.getValue("id").jsonPrimitive.content
                if (!followUp) {
                    check(messages.any { m -> m["role"]?.jsonPrimitive?.content == "user" &&
                        (m["content"] as? JsonArray)?.let { a -> a.any { part -> part.jsonObject["type"]?.jsonPrimitive?.content=="image_url" } &&
                            a.any { part -> "tool_call_id=$id," in part.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() } } == true })
                    val rect=card.getValue("screenBounds").jsonArray.map { it.jsonPrimitive.int }
                    val titleTop=title.getValue("screenBounds").jsonArray[1].jsonPrimitive.int
                    check(titleTop-rect[1]>=4)
                    fun preview(screen:Int, axis:String) = (screen.toLong()*observation.getValue("preview"+axis.replaceFirstChar(Char::uppercaseChar)).jsonPrimitive.int /
                        observation.getValue(axis).jsonPrimitive.int).toInt()
                    paddingPoint=preview((rect[0]+rect[2])/2,"width") to preview((rect[1]+titleTop)/2,"height")
                    intendedNode=card.getValue("id")
                }
                return@OverlayServer function("missing_target",if (followUp) "android_sequence" else "android_action",buildJsonObject {
                    put("observationId",observation.getValue("observationId"))
                    if (followUp) putJsonArray("steps") {
                        add(buildJsonObject { put("kind","tap"); put("intent","navigate"); put("node",reference.getValue("tapNode")) })
                        add(buildJsonObject { put("kind","tap"); put("intent","navigate"); put("node",missing) })
                    } else { put("kind","tap"); put("node",missing); put("intent","navigate") }
                })
            }
            val modelName = checkNotNull(Regex("""HX-\d{4}""").find(nodes.toString())?.value)
            check("原厂离线点阵字库" in nodes.toString())
            verified.set(true)
            event(buildJsonObject { put("content","他惊讶的是，原以为失传的 ${modelName} 阅读器原厂离线点阵字库竟然还在备份里。") }, "stop")
        }.use { server ->
            val model = "review-source-test"
            val provider = c.providers.add("Temporary review source",ProviderType.OPENAI_COMPAT,"http://127.0.0.1:${server.port}/v1", model, "fixture")
            val seeded = c.sessions.createSession("Synthetic quote source", project, ConversationConfig(ConversationKind.PHONE))
            var imageName: String? = null
            try {
                c.providers.saveCatalog(provider.id,listOf(ModelEntry(model, imageInput = true)))
                c.settings.setActiveSelection(provider.id,model); c.settings.setAssistantModel(provider.id,model)
                c.settings.setAssistantVoiceFirst(false); c.settings.setRunInBackground(true)
                c.androidUse.settings.autoManageUserTasks = true
                // Same reaction, different conversation and explanation. Keep a prior phone tool
                // screenshot so this also exercises the exact request sent to the vision provider.
                val bitmap = Bitmap.createBitmap(420, 136, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 23f }
                canvas.drawText(oldGroup, 12f, 36f, paint); canvas.drawText("山顶还有冰川。 真有啊！",12f,96f,paint)
                val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG,100,out); out.toByteArray() }
                bitmap.recycle()
                val frame = c.images.savePhoneFrame(bytes,"image/png",420,136,420,136,pkg)
                imageName = frame.name
                c.sessions.addMessage(seeded, ChatMessage(Role.USER, "看看${oldGroup}里的人为何惊讶"), "old")
                c.sessions.addMessage(seeded, ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("old_page","android_observe","{}"))), "old")
                c.sessions.addMessage(seeded, ChatMessage(Role.TOOL, """{"route":"screen","packageName":"$pkg","header":"$oldGroup","text":"真有啊！"}""",
                    toolCallId = "old_page", toolName = "android_observe", images = listOf(frame)), "old")
                c.sessions.addMessage(seeded, ChatMessage(Role.ASSISTANT, "他惊讶山顶还有冰川。"), "old")
                c.phoneHistory.save(project,seeded)
                withTimeout(8_000) { c.chatModels.observe(null).first { it.selection.active?.providerId == provider.id } }
                ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("am start -W -f 0x10008000 -n $pkg/.ReferencedChatActivity")).use { it.readBytes() }
                panel.waitNode("synthetic conversation") { it.packageName == pkg && it.text?.toString() == "真有啊！" }
                panel.summon()
                send(first)
                if (followUp) {
                    panel.waitNode("ordinary reply in the same panel") { it.text?.contains("可以核对当前页面的引用。") == true }
                    withTimeout(8_000) { while(c.runManager.isRunning(seeded) || c.androidUse.isActive(seeded)) delay(60) }
                    assertNull(c.androidUse.activeSession.value)
                    send(next)
                }
                // Never tap any approval or start action.
                withTimeout(30_000) {
                    while (!verified.get()) { server.failure?.let { throw AssertionError("Mock provider",it) }; delay(60) }
                }
                val sid = checkNotNull(c.phoneHistory.load(project))
                assertEquals(seeded,sid)
                withTimeout(8000) { while(c.androidUse.isActive(sid) || c.runManager.isRunning(sid)) delay(60) }
                assertTrue("Model had to choose again from the correction", corrected.get())
                val counts = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys activity $pkg/.ReferencedChatActivity"))
                    .bufferedReader().useLines { lines -> lines.map { it.trim() }.filter { it.startsWith("fixtureCounts:") }.toList() }
                assertEquals(listOf("fixtureCounts: opens=1 neighbors=0 sends=0"), counts)
            } finally {
                if (c.runtimePolicy.assistantPanelVisible.value) panel.dismissPanel()
                c.androidUse.end(seeded); c.runManager.stopAndJoin(seeded); c.sessions.session(seeded)?.let { c.sessions.deleteSession(it) }
                imageName?.let(c.images::delete)
                if(history == null) c.phoneHistory.clear(project) else c.phoneHistory.save(project, history)
                c.androidUse.settings.autoManageUserTasks = managed
                c.settings.setAssistantModel(old.assistantProviderId,old.assistantModel); c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground); c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId); c.settings.setActiveModel(old.activeModel); c.providers.delete(provider.id)
            }
        }
    }
}
