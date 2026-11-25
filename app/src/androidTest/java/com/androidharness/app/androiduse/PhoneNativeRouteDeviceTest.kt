package com.androidharness.app.androiduse

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.env.DeviceAccessStore
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The bridge is the only fake boundary here. No assistant panel or accessibility driver is bound. */
@RunWith(AndroidJUnit4::class)
class PhoneNativeRouteDeviceTest {
    @Test fun noClicksBeforeChoiceAndNoReplayAfterAmbiguousCallback() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val phone = app.container.androidUse
        val grant = DeviceAccessStore(app)
        val sid = "route-test-" + UUID.randomUUID()
        val pkg = "example.phone.routefixture"
        val ctx = ToolContext(FileFs(app.cacheDir), sessionId = sid, imageInputAllowed = false)
        val tools = phone.tools().associateBy { it.name }
        var sent = 0
        val source = object : PageActionSource {
            override suspend fun isCurrent(packageName: String, authorized: () -> Boolean) = authorized() && packageName == pkg
            override suspend fun discover(packageName: String, authorized: () -> Boolean): PageActionList {
                check(authorized() && packageName == pkg)
                return PageActionList("available", listOf(PageActionEntry("localRef", "lookup")))
            }
            override suspend fun perform(packageName: String, ref: String, arguments: JsonObject, authorized: () -> Boolean): PageActionReply {
                check(authorized() && packageName == pkg && ref == "localRef")
                assertEquals("test", arguments.getValue("query").jsonPrimitive.content)
                sent++
                return PageActionReply(false, JsonObject(emptyMap()))
            }
        }
        try {
            assertTrue(phone.begin(sid, pkg))
            grant.allowPhoneControl(sid, true)
            withContext(Dispatchers.Main) { PageActions.bind(sid, source) { phone.isActive(sid) } }
            val observation = tools.getValue("android_observe").execute(JsonObject(emptyMap()), ctx)
            assertTrue(observation.output, observation.ok)
            val offer = Json.parseToJsonElement(observation.output).jsonObject
            assertEquals("page_action", offer.getValue("route").jsonPrimitive.content)
            val id = offer.getValue("observationId").jsonPrimitive.content
            val action = tools.getValue("android_action")
            val skip = action.execute(buildJsonObject {
                put("kind", "tap"); put("x", 0); put("y", 0); put("observationId", id)
            }, ctx)
            assertFalse(skip.ok)
            assertTrue(skip.output, skip.output.contains("offered route"))
            fun args(query: String) = buildJsonObject {
                put("kind", "page_action"); put("observationId", id); put("actionRef", "localRef")
                put("intent", "submit") // This model-provided flag cannot mint a real native approval.
                putJsonObject("arguments") { put("query", query) }
            }
            val original = args("test")
            assertFalse(action.execute(original, ctx).ok)
            val approval = phone.nativeApproval(sid, original)
            assertTrue(approval.preview, approval.preview.contains(pkg) && approval.preview.contains("test"))
            assertTrue(approval.confirm())
            assertFalse(action.execute(args("different from preview"), ctx).ok)
            assertEquals(0, sent)
            assertTrue(phone.nativeApproval(sid, original).confirm())
            val receipt = action.execute(original, ctx)
            assertTrue(receipt.output, receipt.ok)
            assertEquals("outcome_unknown", Json.parseToJsonElement(receipt.output).jsonObject.getValue("status").jsonPrimitive.content)
            assertNotNull(phone.actionBlockReason(sid))
            assertFalse(action.execute(original, ctx).ok)
            assertEquals(1, sent)
        } finally {
            phone.end(sid)
            withContext(Dispatchers.Main) { PageActions.unbind(sid, source) }
            grant.allowPhoneControl(sid, false)
        }
    }
}
