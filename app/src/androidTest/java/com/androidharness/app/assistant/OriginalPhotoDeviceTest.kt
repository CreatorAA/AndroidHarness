package com.androidharness.app.assistant

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
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
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A real field-record detail with a clickable photo, a non-photo neighbor, and no chat brand.
 * The scripted provider selects the observed original after inspecting an unreadable thumbnail.
 * Its answer is read from the original's provider-wire pixels. */
class OriginalPhotoDeviceTest {
    private val pkg="com.androidharness.fixture.phoneuse"
    private val question="帮我在这份交接记录里查一下，这批样本的登记号是多少？"
    private fun bounds(n:JsonObject):Rect=n.getValue("screenBounds").jsonArray.map { it.jsonPrimitive.int }.let { Rect(it[0],it[1],it[2],it[3]) }
    private fun JsonObject.str(k:String)=this[k]?.jsonPrimitive?.content.orEmpty()
    private fun picture(messages:List<JsonObject>,index:Int):Bitmap {
        val receipt=messages[index];val id=receipt.str("tool_call_id")
        val wrapper=messages.getOrNull(index+1)?.takeIf { it.str("role")=="user" && "tool_call_id=$id" in it["content"].toString() }
        val parts=listOfNotNull(receipt,wrapper).flatMap { (it["content"] as? JsonArray).orEmpty() }.map { it.jsonObject }
        val url=parts.single { it.str("type")=="image_url" }.getValue("image_url").jsonObject.str("url")
        check(url.startsWith("data:image/") && ";base64," in url)
        val bytes=Base64.decode(url.substringAfter(','),Base64.DEFAULT)
        return checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
    }
    // Optical reading, not a secret embedded in view IDs. Sample the seven illuminated segments
    // of each digit. The order is top, upper-left/right, middle, lower-left/right, bottom.
    private fun readPrint(b:Bitmap,r:Rect,w:Int,h:Int):String? {
        if(r.isEmpty || r.width().toFloat()/r.height() !in 1.5f..1.72f)return null
        val points=listOf(32 to 5,5 to 31,58 to 31,32 to 56,5 to 82,58 to 82,32 to 107)
        val digits=listOf(119,36,93,109,46,107,123,37,127,111)
        return buildString {
            for(i in 0 until 4){var mask=0
                for((j,p) in points.withIndex()){
                    val x=((r.left+r.width()*(58+i*91+p.first)/800f)*b.width/w).roundToInt()
                    val y=((r.top+r.height()*(318+p.second)/500f)*b.height/h).roundToInt()
                    if(x !in 0 until b.width || y !in 0 until b.height)return null
                    val c=b.getPixel(x,y);val red=Color.red(c);val green=Color.green(c);val blue=Color.blue(c)
                    if(red<90 && green in 40..115 && blue<105)mask=mask or (1 shl j)
                    else if(red<190||green<180||blue<155)return null
                }
                val digit=digits.indexOf(mask);if(digit<0)return null;append(digit)
            }
        }
    }
    @Test fun unclearThumbnailLeadsToFreshOriginalPixels():Unit=runBlocking {
        val ins=InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay")=="true" && Build.HARDWARE in setOf("ranchu","goldfish"))
        val ctx=ins.targetContext;val c=(ctx.applicationContext as HarnessApp).container
        check(c.androidUse.activeSession.value==null && c.codePhoneHandoff.current()==null)
        val ui=ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val panel=AssistantPhoneOverlayDeviceTest()
        val old=c.settings.settings.first();val managed=c.androidUse.settings.autoManageUserTasks;val backend=c.androidUse.settings.backend
        val project=c.workspace.currentProjectOnce().id;val history=c.phoneHistory.load(project)
        val decoded=AtomicReference<String>()
        var thumbnailRead=false;var seenId="";var offers=0
        fun observe(offer:JsonElement?=null)=buildJsonObject { put("image",true);put("ocr",false)
            if(offer!=null){put("declineOffer",offer);put("reason","No offered action reads the transfer record.")} }
        fun shell(cmd:String)=ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd)).bufferedReader().use { it.readText() }
        awaitFixturePhoneService();panel.awaitAssistantService()
        panel.OverlayServer { body->
            val ms=body.getValue("messages").jsonArray.map { it.jsonObject }
            check(ms.any { it.str("role")=="user" && question in it["content"].toString() })
            val ix=ms.indexOfLast { it.str("role")=="tool" }
            if(ix<0)return@OverlayServer function("record","android_observe",JsonObject(observe() +
                ("taskScope" to fixtureTaskScope("inspect",capture=true))))
            val tool=ms[ix];val id=tool.str("tool_call_id")
            val content=tool.getValue("content")
            val raw=if(content is JsonArray)content.map { it.jsonObject }.first { it.str("type")=="text" }.str("text") else content.jsonPrimitive.content
            val result=Json.parseToJsonElement(raw.substringBefore("\n[took ").trim()).jsonObject
            if(id=="open_media"){
                check(result.str("status") in setOf("verified","dispatched_unverified"))
                return@OverlayServer function("read_original","android_observe",observe())
            }
            if(result.str("route")!="screen"){
                check(++offers<=5)
                return@OverlayServer function(id+"_screen","android_observe",observe(result.getValue("observationId")))
            }
            check(result.str("packageName")==pkg)
            val nodes=result.getValue("nodes").jsonArray.map { it.jsonObject }
            val fw=result.getValue("width").jsonPrimitive.int;val fh=result.getValue("height").jsonPrimitive.int
            if(!id.startsWith("read_original")){
                val entries=result["imagePreviews"]?.jsonArray.orEmpty().map { it.jsonObject }
                val visibleById=nodes.associateBy { it.str("id") }
                // Select only from the application's actual discovery result, not a test ID or fixed location.
                val choice=entries.singleOrNull()?:error("Expected a unique source photo separate from its decorative action; got ${entries.size}")
                val subject=visibleById.getValue(choice.str("imageNode"))
                val tap=visibleById.getValue(choice.str("tapNode"))
                check(tap["clickable"]?.jsonPrimitive?.booleanOrNull==true)
                check(nodes.any { it.str("description").contains("头像") && it.str("id")!=tap.str("id") })
                check(!thumbnailRead && result.getValue("image_attached").jsonPrimitive.boolean)
                val thumb=picture(ms,ix);try{check(readPrint(thumb,bounds(subject),fw,fh)==null)} finally {thumb.recycle()}
                seenId=result.str("observationId");thumbnailRead=true
                return@OverlayServer function("open_media","android_action",buildJsonObject {
                    put("kind","tap");put("intent","navigate");put("observationId",seenId);put("node",tap.getValue("id"))
                })
            }
            check(thumbnailRead && result.str("observationId")!=seenId && result.getValue("image_attached").jsonPrimitive.boolean)
            // No title / OCR fallback. Find the original by optical decoding in current node bounds;
            // accept one unique number, and never use the fixture's number or a previous image.
            val textual=nodes.joinToString { it.str("text")+" "+it.str("description") }+result["ocrLines"].toString()
            check(!Regex("""(?<!\d)\d{4}(?!\d)""").containsMatchIn(textual))
            val original=picture(ms,ix)
            val number=try{nodes.map(::bounds).distinct().mapNotNull { readPrint(original,it,fw,fh) }.distinct().single()}finally{original.recycle()}
            check(decoded.compareAndSet(null,number))
            event(buildJsonObject{put("content","这批样本的登记号是 $number。")},"stop")
        }.use { server->
            val model="field-record-vision"
            val provider=c.providers.add("Temporary photo trial",ProviderType.OPENAI_COMPAT,"http://127.0.0.1:${server.port}/v1",model,"fixture")
            val sid=c.sessions.createSession("Synthetic transfer record",project,ConversationConfig(ConversationKind.PHONE))
            try {
                c.providers.saveCatalog(provider.id,listOf(ModelEntry(model,imageInput=true)))
                c.settings.setActiveSelection(provider.id,model);c.settings.setAssistantModel(provider.id,model)
                c.settings.setAssistantVoiceFirst(false);c.settings.setRunInBackground(true)
                c.androidUse.settings.backend="builtin";c.androidUse.settings.autoManageUserTasks=true;c.phoneHistory.save(project,sid)
                withTimeout(8000){c.chatModels.observe(null).first { it.selection.active?.providerId==provider.id }}
                shell("am start -W -f 0x10008000 -n $pkg/.TransferRecordActivity")
                panel.waitNode("synthetic source record"){ it.packageName?.toString()==pkg && it.text?.toString()=="样本交接记录" }
                panel.summon()
                val field=panel.waitNode("ordinary assistant composer"){it.packageName?.toString()==ctx.packageName&&it.isEditable}
                assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,question)}))
                panel.tap(ContextCompat.getContextForLanguage(ctx).getString(R.string.assistant_send))
                withTimeout(50_000){
                    while(true){
                        server.failure?.let{throw AssertionError("Photo provider",it)}
                        assertNull(c.runManager.live(sid).value.pendingApproval)
                        val n=decoded.get()
                        if(n!=null&&!c.runManager.isRunning(sid)&&c.sessions.messages(sid).any{ it.role==Role.ASSISTANT&&it.text=="这批样本的登记号是 $n。" })break
                        delay(75)
                    }
                }
                val n=checkNotNull(decoded.get());check(n.matches(Regex("""\d{4}""")))
                val evidence=shell("dumpsys activity $pkg/.TransferRecordActivity check-$n").lineSequence().map(String::trim).filter { it.startsWith("photoFixture:") }.toList()
                assertEquals(listOf("photoFixture: opened=1 decoration=0 answerMatches=true"),evidence)
                ins.sendStatus(0,Bundle().apply{putString("photo_trial","inconclusive_thumbnail; explicit_original_open=1; wrong_taps=0; wire_digits_match_fixture; no_approval")})
            }finally{
                if(c.runtimePolicy.assistantPanelVisible.value)panel.dismissPanel()
                c.androidUse.end(sid);c.runManager.stopAndJoin(sid);c.sessions.session(sid)?.let{c.sessions.deleteSession(it)}
                if(history==null)c.phoneHistory.clear(project) else c.phoneHistory.save(project,history)
                c.androidUse.settings.backend=backend;c.androidUse.settings.autoManageUserTasks=managed
                c.settings.setAssistantModel(old.assistantProviderId,old.assistantModel);c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground);c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId);c.settings.setActiveModel(old.activeModel);c.providers.delete(provider.id)
            }
        }
    }
}
