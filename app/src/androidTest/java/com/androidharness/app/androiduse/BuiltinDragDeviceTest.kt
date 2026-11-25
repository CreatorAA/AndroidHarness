package com.androidharness.app.androiduse

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.data.env.DeviceAccessStore
import com.androidharness.app.tools.ToolContext
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

@RunWith(AndroidJUnit4::class)
class BuiltinDragDeviceTest {
    @Test fun originalPointerStaysDownUntilTheItemReachesItsTarget() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        val c = (ins.targetContext.applicationContext as HarnessApp).container
        val pkg = ins.context.packageName
        val phone = c.androidUse
        val old = phone.settings.backend
        val sid = UUID.randomUUID().toString()
        val ctx = ToolContext(FileFs(c.appContext.cacheDir), sessionId = sid)
        val grant = DeviceAccessStore(c.appContext)
        val tools = phone.tools().associateBy { it.name }
        val evidence = mutableListOf<String>()
        val frames = mutableListOf<String>()
        try {
            assumeTrue(phone.activeSession.value == null)
            phone.settings.backend = "builtin"
            c.appContext.startActivity(Intent().setComponent(ComponentName(pkg, DragTimingProbeActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            delay(1200)
            assertTrue(phone.begin(sid, pkg))
            grant.allowPhoneControl(sid, true)
            withContext(Dispatchers.Main) { PhonePanel.bind(sid) { true } }
            suspend fun observe(pixels: Boolean): JsonObject {
                val r = tools.getValue("android_observe").execute(buildJsonObject { put("image",pixels) }, ctx)
                assertTrue(r.output, r.ok)
                val f = Json.parseToJsonElement(r.output).jsonObject
                assertEquals(pkg, f.getValue("packageName").jsonPrimitive.content)
                assertEquals("screen", f.getValue("route").jsonPrimitive.content)
                if (pixels) {
                    val ref = requireNotNull(r.image); frames += ref.name
                    assertTrue("Missing preview bytes", requireNotNull(c.images.resolve(ref)).base64.isNotEmpty())
                    phone.markDelivered(setOf(ref.name), sid)
                }
                return f
            }
            for ((index, kind) in listOf("swipe","drag").withIndex()) {
                val f = observe(true)
                val w = f.getValue("previewWidth").jsonPrimitive.int; val h = f.getValue("previewHeight").jsonPrimitive.int
                val expected = if (kind == "drag") "held drag accepted" else "ordinary swipe"
                val r = tools.getValue("android_action").execute(buildJsonObject {
                    put("kind", kind); put("observationId",f.getValue("observationId")); put("expectedText",expected)
                    if (kind == "drag") put("intent","submit")
                    put("x",w / 4); put("y",h / 2); put("toX",w * 3 / 4); put("toY",h / 2); put("durationMs",380)
                }, ctx)
                assertTrue(r.output, r.ok)
                val descriptions = observe(false).getValue("nodes").jsonArray.mapNotNull { it.jsonObject["description"]?.jsonPrimitive?.content }
                assertEquals("$kind: $descriptions; ${r.output}", "verified", Json.parseToJsonElement(r.output).jsonObject.getValue("status").jsonPrimitive.content)
                val label = descriptions.single { it.startsWith(expected) }
                assertTrue(label, "downs=${index+1}; onePointer=true; releaseInTarget=true" in label)
                evidence += label
            }
            ins.sendStatus(0, Bundle().apply { putString("gestureEvidence", evidence.joinToString(" | ")) })
        } finally {
            phone.end(sid); grant.allowPhoneControl(sid, false)
            withContext(Dispatchers.Main) { PhonePanel.unbind(sid) }
            frames.forEach { c.images.delete(it) }
            phone.settings.backend = old
        }; Unit
    }
}
