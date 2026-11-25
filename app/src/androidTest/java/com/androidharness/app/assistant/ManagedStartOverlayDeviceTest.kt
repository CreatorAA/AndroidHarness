package com.androidharness.app.assistant

import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.androiduse.awaitFixturePhoneService
import com.androidharness.app.androiduse.fixtureTaskScope
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.settings.AssistantPhoneSettings
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual assistant panel + settings, native controls and a loopback provider only. */
class ManagedStartOverlayDeviceTest {
    @Test fun humanPhoneRequestUsesTheSavedChoiceButCodeDraftDoesNot(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ins = InstrumentationRegistry.getInstrumentation()
        val ctx = ins.targetContext
        val c = (ctx.applicationContext as HarnessApp).container
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val panel = AssistantPhoneOverlayDeviceTest()
        val pkg = "com.androidharness.fixture.phoneuse"
        val settings = c.androidUse.settings
        val oldSetting = settings.autoManageUserTasks
        val prefs = ctx.getSharedPreferences("phone_control_grants", 0)
        val hadKey = prefs.contains("auto_manage_user_tasks")
        val old = c.settings.settings.first()
        val project = c.workspace.currentProjectOnce().id
        val history = c.phoneHistory.load(project)
        val marker = "青桥" + SystemClock.uptimeMillis().toString().takeLast(4)
        val exact = "开计算器计算20 × 18"
        val exactInstalledApp = "打开手机操作验证"
        val prompt = "操作手机：在留言中填入${marker}，不要提交"
        val indirect = "I'd like you to inspect this entry for me."
        val mode = AtomicInteger()
        val managedSid = AtomicReference<String?>()
        val completions = AtomicInteger()
        val received = AtomicInteger()
        val proposals = AtomicInteger()
        val sessions = mutableSetOf<String>()
        var source: String? = null
        fun label(id: Int) = ContextCompat.getContextForLanguage(ctx).getString(id)
        fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
        fun launch() {
            shell("am start -W -f 0x10008000 -n $pkg/.FixtureActivity")
            panel.waitNode("fixture ready") { it.packageName?.toString() == pkg && it.isEditable }
            panel.summon()
            panel.waitNode("ordinary composer") { it.packageName?.toString() == ctx.packageName && it.isEditable }
        }
        fun input(text: String) {
            val field = panel.waitNode("ordinary text input") { it.packageName?.toString() == ctx.packageName && it.isEditable }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }))
            panel.tap(label(R.string.assistant_send))
        }
        fun screenshot(name:String) {
            ui.waitForIdle(250, 2_000)
            val image = checkNotNull(ui.takeScreenshot())
            try { File(ctx.getExternalFilesDir(null),name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) } }
            finally { image.recycle() }
        }
        require(c.androidUse.activeSession.value == null && c.codePhoneHandoff.current() == null)
        awaitFixturePhoneService()
        panel.awaitAssistantService()
        panel.OverlayServer { body ->
            val current = mode.get()
            if(current==0){
                check(c.androidUse.activeSession.value==null) { "Task started before review" }
                val ms=body.getValue("messages").jsonArray.map { it.jsonObject }
                check(ms.any { it["role"]?.jsonPrimitive?.content=="user" && exactInstalledApp in it["content"].toString() })
                if(ms.none { it["tool_call_id"]?.jsonPrimitive?.content=="review_only" }){
                    check(proposals.incrementAndGet()==1)
                    return@OverlayServer function("review_only","suggest_app_action",buildJsonObject { put("action","phone_task") })
                }
                return@OverlayServer event(buildJsonObject { put("content","请确认这项手机任务。") },"stop")
            }
            if(current==3) {
                val offered=body.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                check(offered.any { it.getValue("name").jsonPrimitive.content=="android_observe" })
                val panelTool=offered.singleOrNull { it.getValue("name").jsonPrimitive.content=="suggest_app_action" }
                if(panelTool!=null){
                    val actions=panelTool.getValue("parameters").jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("enum").jsonArray
                    check(actions.map { it.jsonPrimitive.content }==listOf("new_conversation"))
                }
                return@OverlayServer event(buildJsonObject { put("content","Fixture answered without using the phone.") },"stop")
            }
            require(current in setOf(1,2,4)) { "Task ran without a start grant" }
            received.incrementAndGet()
            val sid = checkNotNull(c.androidUse.activeSession.value) { "No task lease: assistant sent the command to ordinary chat" }
            check(c.androidUse.isManaged(sid)) { "This direct request was not managed" }
            managedSid.set(sid)
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            check(messages.any { it["role"]?.jsonPrimitive?.content == "user" &&
                (when(current){1->exact;2->prompt;else->indirect}) in it["content"].toString() })
            val id = "a${current}_"
            val step = messages.lastOrNull { it["role"]?.jsonPrimitive?.content == "tool" &&
                it["tool_call_id"]?.jsonPrimitive?.content?.startsWith(id) == true }
            if (step == null) return@OverlayServer function("${id}observe","android_observe", buildJsonObject {
                val scope=when(current){
                    1->fixtureTaskScope("operate","edit",apps=listOf("计算器"))
                    2->fixtureTaskScope("operate","edit")
                    else->fixtureTaskScope("inspect")
                }
                put("taskScope",scope);put("image",false)
            })
            val payload=step.getValue("content")
            val raw = if (payload is JsonArray) payload.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content == "text" }.getValue("text").jsonPrimitive.content else payload.jsonPrimitive.content
            val result=Json.parseToJsonElement(raw.substringBefore("\n[took ").trim()).jsonObject
            when(val call=step.getValue("tool_call_id").jsonPrimitive.content) {
                "${id}observe", "${id}screen", "${id}again" -> {
                    if(result["route"]?.jsonPrimitive?.content != "screen") {
                        check(call != "${id}again")
                        return@OverlayServer function(if(call=="${id}observe") "${id}screen" else "${id}again","android_observe",buildJsonObject {
                            put("image",false); put("declineOffer",result.getValue("observationId"));put("reason","Inspect this named test form; none of the offered commands edits it.")
                        })
                    }
                    check(result.getValue("packageName").jsonPrimitive.content == pkg)
                    if(current==2) {
                        val field=result.getValue("nodes").jsonArray.map { it.jsonObject }.single { it["editable"]?.jsonPrimitive?.booleanOrNull == true }
                        return@OverlayServer function("${id}write","android_action",buildJsonObject {
                            put("observationId",result.getValue("observationId"));put("kind","set_text");put("node",field.getValue("id"));put("text",marker)
                        })
                    }
                }
                "${id}write" -> check(result["status"]?.jsonPrimitive?.content in setOf("verified","dispatched_unverified")) { raw }
                else -> error("Unexpected call: $call")
            }
            completions.incrementAndGet()
            event(buildJsonObject { put("content", if(current==1) "Fixture observation complete; no calculator installed." else "Fixture write dispatched.") },"stop")
        }.use { server ->
            val model = "managed-overlay-local"
            val provider=c.providers.add("Temporary managed overlay",ProviderType.OPENAI_COMPAT,"http://127.0.0.1:${server.port}/v1",model,"local-fixture")
            try {
                c.providers.saveCatalog(provider.id,listOf(ModelEntry(model)))
                c.settings.setActiveSelection(provider.id,model);c.settings.setAssistantModel(provider.id,model)
                c.settings.setAssistantVoiceFirst(false);c.settings.setRunInBackground(true)
                withTimeout(10_000) { c.chatModels.observe(null).first { it.selection.active?.providerId==provider.id } }
                c.phoneHistory.clear(project)
                try { prefs.edit().remove("auto_manage_user_tasks").commit();assertFalse("Older installs must still require explicit review",settings.autoManageUserTasks) }
                finally { if(hadKey) settings.autoManageUserTasks=oldSetting }
                settings.autoManageUserTasks=false
                // The *entire* name is an installed launcher entry. Even this request reaches the model first.
                launch();input(exactInstalledApp)
                panel.waitNode("review named target") { it.text?.toString()==label(R.string.phone_managed_start) }
                assertNull(c.androidUse.activeSession.value);assertEquals(1,proposals.get());assertEquals(0,received.get());screenshot("managed-start-review.png")
                panel.tap(ctx.getString(android.R.string.cancel));panel.dismissPanel()
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scene ->
                    scene.onActivity { a -> a.setContent { HarnessTheme { Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) { AssistantPhoneSettings(c) } } } }
                    panel.tap(label(R.string.phone_auto_manage))
                    withTimeout(4_000){while(!settings.autoManageUserTasks) delay(50)}
                    screenshot("managed-start-settings.png")
                }
                for((idx,task) in listOf(exact,prompt).withIndex()) {
                    val n=idx+1
                    mode.set(n); managedSid.set(null)
                    launch();input(task)
                    withTimeout(24_000) {
                        while(completions.get()<n) {
                            server.failure?.let { throw AssertionError("Mock provider: $it",it) }
                            c.androidUse.activeSession.value?.let { sid ->
                                sessions+=sid
                                val approval=c.runManager.live(sid).value.pendingApproval
                                assertNull("Unexpected per-step approval: ${approval?.call?.name}",approval)
                            }
                            delay(60)
                        }
                    }
                    val sid=checkNotNull(managedSid.get());sessions+=sid
                    withTimeout(12_000) { while(c.androidUse.isActive(sid)||c.runManager.isRunning(sid)) delay(70) }
                    if(n==2) panel.waitNode("actual reversible field edit, without hand-approval") {
                        it.packageName?.toString()==pkg && it.isEditable && it.text?.toString()==marker
                    }
                }
                mode.set(3);launch();input("这个测试应用能做什么")
                panel.waitNode("an ordinary question stays in chat") { it.text?.contains("Fixture answered without using the phone.")==true }
                withTimeout(8_000) { while(c.androidUse.activeSession.value!=null) delay(60) }
                panel.dismissPanel()
                mode.set(4); managedSid.set(null)
                launch();input(indirect)
                withTimeout(24_000) {
                    while(completions.get()<3) {
                        server.failure?.let { throw AssertionError("Mock provider: $it",it) }
                        c.androidUse.activeSession.value?.let { assertNull(c.runManager.live(it).value.pendingApproval) }
                        delay(60)
                    }
                }
                val handedOff=checkNotNull(managedSid.get());sessions+=handedOff
                withTimeout(12_000) { while(c.androidUse.isActive(handedOff)||c.runManager.isRunning(handedOff)) delay(70) }
                if(c.runtimePolicy.assistantPanelVisible.value) panel.dismissPanel()
                val before=received.get()
                source=c.sessions.createSession("Temporary Code handoff",project)
                c.codePhoneHandoff.offer(checkNotNull(source),project,prompt)
                launch();panel.openFixtureTaskReview()
                panel.waitNode("imported draft remains a proposal") { it.text?.toString()==label(R.string.android_use_from_code) }
                assertNull(c.androidUse.activeSession.value);assertEquals(before,received.get())
            } finally {
                if(c.runtimePolicy.assistantPanelVisible.value) panel.dismissPanel()
                c.androidUse.activeSession.value?.let(sessions::add)
                sessions.forEach { sid ->
                    if(c.androidUse.isActive(sid)) c.androidUse.end(sid)
                    c.runManager.stopAndJoin(sid)
                    c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                }
                source?.let { sid -> c.codePhoneHandoff.cancel(sid); c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
                if(history==null) c.phoneHistory.clear(project) else c.phoneHistory.save(project,history)
                if(hadKey) settings.autoManageUserTasks=oldSetting else prefs.edit().remove("auto_manage_user_tasks").apply()
                c.settings.setAssistantModel(old.assistantProviderId,old.assistantModel);c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground);c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId);c.settings.setActiveModel(old.activeModel);c.providers.delete(provider.id)
            }
        }
    }
}
