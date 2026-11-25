package com.androidharness.app.assistant

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

/** The provider cannot answer the new map from accessibility, OCR, fixture state, or metadata.
 * It reports four colors AND silhouettes from the actual inbound picture, in traversal order. */
class VisualRouteDeviceTest {
    private val pkg = "com.androidharness.fixture.phoneuse"
    private val board = "河岸步道示意图"
    private val request = "帮我在这页路线图里查一下，从起点到终点依次经过哪四个路标？按顺序告诉我每个路标的颜色和形状。"

    @Test fun explicitVisualReadUsesFreshProviderPixels() = routeTrial(requestImage = true)
    @Test fun inconclusiveTextIsFinalWithoutAnImageRequest() = routeTrial(requestImage = false)
    private fun routeTrial(requestImage:Boolean): Unit = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("assistantPhoneOverlay") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val ctx = ins.targetContext
        val c = (ctx.applicationContext as HarnessApp).container
        check(c.androidUse.activeSession.value == null && c.codePhoneHandoff.current() == null)
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val panel = AssistantPhoneOverlayDeviceTest()
        val old = c.settings.settings.first()
        val managed = c.androidUse.settings.autoManageUserTasks
        val backend = c.androidUse.settings.backend
        val project = c.workspace.currentProjectOnce().id
        val history = c.phoneHistory.load(project)
        val expected = AtomicReference<List<String>>()
        val wireAnswer = AtomicReference<String>()
        var textOnlyId: String? = null
        var offers = 0
        val stoppedText="结构文字看不出四个路标的颜色和形状，无法据此判断。"
        fun observeArgs(offer: JsonElement? = null, image:Boolean = false) = buildJsonObject {
            put("image", image); put("ocr", false)
            if (offer != null) { put("declineOffer",offer); put("reason","Offered actions do not describe the diagram.") }
        }
        awaitFixturePhoneService()
        panel.awaitAssistantService()
        panel.OverlayServer { body ->
            val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
            check(messages.any { it["role"]?.jsonPrimitive?.content == "user" && request in it["content"].toString() })
            val ix = messages.indexOfLast { it["role"]?.jsonPrimitive?.content == "tool" }
            if (ix < 0) {
                check(c.androidUse.activeSession.value?.let(c.androidUse::isManaged) == true) { "Not a managed phone turn" }
                return@OverlayServer function("route_structure", "android_observe", JsonObject(observeArgs() +
                    ("taskScope" to fixtureTaskScope("inspect",capture=true))))
            }
            val receipt = messages[ix]
            val call = receipt.getValue("tool_call_id").jsonPrimitive.content
            val payload = receipt.getValue("content")
            val raw = if (payload is JsonArray) payload.map { it.jsonObject }.first { it["type"]?.jsonPrimitive?.content=="text" }
                .getValue("text").jsonPrimitive.content else payload.jsonPrimitive.content
            val frame = Json.parseToJsonElement(raw.substringBefore("\n[took ").trim()).jsonObject
            if (frame["route"]?.jsonPrimitive?.content != "screen") {
                check(++offers <= 2)
                return@OverlayServer function("${call}_screen", "android_observe", observeArgs(frame.getValue("observationId"), call.startsWith("route_pixels")))
            }
            check(frame.getValue("packageName").jsonPrimitive.content == pkg)
            val nodes = frame.getValue("nodes").jsonArray.map { it.jsonObject }
            val boardNode = nodes.single { it["description"]?.jsonPrimitive?.content == board }
            val observation = frame.getValue("observationId").jsonPrimitive.content
            val wrapper = messages.getOrNull(ix+1)?.takeIf {
                it["role"]?.jsonPrimitive?.content == "user" && "tool_call_id=$call" in it["content"].toString()
            }
            val urls = listOfNotNull(receipt,wrapper).flatMap { (it["content"] as? JsonArray).orEmpty() }
                .map { it.jsonObject }.filter { it["type"]?.jsonPrimitive?.content == "image_url" }
                .map { it.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content }
            // Neither source text nor OCR should identify even one colored landmark.
            val labels = nodes.flatMap { listOf(it["text"], it["description"]) }.joinToString() + frame["ocrLines"].toString()
            check(!Regex("红色|绿色|蓝色|黄色|三角形|五角星|正方形|圆形").containsMatchIn(labels))
            if (textOnlyId == null) {
                check(!frame.getValue("image_attached").jsonPrimitive.boolean && urls.isEmpty() && call.startsWith("route_structure"))
                textOnlyId = observation
                if(requestImage)return@OverlayServer function("route_pixels","android_observe",observeArgs(image=true))
                check(wireAnswer.compareAndSet(null,stoppedText))
                return@OverlayServer event(buildJsonObject { put("content", stoppedText) }, "stop")
            }
            check(requestImage && call.startsWith("route_pixels")) { "Assistant prose triggered an unsolicited device read" }
            check(observation != textOnlyId && frame.getValue("image_attached").jsonPrimitive.boolean) { "No fresh image-bearing observation" }
            val url = urls.single().also { check(it.startsWith("data:image/") && ";base64," in it) }
            val bytes = Base64.decode(url.substringAfter(','), Base64.DEFAULT)
            val picture = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            val r = boardNode.getValue("screenBounds").jsonArray.map { it.jsonPrimitive.int }
            val detected = try { pixels(picture,Rect(r[0],r[1],r[2],r[3]),frame.getValue("width").jsonPrimitive.int,
                frame.getValue("height").jsonPrimitive.int) } finally { picture.recycle() }
            // Generate the answer from received pixels; the independent, pre-summon screen is only an assertion.
            val answer = detected.joinToString(" → ")
            check(detected == expected.get()) { "Wire image is not this visit's four landmarks in order" }
            check(wireAnswer.compareAndSet(null, answer)) { "Duplicate image recovery" }
            event(buildJsonObject { put("content",answer) }, "stop")
        }.use { server ->
            val model = "current-route-vision"
            val provider = c.providers.add("Temporary diagram vision",ProviderType.OPENAI_COMPAT,"http://127.0.0.1:${server.port}/v1", model,"fixture")
            val sid = c.sessions.createSession("Synthetic field guide",project,ConversationConfig(ConversationKind.PHONE))
            try {
                c.providers.saveCatalog(provider.id,listOf(ModelEntry(model,imageInput=true)))
                c.settings.setActiveSelection(provider.id,model); c.settings.setAssistantModel(provider.id,model)
                c.settings.setAssistantVoiceFirst(false); c.settings.setRunInBackground(true)
                c.androidUse.settings.backend="builtin"; c.androidUse.settings.autoManageUserTasks=true
                c.phoneHistory.save(project,sid)
                withTimeout(8000) { c.chatModels.observe(null).first { it.selection.active?.providerId == provider.id } }
                ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("am start -W -f 0x10008000 -n $pkg/.VisualRouteActivity")).use { it.readBytes() }
                val node = panel.waitNode("synthetic route map") { it.packageName?.toString()==pkg && it.contentDescription?.toString()==board && it.isVisibleToUser }
                val bounds=Rect().also { node.getBoundsInScreen(it) }
                withTimeout(3000) {
                    while(expected.get()==null){
                        check(ui.rootInActiveWindow?.packageName?.toString()==pkg)
                        val screenshot=ui.takeScreenshot()
                        if(screenshot!=null) try {
                            runCatching { pixels(screenshot,bounds,screenshot.width,screenshot.height) }.getOrNull()?.let(expected::set)
                        } finally { screenshot.recycle() }
                        if(expected.get()==null) delay(100)
                    }
                }
                panel.summon()
                val editor=panel.waitNode("ordinary assistant composer") { it.packageName?.toString()==ctx.packageName && it.isEditable }
                assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,request)
                }))
                val label=ContextCompat.getContextForLanguage(ctx).getString(R.string.assistant_send)
                var send: AccessibilityNodeInfo?=panel.waitNode("send") { it.packageName?.toString()==ctx.packageName && it.contentDescription?.toString()==label && it.isVisibleToUser }
                while(send!=null&&!send.isClickable)send=send.parent
                assertTrue(send?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                // No button on a start or action approval card is ever pressed.
                withTimeout(45_000) {
                    while(true){
                        server.failure?.let { throw AssertionError("Visual provider",it) }
                        check(c.runManager.live(sid).value.pendingApproval==null) { "Unexpected approval" }
                        val a=wireAnswer.get()
                        if(a!=null && c.sessions.messages(sid).any { it.role==Role.ASSISTANT && it.text==a } && !c.runManager.isRunning(sid))break
                        delay(70)
                    }
                }
                assertEquals(sid,c.phoneHistory.load(project))
                val wireCalls=c.sessions.messages(sid).flatMap { it.toolCalls }
                assertEquals(requestImage,wireCalls.any { it.id.startsWith("route_pixels") })
                if(!requestImage)assertTrue(wireCalls.all { it.id.startsWith("route_structure") })
                ins.sendStatus(0,Bundle().apply { putString("visual_route",if(requestImage) "requested_pixels; four_landmarks_matched" else "inconclusive_final; no_automatic_image") })
            } finally {
                if(c.runtimePolicy.assistantPanelVisible.value)panel.dismissPanel()
                c.androidUse.end(sid);c.runManager.stopAndJoin(sid)
                c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                if(history==null)c.phoneHistory.clear(project) else c.phoneHistory.save(project,history)
                c.androidUse.settings.backend=backend;c.androidUse.settings.autoManageUserTasks=managed
                c.settings.setAssistantModel(old.assistantProviderId,old.assistantModel);c.settings.setAssistantVoiceFirst(old.assistantVoiceFirst)
                c.settings.setRunInBackground(old.runInBackground);c.settings.setLastActiveSessionId(old.lastActiveSessionId)
                c.settings.setActiveProvider(old.activeProviderId);c.settings.setActiveModel(old.activeModel);c.providers.delete(provider.id)
            }
        }
    }

    /** Components are classified by hue and their occupied fraction of the tight ink bounds.
     * Looking at a center pixel alone could never distinguish a circle from a square. */
    private fun pixels(source: Bitmap, area: Rect, displayW: Int, displayH: Int): List<String> {
        val bmp=if(source.config==Bitmap.Config.HARDWARE)checkNotNull(source.copy(Bitmap.Config.ARGB_8888,false)) else source
        try {
            val sx=bmp.width.toFloat()/displayW;val sy=bmp.height.toFloat()/displayH
            val half=minOf(area.width(),area.height())*0.133f
            val rgb=arrayOf(intArrayOf(205,64,56),intArrayOf(38,125,73),intArrayOf(48,94,174),intArrayOf(220,168,13))
            val out=listOf(0.25f to 0.29f,0.75f to 0.29f,0.75f to 0.73f,0.25f to 0.73f).map { (u,v)->
                val cx=area.left+area.width()*u;val cy=area.top+area.height()*v
                val left=((cx-half)*sx).roundToInt();val top=((cy-half)*sy).roundToInt()
                val w=(half*2*sx).roundToInt();val h=(half*2*sy).roundToInt()
                check(left>=0&&top>=0&&w>10&&h>10&&left+w<=bmp.width&&top+h<=bmp.height)
                val block=IntArray(w*h);bmp.getPixels(block,0,w,left,top,w,h)
                val counts=IntArray(4);val boxes=Array(4){Rect(w,h,-1,-1)}
                for(i in block.indices){val p=block[i];val r=(p ushr 16) and 255; val g=(p ushr 8) and 255;val b=p and 255
                    for(k in rgb.indices){val c=rgb[k];val dr=r-c[0];val dg=g-c[1];val db=b-c[2]
                        if(dr*dr+dg*dg+db*db>48*48)continue
                        counts[k]++;val x=i%w;val y=i/w;val box=boxes[k]
                        box.left=minOf(box.left,x);box.right=maxOf(box.right,x);box.top=minOf(box.top,y);box.bottom=maxOf(box.bottom,y)
                        break
                    }
                }
                val color=counts.indices.maxBy { counts[it] }
                check(counts[color]>40 && counts.indices.all { it==color || counts[it]*12 < counts[color] }) { "No isolated color in landmark" }
                val box=boxes[color];val fill=counts[color].toDouble()/((box.width()+1)*(box.height()+1))
                val shape=when(fill){in 0.26..0.44->"五角星";in 0.45..0.59->"三角形";in 0.69..0.88->"圆形";in 0.91..1.0->"正方形";else->error("Unrecognized landmark silhouette: $fill")}
                listOf("红色","绿色","蓝色","黄色")[color]+shape
            }
            check(out.distinct().size==4) { "Missing distinct route markers" }
            return out
        } finally { if(bmp!==source)bmp.recycle() }
    }
}
