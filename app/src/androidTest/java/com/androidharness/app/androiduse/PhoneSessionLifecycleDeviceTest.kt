package com.androidharness.app.androiduse

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.data.env.DeviceAccessStore
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhoneSessionLifecycleDeviceTest {
    private val fixture = "com.androidharness.fixture.phoneuse"
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    @Test fun managedGrantEndsWithTheTaskAndDoesNotAlterTheChatsDevicePermission() = runBlocking {
        val phone = c.androidUse
        assumeTrue(phone.activeSession.value == null)
        val id = "managed-test-" + UUID.randomUUID()
        val previous = c.deviceAccess.phoneControlAllowedNow(id)
        try {
            assertFalse(phone.begin(id, fixture, managed = true, userInstruction = " "))
            assertTrue(phone.begin(id, fixture, managed = true, userInstruction = "在计算器算 37×19"))
            assertTrue(phone.isManaged(id))
            assertFalse(phone.isManaged("elsewhere"))
            assertEquals(previous, c.deviceAccess.phoneControlAllowedNow(id))
            phone.end(id)
            assertFalse(phone.isManaged(id))
            assertTrue(phone.begin(id, fixture))
            assertFalse(phone.isManaged(id))
            assertEquals(previous, c.deviceAccess.phoneControlAllowedNow(id))
        } finally { phone.end(id) }
    }

    @Test fun managedScriptApprovalBindsOneExecutionToExactReviewedSourceAndLease() = runBlocking {
        val phone=c.androidUse
        assumeTrue(phone.activeSession.value == null)
        val sid="script-approval-" + UUID.randomUUID()
        val original=phone.settings.autoJsEnabled
        val name="fixture-" + UUID.randomUUID()
        val first="""text("Green").waitFor();"""
        val second="""text("Blue").waitFor();"""
        fun named() = buildJsonObject { put("name",name) }
        fun anonymous(source:String) = buildJsonObject { put("source",source) }
        suspend fun beforeRoute(source:String, saved:String?=null) = Json.parseToJsonElement(
            phone.runAutoJsScript(sid,source,saved,requireScreenRoute=true).output).jsonObject.getValue("message").jsonPrimitive.content
        try {
            phone.settings.autoJsEnabled=true
            assertTrue(phone.begin(sid,fixture,true,"测试匿名与已保存的脚本"))
            phone.bindTaskScope(sid,buildJsonObject { put("taskScope",fixtureTaskScope("operate","change")) })
            phone.scripts.save(name,first)
            val old=phone.scriptApproval(sid,named())
            assertTrue(old.preview.contains(first))
            phone.scripts.save(name,second)
            assertFalse(old.confirm())
            assertTrue(beforeRoute(second,name).contains("execution approval"))
            val current=phone.scriptApproval(sid,named())
            assertTrue(current.preview.contains(second))
            assertTrue(current.confirm())
            // Named and anonymous are not interchangeable even when the text is identical.
            assertTrue(beforeRoute(second).contains("execution approval"))
            assertTrue(phone.scriptApproval(sid,anonymous(first)).confirm())
            assertTrue(beforeRoute(first).contains("android_observe(driver=autojs)"))
            assertTrue(beforeRoute(first).contains("execution approval"))
            val staleLease=phone.scriptApproval(sid,named())
            phone.end(sid)
            assertTrue(phone.begin(sid,fixture,true,"另开任务"))
            assertFalse(staleLease.confirm())
        } finally {
            phone.end(sid); if (phone.scripts.get(name)!=null) phone.scripts.delete(name)
            phone.settings.autoJsEnabled=original
        }
    }

    @Test fun stoppingDuringPanelHandoffCancelsTheOldCallWithoutTouchingTheReplacementLease() = runBlocking {
        val phone = c.androidUse
        assumeTrue(phone.activeSession.value == null)
        val old = "phone-policy-${UUID.randomUUID()}"
        val next = "phone-policy-${UUID.randomUUID()}"
        val original = phone.settings.backend
        val waiting = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val grant = DeviceAccessStore(c.appContext)
        val observe = phone.tools().single { it.name == "android_observe" }
        val context = ToolContext(FileFs(c.appContext.cacheDir), sessionId = old)
        try {
            phone.settings.backend = "builtin"
            assertFalse(phone.begin(old, c.appContext.packageName))
            assertTrue(phone.begin(old, fixture) { finished.complete(Unit) })
            grant.allowPhoneControl(old, true)
            assertFalse(phone.begin(next, fixture))
            assertFalse(observe.isAvailable(context.copy(sessionId = next)))
            withContext(Dispatchers.Main) { PhonePanel.bind(old) { waiting.complete(Unit); awaitCancellation() } }
            val oldCall = async(Dispatchers.Default) { observe.execute(buildJsonObject {}, context) }
            withTimeout(3_000) { waiting.await() }
            phone.end(old)
            // Grant a different chat before the old cleanup callback has necessarily arrived.
            assertTrue(phone.begin(next, fixture))
            phone.end(old)
            withTimeout(3_000) { oldCall.join(); finished.await() }
            assertTrue(oldCall.isCancelled)
            assertTrue(phone.isActive(next))
            assertFalse(phone.isActive(old))
            assertFalse(observe.execute(buildJsonObject {}, context).ok)
        } finally {
            phone.end(old)
            phone.end(next)
            withContext(Dispatchers.Main) { PhonePanel.unbind(old) }
            grant.allowPhoneControl(old, false)
            phone.settings.backend = original
        }
    }

    @Test fun oldFramesForeignLeasesAndMetadataOnlyScreensCannotDriveCoordinates() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true")
        awaitFixturePhoneService()
        val phone = c.androidUse
        assumeTrue(phone.activeSession.value == null)
        val id = "phone-policy-${UUID.randomUUID()}"
        val original = phone.settings.backend
        val originalContext = ToolContext(FileFs(c.appContext.cacheDir), sessionId = id, imageInputAllowed = false)
        val tools = phone.tools().associateBy { it.name }
        val observe = tools.getValue("android_observe")
        val action = tools.getValue("android_action")
        val grant = DeviceAccessStore(c.appContext)
        try {
            phone.settings.backend = "builtin"
            c.appContext.startActivity(Intent().setComponent(ComponentName(fixture, "$fixture.FixtureActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(phone.begin(id, fixture))
            grant.allowPhoneControl(id, true)
            withContext(Dispatchers.Main) { PhonePanel.bind(id) { true } }
            suspend fun observeId(): String = withTimeout(10_000) {
                while (true) {
                    val r = observe.execute(buildJsonObject { put("image", false) }, originalContext)
                    if (r.ok) {
                        val json = Json.parseToJsonElement(r.output).jsonObject
                        assertEquals(fixture, json.getValue("packageName").jsonPrimitive.content)
                        assertNull(r.image)
                        return@withTimeout json.getValue("observationId").jsonPrimitive.content
                    }
                    delay(120)
                }
                @Suppress("UNREACHABLE_CODE") error("The fixture did not become observable.")
            }
            val first = observeId()
            val second = observeId()
            assertNotEquals(first, second)
            fun tap(frame: String) = buildJsonObject {
                put("kind", "tap"); put("observationId", frame); put("x", 0); put("y", 0)
            }
            val wrongOwner = action.execute(tap(second), originalContext.copy(sessionId = "other"))
            assertFalse(wrongOwner.ok)
            assertTrue(wrongOwner.output, wrongOwner.output.contains("No active phone task"))
            val stale = action.execute(tap(first), originalContext)
            assertFalse(stale.ok)
            assertTrue(stale.output, stale.output.contains("Stale observation"))
            phone.markDelivered(setOf("a-different-image.jpg"), id)
            val notDelivered = action.execute(tap(second), originalContext)
            assertFalse(notDelivered.ok)
            assertTrue(notDelivered.output, notDelivered.output.contains("pixels"))
            fun launch(pkg: String) = buildJsonObject {
                put("kind", "launch"); put("observationId", second); put("packageName", pkg)
            }
            assertFalse(phone.needsRetarget(id, launch(fixture)))
            assertTrue(phone.needsRetarget(id, launch("example.other")))
            assertEquals("$fixture → example.other", phone.submissionPreview(id, launch("example.other")))
            phone.end(id)
            assertNull(phone.submissionPreview(id, launch("example.other")))
            assertFalse(action.execute(tap(second), originalContext).ok)
        } finally {
            phone.end(id)
            withContext(Dispatchers.Main) { PhonePanel.unbind(id) }
            grant.allowPhoneControl(id, false)
            phone.settings.backend = original
        }
    }
}
