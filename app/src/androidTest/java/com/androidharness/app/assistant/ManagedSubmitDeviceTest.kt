package com.androidharness.app.assistant

import android.app.UiAutomation
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
import com.androidharness.app.core.Role
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.settings.AssistantPhoneSettings
import com.androidharness.app.ui.theme.HarnessTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Full control is granted by the human's actual settings switch. Only the provider is mocked;
 * the form, submit callback and persisted count are the separate fixture's real Android UI. */
class ManagedSubmitDeviceTest {
    @Test fun enabledFullControlSubmitsExactlyOnceWithoutReview() = runForm(followUp=false)
    @Test fun naturalFollowUpExecutesEarlierPlanExactlyOnce() = runForm(followUp=true)
    private fun runForm(followUp:Boolean): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay")=="true" &&
            Build.HARDWARE in setOf("goldfish","ranchu"))
        val ins=InstrumentationRegistry.getInstrumentation()
        val ctx=ins.targetContext
        val c=(ctx.applicationContext as HarnessApp).container
        val ui=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val panel=AssistantPhoneOverlayDeviceTest()
        val pkg="com.androidharness.fixture.phoneuse"
        val settings=c.androidUse.settings
        val old=c.settings.settings.first()
        val oldManage=settings.autoManageUserTasks
        val oldBackend=settings.backend
        val project=c.workspace.currentProjectOnce().id
        val history=c.phoneHistory.load(project)
        val note="归帆"+SystemClock.uptimeMillis()
        val earlier="这份表单要填「$note」、选白鹭、最后提交一次。应该怎么做？"
        val plan="先在留言框填上「$note」，然后选下方的白鹭，核对无误后点一次确认提交。"
        val prompt=if(followUp)"你直接帮我一口气搞完吧" else "帮我操作手机：在这份表单里填上「$note」，选白鹭，再点确认提交，只提交一次。"
        val done="已选择白鹭，表单只提交了一次。"
        val submitCalls=AtomicInteger()
        val readings=mutableSetOf<String>()
        val userCount=AtomicInteger(-1)
        var nativeOffers=0
        fun label(id:Int)=ContextCompat.getContextForLanguage(ctx).getString(id)
        val managedStart=label(R.string.phone_managed_start)
        val allowStart=label(R.string.assistant_phone_allow_once)
        fun startCard(n:AccessibilityNodeInfo?):Boolean {
            if(n==null || n.packageName?.toString()!=ctx.packageName)return false
            if(n.isVisibleToUser && (n.text?.toString() in setOf(managedStart,allowStart) ||
                        n.contentDescription?.toString() in setOf(managedStart,allowStart)))return true
            return (0 until n.childCount).any { startCard(n.getChild(it)) }
        }
        fun readCount(s:String):Int?=Regex("""提交次数：(\d+)""").find(s)?.groupValues?.get(1)?.toInt()
        fun readyNode(predicate:(AccessibilityNodeInfo)->Boolean)=panel.waitNode("real test form") {
            it.packageName?.toString()==pkg && predicate(it)
        }
        fun observe(offer:JsonElement?=null)=buildJsonObject {
            put("image",false);put("ocr",false)
            if(offer!=null){put("declineOffer",offer);put("reason","These offered actions do not fill or submit this form.")}
        }
        check(c.androidUse.activeSession.value==null && c.codePhoneHandoff.current()==null)
        awaitFixturePhoneService();panel.awaitAssistantService()
        panel.OverlayServer { body ->
            val messages=body.getValue("messages").jsonArray.map { it.jsonObject }
            check(messages.any { it["role"]?.jsonPrimitive?.content=="user" && prompt in it["content"].toString() })
            val receipt=messages.lastOrNull { it["role"]?.jsonPrimitive?.content=="tool" }
            if(followUp && receipt==null){
                val oldQuestion=messages.indexOfFirst { it["role"]?.jsonPrimitive?.content=="user" && earlier in it["content"].toString() }
                val oldPlan=messages.indexOfFirst { it["role"]?.jsonPrimitive?.content=="assistant" && plan in it["content"].toString() }
                val now=messages.indexOfLast { it["role"]?.jsonPrimitive?.content=="user" && prompt in it["content"].toString() }
                check(oldQuestion>=0 && oldPlan>oldQuestion && now>oldPlan) { "The model cannot relate the follow-up to the earlier request and plan" }
                val offered=body.getValue("tools").jsonArray.map { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
                check("android_observe" in offered && "android_action" in offered) { "Execution requires a different entry point" }
            }else check(c.androidUse.activeSession.value?.let(c.androidUse::isManaged)==true)
            if(receipt==null){
                val scope=if(followUp)fixtureTaskScope("operate","edit","save") else buildJsonObject { put("access","operate") }
                return@OverlayServer function("first","android_observe",JsonObject(observe()+("taskScope" to scope)))
            }
            val id=receipt.getValue("tool_call_id").jsonPrimitive.content
            val contents=receipt.getValue("content")
            val payload=if(contents is JsonArray)contents.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content=="text" }.getValue("text") else contents
            val result=Json.parseToJsonElement(payload.jsonPrimitive.content.substringBefore("\n[took ").trim()).jsonObject
            if(id in setOf("write","choose","commit")){
                check(result["status"]?.jsonPrimitive?.content in setOf("verified","dispatched_unverified")) { "Not dispatched: $id" }
                return@OverlayServer function("${id}_read","android_observe",observe())
            }
            if(result["route"]?.jsonPrimitive?.content!="screen"){
                check(++nativeOffers<=8)
                return@OverlayServer function(id+"_screen","android_observe",observe(result.getValue("observationId")))
            }
            check(result.getValue("packageName").jsonPrimitive.content==pkg)
            check(readings.add(result.getValue("observationId").jsonPrimitive.content))
            val nodes=result.getValue("nodes").jsonArray.map { it.jsonObject }
            fun text(n:JsonObject)=n["text"]?.jsonPrimitive?.content.orEmpty()
            fun action(s:String,node:JsonObject,kind:String, payload:JsonObjectBuilder.()->Unit)=
                function(s,"android_action",buildJsonObject{
                    put("observationId",result.getValue("observationId"));put("node",node.getValue("id"));put("kind",kind);payload()
                })
            when(id.substringBefore("_screen")){
                "first" -> {
                    check(nodes.any { readCount(text(it))==userCount.get() })
                    action("write",nodes.single { it["editable"]?.jsonPrimitive?.booleanOrNull==true },"set_text"){put("text",note)}
                }
                "write_read" -> {
                    check(nodes.any { text(it)==note })
                    val options=nodes.filter { text(it)=="选择" && it["clickable"]?.jsonPrimitive?.booleanOrNull==true }
                    check(options.size==2 && nodes.any { "下方选项：白鹭"==text(it) })
                    action("choose",options.maxBy { it.getValue("screenBounds").jsonArray[1].jsonPrimitive.int },"tap"){
                        put("intent","navigate");put("expectedText","第二步 · 核对")
                    }
                }
                "choose_read" -> {
                    check(nodes.any { text(it)=="选项：白鹭\n留言：$note" })
                    check(submitCalls.incrementAndGet()==1) { "Model attempted to submit twice" }
                    action("commit",nodes.single { text(it)=="确认提交" },"tap"){
                        put("intent","submit");if(followUp)put("effect","save");put("expectedText","第三步 · 已收到")
                    }
                }
                "commit_read" -> {
                    check(nodes.any { text(it)=="提交次数：${userCount.get()+1}\n选项：白鹭\n留言：$note" })
                    event(buildJsonObject{put("content",done)},"stop")
                }
                else->error("Unexpected fixture step: $id")
            }
        }.use { server ->
            val model="submit-once"
            val provider=c.providers.add("Temporary form trial",ProviderType.OPENAI_COMPAT,"http://127.0.0.1:${server.port}/v1",model,"fixture")
            val sid=c.sessions.createSession("Synthetic managed form",project,ConversationConfig(ConversationKind.PHONE))
            try{
                c.providers.saveCatalog(provider.id,listOf(ModelEntry(model)))
                c.settings.setActiveSelection(provider.id,model);c.settings.setAssistantModel(provider.id,model)
                c.settings.setAssistantVoiceFirst(false);c.settings.setRunInBackground(true);settings.backend="builtin"
                if(followUp){
                    c.sessions.addMessage(sid,ChatMessage(Role.USER,earlier),"plan-only")
                    c.sessions.addMessage(sid,ChatMessage(Role.ASSISTANT,plan),"plan-only")
                }
                c.phoneHistory.save(project,sid)
                withTimeout(8_000){c.chatModels.observe(null).first { it.selection.active?.providerId==provider.id }}
                settings.autoManageUserTasks=false
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scene ->
                    scene.onActivity { activity -> activity.setContent { HarnessTheme { Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) { AssistantPhoneSettings(c) } } } }
                    panel.tap(label(R.string.phone_auto_manage))
                    withTimeout(4000){while(!settings.autoManageUserTasks)delay(60)}
                }
                ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("am start -W -f 0x10008000 -n $pkg/.FixtureActivity")).use { it.readBytes() }
                userCount.set(checkNotNull(readCount(readyNode { readCount(it.text?.toString().orEmpty())!=null }.text.toString())))
                panel.summon()
                val editor=panel.waitNode("ordinary assistant composer"){ it.packageName?.toString()==ctx.packageName && it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,prompt)
                }))
                // The next and final user action in the managed task is sending the request.
                panel.tap(label(R.string.assistant_send))
                withTimeout(55_000){
                    while(true){
                        server.failure?.let { throw AssertionError("Mock provider",it) }
                        assertNull("Per-action approval in full-control mode",c.runManager.live(sid).value.pendingApproval)
                        if(c.runtimePolicy.assistantPanelVisible.value)
                            assertFalse("A start approval was shown",ui.windows.any { startCard(it.root) })
                        if(!c.runManager.isRunning(sid) && c.sessions.messages(sid).any { it.role==Role.ASSISTANT && it.text==done })break
                        delay(70)
                    }
                }
                assertEquals(1,submitCalls.get())
                if(followUp){
                    val rows=c.sessions.messages(sid);val user=rows.filter { it.role==Role.USER }
                    assertEquals(2,user.size)
                    val turn=checkNotNull(user.single { prompt in it.text }.turnId)
                    assertNotEquals("plan-only",turn)
                    val executed=rows.filter { it.turnId==turn }
                    assertTrue(executed.any { it.role==Role.ASSISTANT && it.text==done })
                    assertEquals(3,executed.flatMap { it.toolCalls }.count { it.name=="android_action" })
                    assertFalse(executed.flatMap { it.toolCalls }.any { it.name=="suggest_app_action" })
                }
                if(c.runtimePolicy.assistantPanelVisible.value)panel.dismissPanel()
                val receipt=readyNode { it.text?.toString()=="提交次数：${userCount.get()+1}\n选项：白鹭\n留言：$note" }
                assertTrue(receipt.isVisibleToUser)
                ins.sendStatus(0,Bundle().apply { putString("managed_form","form_receipt_delta=1; start_cards=0; per_action_approvals=0") })
            }finally{
                if(c.runtimePolicy.assistantPanelVisible.value)panel.dismissPanel()
                c.androidUse.end(sid);c.runManager.stopAndJoin(sid);c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                if(history==null)c.phoneHistory.clear(project) else c.phoneHistory.save(project,history)
                settings.autoManageUserTasks=oldManage;settings.backend=oldBackend
                c.settings.setAssistantModel(old.assistantProviderId,old.assistantModel);c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground);c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId);c.settings.setActiveModel(old.activeModel);c.providers.delete(provider.id)
            }
        }
    }
}
