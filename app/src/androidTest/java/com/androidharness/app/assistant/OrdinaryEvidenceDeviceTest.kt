package com.androidharness.app.assistant

import android.app.UiAutomation
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
import com.androidharness.app.core.Role
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fresh ordinary turns, real UI and local provider. No special phrase, proposal, or second
 * human instruction supplies access. Model responses are scripted; node IDs and answers come from real observations. */
class OrdinaryEvidenceDeviceTest {
    private enum class Case(val question:String,val component:String,val entry:String){
        REACTION("他这句「真有啊」是在说哪样东西？","ReferencedChatActivity","真有啊！"),
        INTERNAL("打开手机操作验证里的群聊记录，读一下原文。","ReferencedChatActivity","真有啊！"),
        SCHEDULE("我周日下午才到，摄影展最晚几点还能进门？","ExhibitionScheduleActivity","周末展览日程"),
        GENERAL("写一句押韵的晚安祝福。","ExhibitionScheduleActivity","周末展览日程")
    }
    @Test fun spontaneousReactionQuestionReadsOriginal() = trial(Case.REACTION)
    @Test fun openInternalFeatureReachesTheModelAndReadScopeRejectsLaterWrites() = trial(Case.INTERNAL)
    @Test fun spontaneousScheduleQuestionReadsAdmissionTime() = trial(Case.SCHEDULE)
    @Test fun ordinaryGreetingDoesNotUsePhoneOrCollapsePanel() = trial(Case.GENERAL)
    private fun JsonObject.str(k:String)=this[k]?.jsonPrimitive?.content.orEmpty()
    private fun trial(test:Case):Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay")=="true" && Build.HARDWARE in setOf("goldfish","ranchu"))
        val ui=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val ctx=ins.targetContext;val c=(ctx.applicationContext as HarnessApp).container
        check(c.androidUse.activeSession.value==null && c.codePhoneHandoff.current()==null)
        val panel=AssistantPhoneOverlayDeviceTest();val pkg="com.androidharness.fixture.phoneuse"
        val old=c.settings.settings.first();val backend=c.androidUse.settings.backend;val manage=c.androidUse.settings.autoManageUserTasks
        val project=c.workspace.currentProjectOnce().id;val history=c.phoneHistory.load(project)
        val answer=AtomicReference<String>()
        var firstFrame="";var chosen=0;var offers=0;var modelRequests=0
        val denied=mutableListOf<String>()
        fun shell(cmd:String)=ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
        fun observe(offer:JsonElement?=null)=buildJsonObject { put("image",false);put("ocr",false)
            if(offer!=null){put("declineOffer",offer);put("reason","No offered action reveals the missing information on this page.")} }
        awaitFixturePhoneService();panel.awaitAssistantService()
        panel.OverlayServer { body->
            modelRequests++
            val ms=body.getValue("messages").jsonArray.map { it.jsonObject }
            check(ms.any { it.str("role")=="user" && test.question in it["content"].toString() })
            val receipt=ms.lastOrNull { it.str("role")=="tool" }
            if(receipt==null){
                val offered=body.getValue("tools").jsonArray.map { it.jsonObject["function"]?.jsonObject?.str("name") }
                check("android_observe" in offered && "android_action" in offered) { "No same-turn phone tools for an ordinary human question; no model proposal was authorized" }
                if(test==Case.GENERAL){
                    check(c.runtimePolicy.assistantPanelVisible.value) { "Chat collapsed before a tool request" }
                    check(ms.none { msg->(msg["content"] as? JsonArray).orEmpty().any { it.jsonObject.str("type")=="image_url" } }) { "The ordinary greeting sent an unnecessary picture" }
                    val text="月光洒满窗，愿你好梦长。";answer.set(text)
                    return@OverlayServer event(buildJsonObject { put("content",text) },"stop")
                }
                return@OverlayServer function("read_list","android_observe",JsonObject(observe() +
                    ("taskScope" to fixtureTaskScope("inspect"))))
            }
            check(test!=Case.GENERAL)
            val id=receipt.str("tool_call_id");val payload=receipt.getValue("content")
            val raw=if(payload is JsonArray)payload.map { it.jsonObject }.first { it.str("type")=="text" }.str("text") else payload.jsonPrimitive.content
            if(id in setOf("expand","try_send","try_write")){
                // Use a fresh frame and node for each attempted action, so a stale token cannot explain a denial.
                denied+=id
                val read=when(id){"expand"->"find_send";"try_send"->"find_editor";else->"reread_list"}
                return@OverlayServer function(read,"android_observe",observe())
            }
            val frame=Json.parseToJsonElement(raw.substringBefore("\n[took ").trim()).jsonObject
            if(id=="select_source"){
                check(frame.str("status") in setOf("verified","dispatched_unverified"))
                return@OverlayServer function("read_detail","android_observe",observe())
            }
            if(frame.str("route")!="screen"){
                check(++offers<=5)
                return@OverlayServer function(id+"_screen","android_observe",observe(frame.getValue("observationId")))
            }
            check(frame.str("packageName")==pkg)
            val nodes=frame.getValue("nodes").jsonArray.map { it.jsonObject }.associateBy { it.str("id") }
            val text=nodes.values.joinToString("\n") { it.str("text") }
            if(id.startsWith("find_send"))return@OverlayServer function("try_send","android_action",buildJsonObject {
                val send=nodes.values.single { it.str("text")=="发送" && it["clickable"]?.jsonPrimitive?.booleanOrNull==true }
                put("kind","tap");put("intent","submit");put("effect","send")
                put("node",send.getValue("id"));put("observationId",frame.getValue("observationId"))
            })
            if(id.startsWith("find_editor"))return@OverlayServer function("try_write","android_action",buildJsonObject {
                val edit=nodes.values.single { it["editable"]?.jsonPrimitive?.booleanOrNull==true }
                put("kind","set_text");put("text","fixture-forbidden-draft")
                put("node",edit.getValue("id"));put("observationId",frame.getValue("observationId"))
            })
            if(id.startsWith("read_list") || id.startsWith("reread_list")){
                if(firstFrame.isEmpty())firstFrame=frame.str("observationId")
                if(test==Case.INTERNAL && id.startsWith("read_list")){
                    return@OverlayServer function("expand","android_observe",JsonObject(observe() +
                        ("taskScope" to fixtureTaskScope("operate","edit","send"))))
                }
                check(nodes.values.none { it.str("text")=="fixture-forbidden-draft" })
                val targets=frame.getValue("actionTargets").jsonArray.map { it.jsonObject }
                val choice=targets.single { target->
                    val labels=target.getValue("evidenceNodes").jsonArray.map { nodes[it.jsonPrimitive.content]?.str("text").orEmpty() }
                    when(test){
                        Case.REACTION,Case.INTERNAL->"群聊的聊天记录" in labels
                        Case.SCHEDULE->labels.any { "周日" in it && "14:00" in it } && labels.any { "南馆" in it }
                        else->false
                    }
                }
                check(chosen++==0 && test.entry in text)
                check(if(test!=Case.SCHEDULE) !Regex("""HX-\d{4}""").containsMatchIn(text) else "停止入场：" !in text)
                return@OverlayServer function("select_source","android_action",buildJsonObject{
                    put("kind","tap");put("node",choice.getValue("tapNode"));put("observationId",frame.getValue("observationId"));put("intent","navigate")
                })
            }
            check(id.startsWith("read_detail") && firstFrame!=frame.str("observationId"))
            val reply=if(test!=Case.SCHEDULE){
                val model=Regex("""HX-\d{4}""").find(text)?.value?:error("No original model evidence")
                check("原厂离线点阵字库" in text)
                "他说的是 $model 阅读器的原厂离线点阵字库，备份里还留着它。"
            }else{
                check("周日" in text && "14:00" in text && "南馆" in text)
                val limit=Regex("""停止入场：(\d{2}:\d{2})""").find(text)?.groupValues?.get(1)?:error("Admission cutoff absent")
                "周日下午这场 $limit 停止入场。"
            }
            answer.set(reply);event(buildJsonObject {put("content",reply)},"stop")
        }.use { server->
            val model="ordinary-source-model";val provider=c.providers.add("Disposable ordinary questions",ProviderType.OPENAI_COMPAT,"http://127.0.0.1:${server.port}/v1",model,"fixture")
            val sid=c.sessions.createSession("Synthetic ordinary question",project,ConversationConfig(ConversationKind.PHONE))
            var watch:kotlinx.coroutines.Job?=null;var hidden=false
            try{
                c.providers.saveCatalog(provider.id,listOf(ModelEntry(model,imageInput=true)));c.settings.setActiveSelection(provider.id,model);c.settings.setAssistantModel(provider.id,model)
                c.settings.setAssistantVoiceFirst(false);c.settings.setRunInBackground(true);c.androidUse.settings.backend="builtin";c.androidUse.settings.autoManageUserTasks=true;c.phoneHistory.save(project,sid)
                withTimeout(8000){c.chatModels.observe(null).first { it.selection.active?.providerId==provider.id }}
                shell("am start -W -f 0x10008000 -n $pkg/.${test.component}")
                panel.waitNode("fixture list"){ it.packageName?.toString()==pkg && it.text?.toString()==test.entry }
                panel.summon()
                val editor=panel.waitNode("ordinary composer"){ it.packageName?.toString()==ctx.packageName && it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,test.question)}))
                check(c.runtimePolicy.assistantPanelVisible.value)
                watch=launch(start=CoroutineStart.UNDISPATCHED){ c.runtimePolicy.assistantPanelVisible.collect { if(!it)hidden=true } }
                panel.tap(ContextCompat.getContextForLanguage(ctx).getString(R.string.assistant_send))
                withTimeout(48_000){
                    while(true){server.failure?.let{throw AssertionError("Ordinary-turn provider",it)}
                        assertNull(c.runManager.live(sid).value.pendingApproval)
                        val a=answer.get()
                        if(a!=null && !c.runManager.isRunning(sid)&&c.sessions.messages(sid).any { it.role==Role.ASSISTANT&&it.text==a })break
                        delay(75)
                    }
                }
                val rows=c.sessions.messages(sid)
                val q=rows.single { it.role==Role.USER && it.text.contains(test.question) };val same=rows.filter { it.turnId==q.turnId }
                check(q.turnId!=null&&same.any { it.role==Role.ASSISTANT&&it.text==answer.get() })
                val actions=same.flatMap { it.toolCalls }.map { it.name }
                check(actions.none { it=="suggest_app_action" || it.startsWith("web_") })
                if(test==Case.GENERAL){assertEquals(1,modelRequests);assertTrue(actions.isEmpty());assertFalse("Panel hid during ordinary greeting",hidden);assertTrue(c.runtimePolicy.assistantPanelVisible.value)}
                else {assertEquals(if(test==Case.INTERNAL) 3 else 1,actions.count { it=="android_action" });assertTrue(actions.count { it=="android_observe" }>=2)}
                if(test==Case.INTERNAL){
                    assertEquals(listOf("expand","try_send","try_write"),denied)
                    val deniedReceipts=same.filter { it.role==Role.TOOL && it.toolCallId in denied }
                    assertEquals(3,deniedReceipts.size)
                    assertTrue(deniedReceipts.all { it.isError })
                }
                if(test==Case.REACTION || test==Case.INTERNAL){
                    val counts=shell("dumpsys activity $pkg/.${test.component}").lineSequence().map(String::trim).filter { it.startsWith("fixtureCounts:") }.toList()
                    assertEquals(listOf("fixtureCounts: opens=1 neighbors=0 sends=0"),counts)
                }
                ins.sendStatus(0,Bundle().apply{putString("ordinary_source",test.name+"; same_turn=true; page_actions="+chosen+"; panel_hidden="+hidden)})
            }finally{
                watch?.cancelAndJoin();if(c.runtimePolicy.assistantPanelVisible.value)panel.dismissPanel()
                c.androidUse.end(sid);c.runManager.stopAndJoin(sid);c.sessions.session(sid)?.let{c.sessions.deleteSession(it)}
                if(history==null)c.phoneHistory.clear(project) else c.phoneHistory.save(project,history)
                c.androidUse.settings.backend=backend;c.androidUse.settings.autoManageUserTasks=manage
                c.settings.setAssistantModel(old.assistantProviderId,old.assistantModel);c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground);c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId);c.settings.setActiveModel(old.activeModel);c.providers.delete(provider.id)
            }
        }
    }
}
