package com.androidharness.app.androiduse

import com.androidharness.app.tools.mcp.TinyHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AutoJsPhoneDriverTest {
    private class Plugin : AutoCloseable {
        val server = TinyHttpServer()
        var pkg = "test.notes"
        var textSent: JsonObject? = null
        val sends = AtomicInteger()
        var onInput: () -> Unit = {}
        var omitImage = false
        var fieldValue = "草稿"
        var fullValue: String? = null
        var ambiguous = false
        var changeAfterDetail = false
        var httpLimit: (String) -> Int? = { null }
        var screenWait: Long? = null
        val arrivals = CopyOnWriteArrayList<Pair<String, Long>>()
        val detailReads = AtomicInteger()
        val compactReads = AtomicInteger()
        val captures = AtomicInteger()
        var failInput = false
        var redirect = false
        val permissions = mutableListOf<String?>()
        val driver: AutoJsPhoneDriver
        val names = listOf("device_ping","ui_current_window","screen_state","ui_dump","ui_set_text",
            "ui_click","ui_long_click","ui_swipe","ui_press_key","app_launch","screen_capture","script_run","shell_exec")

        init {
            server.route("/mcp") { conn ->
                val request = Json.parseToJsonElement(conn.request.body).jsonObject
                permissions += conn.request.headers["authorization"]
                val method = request["method"]!!.jsonPrimitive.content
                val op = if (method == "tools/call") request.getValue("params").jsonObject.getValue("name").jsonPrimitive.content else method
                arrivals += op to System.nanoTime()
                httpLimit(op)?.let { seconds -> conn.respond(429, headers = mapOf("Retry-After" to seconds.toString())); return@route }
                if (request["id"] == null) { conn.respond(202); return@route }
                val result = when (request["method"]!!.jsonPrimitive.content) {
                    "initialize" -> JsonObject(emptyMap())
                    "tools/list" -> buildJsonObject { put("tools", buildJsonArray {
                        for (name in names) add(buildJsonObject { put("name", name) })
                    }) }
                    else -> {
                        val params = request.getValue("params").jsonObject
                        val name = params.getValue("name").jsonPrimitive.content
                        val args = params.getValue("arguments").jsonObject
                        val detail = name == "ui_dump" && args["format"]?.jsonPrimitive?.content == "json"
                        if (detail) detailReads.incrementAndGet() else if (name == "ui_dump") compactReads.incrementAndGet()
                        if (name == "screen_capture") {
                            captures.incrementAndGet()
                            screenWait?.let { ms ->
                                screenWait = null
                                val response = buildJsonObject {
                                    put("jsonrpc","2.0"); put("id",request.getValue("id"))
                                    putJsonObject("result") {
                                        put("isError",true); putJsonObject("structuredContent") { putJsonObject("error") {
                                            put("code","RATE_LIMITED"); put("retryAfterMs",ms)
                                        } }
                                    }
                                }
                                conn.respond(200,response.toString().toByteArray(),"application/json"); return@route
                            }
                        }
                        if (name == "ui_set_text") {
                            textSent = args; sends.incrementAndGet(); onInput()
                            if (failInput) { conn.respond(503, headers = mapOf("Retry-After" to "0")); return@route }
                            if (redirect) { conn.respond(307, headers = mapOf("Location" to "/other")); return@route }
                        }
                        val fields = when(name) {
                            "screen_state" -> """{"screenOn":true,"width":1080,"height":2400,"rotation":0}"""
                            "ui_current_window" -> """{"packageName":"$pkg","activityName":"Notes","accessibilityAvailable":true,"windows":[{"id":42,"type":1,"active":true,"focused":true,"packageName":"$pkg","displayId":0,"layer":3,"bounds":{"left":0,"top":32,"right":1080,"bottom":2312}}]}"""
                            "ui_dump" -> if (detail) buildJsonObject {
                                putJsonObject("window") { put("packageName",pkg); put("activityName","Notes") }
                                val target = buildJsonObject {
                                    put("className","android.widget.EditText"); put("id",""); put("text", fullValue); put("desc","")
                                    put("editable",true); put("clickable",false); put("enabled",true); put("visible",true)
                                    putJsonObject("bounds") { put("left",400);put("right",600);put("top",80);put("bottom",120) }
                                }
                                putJsonArray("nodes") { add(target); if (ambiguous) add(target) }
                            }.toString() else """{"snapshotId":"p:${13 + compactReads.get()}","window":{"packageName":"$pkg","activityName":"Notes"}}"""
                            "screen_capture" -> """{"width":540,"height":1200,"mime":"image/jpeg","bytes":42}"""
                            "ui_set_text" -> """{"performed":true}"""
                            else -> "{}"
                        }
                        buildJsonObject {
                            put("structuredContent", Json.parseToJsonElement(fields))
                            put("content",buildJsonArray {
                                add(buildJsonObject { put("type","text"); put("text",if (name == "ui_dump")
                                    "#n1 FrameLayout [0,32][1080,2312]\n#n2  EditText editable focused \"${if(changeAfterDetail && detailReads.get()>0) "[password]" else fieldValue}\" c=(500,100)" else fields) })
                                if (name == "screen_capture" && !omitImage) add(buildJsonObject {
                                    put("type","image"); put("mimeType","image/png"); put("data","AAAA")
                                })
                            })
                        }
                    }
                }
                val payload = buildJsonObject { put("jsonrpc","2.0"); put("result",result); put("id",request.getValue("id")) }
                conn.respond(200,"event: message\ndata: $payload\n\n".toByteArray(),"text/event-stream",mapOf("Mcp-Session-Id" to "session"))
            }
            server.start()
            driver = AutoJsPhoneDriver(File("."),server.port,"T".repeat(43)) {}
        }

        override fun close() { driver.close(); server.stop() }
    }

    @Test fun compactTreePreservesUnicodeFlagsAndPointOnlyLeaf() {
        val row = autoJsNode("""#n17   EditText editable focused "中\n\"文\"" desc="clickable hidden" id=p:id/edit c=(25,48)""")!!
        assertEquals("中\n\"文\"", row.text)
        assertEquals("clickable hidden", row.description)
        assertTrue(row.editable)
        assertFalse(row.clickable)
        assertEquals(listOf(25,48,25,48), listOf(row.left,row.top,row.right,row.bottom))
        assertNull(autoJsNode("""#n2 Button !enabled "OK" c=(1,2)"""))
        assertNull(autoJsNode("""#n9 EditText editable "[password]" c=(3,4)"""))
    }

    @Test fun observationShowsScriptSelectorsForDuplicateLabelsEvenWhenNodeRefsChange() = runBlocking {
        val upper = autoJsNode("""#n7   Button clickable "选择" id=test.notes:id/choose_first c=(80,100)""")!!
        val lower = autoJsNode("""#n9   Button clickable "选择" id=test.notes:id/choose_second c=(80,200)""")!!
        val rows = listOf(upper, lower).map { it.observationJson() }
        assertEquals(listOf("#n7", "#n9"), rows.map { it.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("test.notes:id/choose_first", "test.notes:id/choose_second"),
            rows.map { it.getValue("resourceId").jsonPrimitive.content })
        assertFalse(upper.copy(resource = "").observationJson().containsKey("resourceId"))
        val requested = rows.maxBy { it.getValue("screenBounds").jsonArray[1].jsonPrimitive.int }
        val steps = AutoJsUiScripts.compile("""id(${requested.getValue("resourceId")}).findOne(1500).click();""")
        val fresh = PhoneFrame("test.notes", "window", 400, 600, 0,
            listOf(lower.copy(id = "#n22"), upper.copy(id = "#n23")))
        val clicks = mutableListOf<String>()
        val result = AutoJsUiScripts.execute(steps, read = { fresh }, apply = { _,node,_ -> clicks += node.id })
        assertEquals(AutoJsUiScripts.Result(1,1,0), result)
        assertEquals(listOf("#n22"), clicks)
    }

    @Test fun pairedNodeActionUsesOnlyObservedReferenceAndUnicodeValue() = runBlocking {
        Plugin().use { p ->
            val caps = p.driver.capabilities()
            assertTrue("set_text" in caps); assertFalse("script_run" in caps); assertFalse("shell_exec" in caps)
            val frame = p.driver.observe(false)
            val text = "中文 🐦\n&$"
            val action = PhoneAction(kind="set_text",node="#n2",text=text)
            p.driver.dispatch(frame,action)
            assertEquals(buildJsonObject { put("nodeRef","#n2"); put("snapshotId","p:14"); put("text",text) },p.textSent)
            assertTrue(runCatching { p.driver.dispatch(frame,action) }.isFailure)
            assertEquals(1,p.sends.get())
            assertTrue(p.permissions.all { it == "Bearer " + "T".repeat(43) })
        }
    }

    @Test fun longReceiptGetsOneRealFullFieldAndKeepsTheCheckedSnapshot() = runBlocking {
        Plugin().use { p ->
            val full = "提交次数：1\n选项：白鹭\n留言：「🐦」\n中文 \"ok\" & " + "长".repeat(130)
            p.fullValue = full
            // A real compact line clips *escaped* UTF-16, so the suffix can even end inside an escape.
            p.fieldValue = JsonPrimitive(full).toString().removeSurrounding("\"").take(40) + "..."
            val frame = p.driver.observe(false)
            assertEquals(full, frame.nodes.single { it.id == "#n2" }.text)
            assertEquals(1,p.detailReads.get())
            p.driver.dispatch(frame, PhoneAction("set_text", "#n2", "下一条"))
            assertEquals("p:15", p.textSent?.get("snapshotId")?.jsonPrimitive?.content)
        }
    }

    @Test fun ambiguousOrPrivateDetailsNeverBecomeProducedText() = runBlocking {
        Plugin().use { p ->
            p.fullValue = "a".repeat(39) + "\n拓展"
            p.fieldValue = "a".repeat(39) + "\\..."
            p.ambiguous = true
            assertTrue(runCatching { p.driver.observe(false) }.exceptionOrNull()?.message.orEmpty().contains("uniquely"))
            p.ambiguous = false; p.changeAfterDetail = true; p.detailReads.set(0)
            assertTrue(runCatching { p.driver.observe(false) }.exceptionOrNull()?.message.orEmpty().contains("Private input"))
            assertEquals(0,p.sends.get())
        }
    }

    @Test fun ordinaryEllipsisIsLiteralAndNeedsNoSecondTree() = runBlocking {
        Plugin().use { p ->
            p.fieldValue = "加载中..."
            assertEquals(p.fieldValue, p.driver.observe(false).nodes.single { it.id=="#n2" }.text)
            assertEquals(0,p.detailReads.get())
        }
    }

    @Test fun tokensUseTheAutoJs6ReleaseFormat() {
        assertTrue(validAutoJsToken("a_-".repeat(14) + "0"))
        assertFalse(validAutoJsToken("Bearer " + "x".repeat(43)))
        assertFalse(validAutoJsToken("x".repeat(42) + "="))
        assertFalse(validAutoJsToken(""))
    }

    @Test fun passwordPageNeverRequestsAScreenshotOrReturnsControls() = runBlocking {
        Plugin().use { p ->
            p.fieldValue = "[password]"
            for (pixels in listOf(false, true)) {
                val failure = runCatching { p.driver.observe(pixels) }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("Private input"))
            }
            assertEquals(0, p.captures.get())
        }
    }

    @Test fun changedAppAndUnknownNodeCauseNoAction() = runBlocking {
        Plugin().use { p ->
            val frame = p.driver.observe(false)
            p.pkg = "test.other"
            assertTrue(runCatching { p.driver.dispatch(frame,PhoneAction("set_text","#n2","never")) }.isFailure)
            val next = p.driver.observe(false)
            assertTrue(runCatching { p.driver.dispatch(next,PhoneAction("set_text","#n3","never")) }.isFailure)
            assertEquals(0,p.sends.get())
        }
    }

    @Test fun invalidImageBlockOrTextOnlyScreenshotIsNeverDelivered() = runBlocking {
        Plugin().use { p ->
            assertTrue(runCatching { p.driver.observe(true) }.exceptionOrNull()?.message.orEmpty().contains("format"))
            p.omitImage = true
            assertTrue(runCatching { p.driver.observe(true) }.exceptionOrNull()?.message.orEmpty().contains("typed screenshot"))
        }
    }

    @Test fun failedOrRedirectedPostIsNeverReplayed() = runBlocking {
        Plugin().use { p ->
            p.failInput = true
            assertTrue(runCatching { p.driver.dispatch(p.driver.observe(false),PhoneAction("set_text","#n2","one")) }.isFailure)
            assertEquals(1,p.sends.get())
            p.failInput = false; p.redirect = true
            assertTrue(runCatching { p.driver.dispatch(p.driver.observe(false),PhoneAction("set_text","#n2","two")) }.isFailure)
            assertEquals(2,p.sends.get())
            assertFalse(p.server.requests.any { it.path == "/other" })
        }
    }

    @Test fun adjoiningSessionsAndHandshakeShareTheRollingHttpPace() = runBlocking {
        Plugin().use { p ->
            // Simulate a conversation with immediate tool calls, then a fresh task with the same endpoint.
            repeat(3) { p.driver.observe(false) }
            p.driver.close()
            val next = AutoJsPhoneDriver(File("."), p.server.port, "T".repeat(43)) {}
            try { repeat(2) { next.observe(false) } } finally { next.close() }
            val starts = p.arrivals.map { it.second }
            assertTrue(starts.size > 20)
            starts.forEachIndexed { i,t -> if (i >= 20) assertTrue("more than 20 requests/s", t-starts[i-20] >= 1_000_000_000L) }
            assertEquals(2,p.arrivals.count { it.first == "initialize" })
        }
    }

    @Test fun realRetryAfterAppliesToReadAndHandshakeOnly() = runBlocking {
        Plugin().use { p ->
            p.httpLimit = { op -> if (op in setOf("initialize","ui_dump") && p.arrivals.count { it.first == op } == 1) 1 else null }
            val frame = p.driver.observe(false)
            for (op in listOf("initialize","ui_dump")) {
                val requests = p.arrivals.filter { it.first == op }
                assertEquals(2,requests.size)
                assertTrue(requests[1].second - requests[0].second >= 1_000_000_000L)
            }
            p.httpLimit = { if (it == "ui_set_text") 1 else null }
            val error = runCatching { p.driver.dispatch(frame,PhoneAction("set_text","#n2","once")) }.exceptionOrNull()
            assertTrue(error is PhoneActionNotSent); assertTrue(error?.message.orEmpty().contains("ui_set_text"))
            assertEquals(1,p.arrivals.count { it.first == "ui_set_text" }); assertEquals(0,p.sends.get())
            p.httpLimit = { null }
            val second = p.driver.observe(false)
            p.httpLimit = { if (it == "screen_state") 9 else null }
            assertTrue(runCatching { p.driver.dispatch(second,PhoneAction("set_text","#n2","never")) }.exceptionOrNull() is PhoneActionNotSent)
            assertEquals(0,p.sends.get())
        }
    }

    @Test fun longScreenshotQuotaBlocksCapturesButNotNodeReads() = runBlocking {
        Plugin().use { p ->
            p.screenWait = 10_000
            repeat(2) {
                val error = runCatching { p.driver.observe(true) }.exceptionOrNull()
                assertTrue(error?.message.orEmpty().contains("RATE_LIMITED"))
            }
            assertEquals(1,p.captures.get())
            assertTrue(p.driver.observe(false).nodes.isNotEmpty())
        }
    }

    @Test fun stopDuringReadBackoffDoesNotSendTheRetry() = runBlocking {
        Plugin().use { p ->
            val rejected = CountDownLatch(1)
            p.httpLimit = { if (it == "ui_dump") { rejected.countDown(); 1 } else null }
            val job = async(Dispatchers.IO) { runCatching { p.driver.observe(false) } }
            assertTrue(rejected.await(5,TimeUnit.SECONDS))
            p.driver.close()
            assertTrue(withTimeout(750) { job.await() }.isFailure)
            assertEquals(1,p.arrivals.count { it.first == "ui_dump" })
        }
    }

    @Test fun closeInterruptsActiveHttpPostWithoutASecondDispatch() = runBlocking {
        Plugin().use { p ->
            val arrived = CountDownLatch(1); val release = CountDownLatch(1)
            val frame = p.driver.observe(false)
            p.onInput = { arrived.countDown(); release.await(8,TimeUnit.SECONDS) }
            val job = async(Dispatchers.IO) { runCatching { p.driver.dispatch(frame,PhoneAction("set_text","#n2","once")) } }
            try {
                assertTrue(arrived.await(4,TimeUnit.SECONDS))
                p.driver.close()
                assertTrue(withTimeout(2_000) { job.await() }.isFailure)
                assertTrue(runCatching { p.driver.observe(false) }.isFailure)
                assertEquals(1,p.sends.get())
            } finally { release.countDown() }
        }
    }
}
