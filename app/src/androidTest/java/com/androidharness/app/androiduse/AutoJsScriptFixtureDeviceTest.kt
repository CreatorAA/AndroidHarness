package com.androidharness.app.androiduse

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.data.env.DeviceAccessStore
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: uses the emulator's user-paired, official AutoJs6 MCP server and the independent fixture. */
@RunWith(AndroidJUnit4::class)
class AutoJsScriptFixtureDeviceTest {
    @Test fun previewDoesNotRunAndConfirmedNamedAndInlineStepsUseTheRealCompanion() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("realAutoJsScripts") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        val app = ins.targetContext.applicationContext as HarnessApp
        val phone = app.container.androidUse
        val original = phone.settings.backend
        val wasEnabled = phone.settings.autoJsEnabled
        val pkg = "com.androidharness.fixture.phoneuse"
        val sid = UUID.randomUUID().toString()
        val name = "test-" + sid.take(8)
        val grant = DeviceAccessStore(app)
        val ctx = ToolContext(FileFs(app.cacheDir), sessionId = sid, imageInputAllowed = false)
        val text = "脚本实测 " + sid.take(5)
        val source = """id("message").findOne(2000).setText("$text");
            |text("$text").waitFor();""".trimMargin()
        val inline = """id("choose_second").findOne(2000).click();
            |text("第二步 · 核对").waitFor();""".trimMargin()
        fun body(r: ToolResult): JsonObject {
            assertTrue(r.output, r.ok)
            return Json.parseToJsonElement(r.output).jsonObject
        }
        val tools = phone.autoJsTools(true).associateBy { it.name }
        val lib = tools.getValue(AutoJsUiScripts.LIBRARY)
        val runner = tools.getValue(AutoJsUiScripts.RUN)
        suspend fun screen(driver: String = "autojs"): JsonObject {
            val obs = phone.tools().single { it.name == "android_observe" }
            var offer: JsonElement? = null
            repeat(3) {
                val result = body(obs.execute(buildJsonObject {
                    put("image", false)
                    put("driver", driver)
                    offer?.let { put("declineOffer", it); put("reason", "This task tests AutoJs UI selectors on the fixture form.") }
                }, ctx))
                if (result.getValue("route").jsonPrimitive.content == "screen") {
                    assertEquals(pkg, result.getValue("packageName").jsonPrimitive.content)
                    return result
                }
                offer = result.getValue("observationId")
            }
            error("The fixture screen route was not available.")
        }
        fun shows(f: JsonObject, part: String) = f.getValue("nodes").jsonArray.any {
            it.jsonObject["text"]?.jsonPrimitive?.content?.contains(part) == true
        }
        try {
            assumeTrue(phone.activeSession.value == null)
            phone.settings.backend = "builtin"
            phone.settings.autoJsEnabled = false
            assertFalse(lib.isAvailable(ctx))
            phone.settings.autoJsEnabled = true
            assertEquals("builtin", phone.settings.backend)
            assertTrue(lib.isAvailable(ctx))
            app.startActivity(Intent().setComponent(ComponentName(pkg, "$pkg.FixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            delay(750)
            assertTrue(phone.begin(sid, pkg))
            grant.allowPhoneControl(sid, true)
            withContext(Dispatchers.Main) { PhonePanel.bind(sid) { true } }
            assertTrue(shows(screen("builtin"), "第一步"))
            val caps = phone.tools().single { it.name == "android_capabilities" }
            body(caps.execute(buildJsonObject { put("driver","autojs") }, ctx))
            assertFalse(shows(screen(), text))
            suspend fun named(op: String) = lib.execute(buildJsonObject { put("action", op); put("name", name) }, ctx)
            assertTrue(lib.execute(buildJsonObject { put("action", "save"); put("name", name); put("source", source) }, ctx).ok)
            assertEquals(source, body(named("read")).getValue("source").jsonPrimitive.content)
            assertEquals("validated_no_execution", body(named("test")).getValue("status").jsonPrimitive.content)
            assertTrue(named("disable").ok)
            val namedArgs = buildJsonObject { put("name", name) }
            assertFalse(runner.execute(namedArgs, ctx).ok)
            assertFalse("Preview and disabled execution cannot write the field.", shows(screen(), text))
            assertTrue(named("enable").ok)
            val saved = body(runner.execute(namedArgs, ctx))
            assertEquals("ran", saved.getValue("status").jsonPrimitive.content)
            assertEquals(2, saved.getValue("completedSteps").jsonPrimitive.int)
            assertEquals(1, saved.getValue("verifiedChecks").jsonPrimitive.int)
            assertTrue(shows(screen(), text))
            val anon = body(runner.execute(buildJsonObject { put("source", inline) }, ctx))
            assertEquals("ran", anon.getValue("status").jsonPrimitive.content)
            assertEquals(2, anon.getValue("completedSteps").jsonPrimitive.int)
            val after = screen()
            assertTrue(shows(after, "白鹭"))
            assertTrue(shows(after, text))
            assertTrue(named("delete").ok)
            assertFalse(named("read").ok)
            phone.settings.autoJsEnabled = false
            assertFalse(lib.isAvailable(ctx))
            assertFalse(runner.isAvailable(ctx))
            assertTrue(shows(screen("builtin"), "白鹭"))
            ins.sendStatus(0, Bundle().apply { putString("scriptEvidence",
                "Official companion: preview unchanged; disabled refused; named setText + wait; anonymous click + wait; fixture review shows 白鹭 and the exact input. No submit.") })
        } finally {
            if (phone.scripts.get(name) != null) phone.scripts.delete(name)
            phone.end(sid)
            withContext(Dispatchers.Main) { PhonePanel.unbind(sid) }
            grant.allowPhoneControl(sid, false)
            phone.settings.backend = original
            phone.settings.autoJsEnabled = wasEnabled
        }
        Unit
    }
}
