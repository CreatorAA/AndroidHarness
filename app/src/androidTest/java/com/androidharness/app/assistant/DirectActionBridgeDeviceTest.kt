package com.androidharness.app.assistant

import android.app.UiAutomation
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.R
import com.androidharness.app.androiduse.awaitFixturePhoneService
import com.androidharness.app.androiduse.fixtureTaskScope
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The only mock is the model. Each turn goes through system Assist, the real agent, one-task consent and tool approvals. */
@RunWith(AndroidJUnit4::class)
class DirectActionBridgeDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val c get() = (ctx.applicationContext as HarnessApp).container
    private val ui by lazy { ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES) }
    private val overlay = AssistantPhoneOverlayDeviceTest()
    private val pkg = "com.androidharness.fixture.phoneuse"
    private val query = "白鹭 & café #7"
    @Volatile private var scenario = ""
    @Volatile private var verified = false

    @Test fun routesOnAnActivityOfferingBothPublicAndDirectActions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeRouteFixture") == "true")
        check(shell("getprop ro.kernel.qemu") == "1" || shell("getprop ro.boot.qemu") == "1") { "Use a disposable test emulator." }
        check(shell("pm path $pkg").startsWith("package:"))
        check(c.androidUse.activeSession.value == null && c.codePhoneHandoff.current() == null)
        overlay.awaitAssistantService()
        val old = c.settings.settings.first()
        val oldBackend = c.androidUse.settings.backend
        val sessions = mutableListOf<String>()
        val model = "local-native-route"
        overlay.OverlayServer { body -> reply(this, body) }.use { server ->
            val provider = c.providers.add("Disposable native routes", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:${server.port}/v1",model,"fixture-not-a-key")
            try {
                c.providers.saveCatalog(provider.id, listOf(ModelEntry(model, imageInput = false)))
                c.settings.setActiveSelection(provider.id, model)
                c.settings.setAssistantModel(provider.id, model)
                c.settings.setAssistantVoiceFirst(false)
                c.settings.setRunInBackground(true)
                c.androidUse.settings.backend = "builtin"
                withTimeout(10_000L) { c.chatModels.observe(null).first { it.selection.active?.providerId == provider.id } }
                for (which in listOf("direct","public","screen","explicit_screen")) {
                    scenario = which; verified = false
                    // Instrumentation restarts the assistant process; rebind the already user-enabled
                    // emulator service only for the scenario that truly needs screen controls.
                    if (which.endsWith("screen")) awaitFixturePhoneService()
                    launchFixture(true)
                    overlay.waitNode("fixture ready") { it.packageName?.toString() == pkg && it.text?.toString() == "Direct Action fixture: ready" }
                    overlay.summon()
                    overlay.openFixtureTaskReview()
                    overlay.waitNode("consent target") { it.text?.toString() == pkg }
                    val instruction = overlay.waitNode("instruction") { it.isEditable && it.packageName?.toString() == ctx.packageName }
                    check(instruction.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Native route fixture $which")
                    }))
                    overlay.tap(label(R.string.assistant_phone_allow_once))
                    await("task lease") { c.androidUse.activeSession.value != null }
                    val sid = requireNotNull(c.androidUse.activeSession.value)
                    sessions += sid
                    await("model verified $which") {
                        server.failure?.let { throw AssertionError("Fixture model ($which): ${it.message}", it) }
                        overlay.approveFixtureStep(sid)
                        verified
                    }
                    await("completed turn") { !c.runManager.isRunning(sid) }
                    // A screen read hides the assistant. A finished engine can precede the system's
                    // onShow callback; dismiss only its returned answer, never the fixture underneath.
                    overlay.waitNode("returned answer for $which") {
                        it.packageName?.toString() == ctx.packageName && it.text?.contains("Route test completed.") == true
                    }
                    overlay.dismissPanel()
                    val expected = when (which) {
                        "direct" -> "Direct Action receipt: shown once"
                        "public" -> "Public map receipt: $query"
                        else -> "Direct Action fixture: ready"
                    }
                    overlay.waitNode("actual $which receipt") { it.packageName?.toString() == pkg && it.text?.toString() == expected }
                    ins.sendStatus(0, Bundle().apply { putString("routeEvidence","$which: $expected") })
                }
            } finally {
                sessions.forEach { sid ->
                    if (c.androidUse.isActive(sid)) c.androidUse.end(sid)
                    c.runManager.stopAndJoin(sid)
                    c.deviceAccess.store.allowPhoneControl(sid,false)
                    c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                }
                if (c.runtimePolicy.assistantPanelVisible.value) overlay.dismissPanel()
                launchFixture(false) // The map handler exists for this test only.
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

    private fun reply(server: AssistantPhoneOverlayDeviceTest.OverlayServer, body: JsonObject): String = with(server) {
        val last = body.getValue("messages").jsonArray.map { it.jsonObject }.lastOrNull { it["role"]?.jsonPrimitive?.content == "tool" }
        if (last == null) return function("first","android_observe",buildJsonObject {
            put("taskScope",if(scenario=="direct")buildJsonObject { put("access","operate") } else fixtureTaskScope("inspect"))
            put("image",false)
            if(scenario=="explicit_screen")put("route","screen")
        })
        val payload = last.getValue("content")
        val raw = (if (payload is JsonArray) payload.first { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }.jsonObject.getValue("text") else payload)
            .jsonPrimitive.content.substringBefore("\n[took ").trim()
        val obj = Json.parseToJsonElement(raw).jsonObject
        val step = last.getValue("tool_call_id").jsonPrimitive.content
        val observation = obj["observationId"]
        fun decline(call: String, reason: String) = function(call, "android_observe",buildJsonObject {
            put("declineOffer",requireNotNull(observation)); put("reason",reason); put("image",false)
        })
        fun action(kind: String, ref: String, args: JsonObject) = function("act","android_action",buildJsonObject {
            put("observationId",requireNotNull(observation)); put("kind",kind); put("actionRef",ref); put("arguments",args)
        })
        when (step) {
            "first" -> {
                if(scenario=="explicit_screen"){
                    check(obj.getValue("route").jsonPrimitive.content=="screen" &&
                        obj.getValue("nodes").jsonArray.any { it.jsonObject["text"]?.jsonPrimitive?.content=="Direct Action fixture: ready" }) { raw }
                    this@DirectActionBridgeDeviceTest.verified=true
                    return event(buildJsonObject { put("content","Route test completed.") },"stop")
                }
                check(obj["route"]?.jsonPrimitive?.content == "public_api") { raw }
                check(obj.getValue("actions").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == "map_search" })
                if (scenario == "public") action("public_api","map_search",buildJsonObject { put("query",query) })
                else decline("second","A location search cannot display or inspect the fixture receipt.")
            }
            "second" -> {
                check(obj["route"]?.jsonPrimitive?.content == "page_action") { raw }
                val row = obj.getValue("actions").jsonArray.single().jsonObject
                check(row["id"]?.jsonPrimitive?.content == "show_test_receipt")
                if (scenario == "direct") action("page_action",row.getValue("ref").jsonPrimitive.content,JsonObject(emptyMap()))
                else decline("third","The task asks to read the existing page, not to display a new receipt.")
            }
            "third","act" -> {
                when (scenario) {
                    "public" -> check(obj["status"]?.jsonPrimitive?.content == "dispatched_unverified")
                    "direct" -> check(obj["status"]?.jsonPrimitive?.content == "callback_received" &&
                        obj["values"]?.jsonObject?.get("status")?.jsonPrimitive?.content == "shown") { raw }
                    else -> check(obj["route"]?.jsonPrimitive?.content == "screen" && obj.getValue("nodes").jsonArray.any {
                        it.jsonObject["text"]?.jsonPrimitive?.content == "Direct Action fixture: ready"
                    }) { raw }
                }
                this@DirectActionBridgeDeviceTest.verified = true
                event(buildJsonObject { put("content", "Route test completed.") },"stop")
            }
            else -> error("Unexpected tool reply: $step")
        }
    }
    private fun launchFixture(map: Boolean) { shell("am start -W -f 0x10008000 -n $pkg/.DirectActionFixtureActivity --ez offer_fixture_map $map") }
    private fun label(id: Int) = ContextCompat.getContextForLanguage(ctx).getString(id)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun await(what: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 32_000
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(80) }
        error("Timed out: $what")
    }
}
