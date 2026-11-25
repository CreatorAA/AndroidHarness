package com.androidharness.app.tools

import com.androidharness.app.assistant.AppHandoffRequest
import com.androidharness.app.workspace.FileFs
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SuggestAppActionToolTest {
    private val ctx = ToolContext(FileFs(File(System.getProperty("java.io.tmpdir"))), sessionId = "panel")
    private fun args(app: String = "百度地图", query: String = "纸月测试公园") = buildJsonObject {
        put("action", "map_search"); put("app", app); put("query", query)
    }

    @Test fun returnsRealLocalStatusForOneReviewNeverClaimsOpened() = runBlocking {
        val seen = mutableListOf<AppHandoffRequest>()
        val tool = SuggestAppActionTool("panel") { seen += it; "未安装百度地图，可改用高德地图。" }
        assertFalse(tool.isAvailable(ctx.copy(sessionId = "regular")))
        assertFalse(tool.execute(args(), ctx.copy(sessionId = "regular")).ok)
        val result = tool.execute(args(), ctx)
        assertTrue(result.ok)
        val receipt = Json.parseToJsonElement(result.output).jsonObject
        assertEquals("review_presented", receipt["status"]?.jsonPrimitive?.content)
        assertEquals(false, receipt["launched"]?.jsonPrimitive?.boolean)
        assertTrue(receipt["detail"]!!.jsonPrimitive.content.contains("未安装百度地图"))
        assertFalse(tool.isAvailable(ctx))
        assertFalse(tool.execute(args(), ctx).ok)
        assertEquals(listOf(AppHandoffRequest("map_search", "百度地图", "纸月测试公园")), seen)
    }

    @Test fun managedTurnsOfferOnlySessionControlThroughThePanelTool() = runBlocking {
        var resets = 0
        val tool = SuggestAppActionTool("panel", newConversation = { resets++; "ready" })
        val properties = tool.parametersSchema.getValue("properties").jsonObject
        assertEquals(setOf("action"), properties.keys)
        assertEquals(listOf("new_conversation"), properties.getValue("action").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(tool.execute(args(), ctx).ok)
        assertFalse(tool.execute(buildJsonObject { put("action", "phone_task") }, ctx).ok)
        val reset = buildJsonObject { put("action", "new_conversation") }
        assertFalse(tool.execute(JsonObject(reset + ("text" to JsonPrimitive("injected"))), ctx).ok)
        assertTrue(tool.execute(reset, ctx).ok)
        assertFalse(tool.execute(reset, ctx).ok)
        assertEquals(1, resets)
    }

    @Test fun structuredArgumentsMayResolveReferencesButCannotRunAnArbitraryComponent() = runBlocking {
        var received: AppHandoffRequest? = null
        val tool = SuggestAppActionTool("panel") { received = it; "review only" }
        assertFalse(tool.execute(args(query = "one\ntwo"), ctx).ok)
        assertFalse(tool.execute(JsonObject(args() + ("component" to JsonPrimitive("external"))), ctx).ok)
        assertNull(received)
        // A conversational reference can resolve to a place named on a previous turn.
        val result = tool.execute(args(app = "City Atlas", query = "North exit"), ctx)
        assertTrue(result.ok)
        assertFalse(Json.parseToJsonElement(result.output).jsonObject.getValue("launched").jsonPrimitive.boolean)
        assertEquals(AppHandoffRequest("map_search", "City Atlas", "North exit"), received)
    }

    @Test fun expiredPanelCannotPresentOrDispatchAnAction() = runBlocking {
        val tool = SuggestAppActionTool("panel") { null }
        assertFalse(tool.execute(args(), ctx).ok)
    }

    @Test fun phoneTaskOffersOnlyReviewOfTheOriginalInstruction() = runBlocking {
        var reviews = 0
        val tool = SuggestAppActionTool("panel",
            reviewPhoneTask = { reviews++; "review only" }) { fail("unexpected direct handoff"); null }
        val args = buildJsonObject { put("action", "phone_task") }
        assertFalse(tool.execute(JsonObject(args + ("instruction" to JsonPrimitive("substitute another task"))), ctx).ok)
        assertFalse(tool.execute(args, ctx.copy(sessionId = "different")).ok)
        val proposal = tool.execute(args, ctx)
        assertTrue(proposal.ok)
        val receipt = Json.parseToJsonElement(proposal.output).jsonObject
        assertEquals("review_queued", receipt["status"]?.jsonPrimitive?.content)
        assertEquals(false, receipt["launched"]?.jsonPrimitive?.boolean)
        assertFalse(tool.execute(args, ctx).ok)
        assertEquals(1, reviews)
    }

}
