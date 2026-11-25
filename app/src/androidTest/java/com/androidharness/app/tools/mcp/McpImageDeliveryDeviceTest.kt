package com.androidharness.app.tools.mcp

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ImageStore
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.OpenAiCompatProvider
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderFactory
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.llm.RequestOptions
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class McpImageDeliveryDeviceTest {
    @Test fun genericAdapterPreservesMcpTextAndReturnsPixelsFromImageStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(48,80,Bitmap.Config.ARGB_8888)
        val png = ByteArrayOutputStream().also { stream ->
            try { bitmap.eraseColor(Color.RED); assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)) }
            finally { bitmap.recycle() }
        }.toByteArray()
        val encoded = Base64.getEncoder().encodeToString(png)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val body = Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
            val rpc = Json.parseToJsonElement(body).jsonObject
            val result = when (rpc["method"]?.jsonPrimitive?.content) {
                "tools/list" -> buildJsonObject { putJsonArray("tools") { add(buildJsonObject { put("name","inspect") }) } }
                "tools/call" -> buildJsonObject { putJsonArray("content") {
                    add(buildJsonObject { put("type","text"); put("text","frame 42") })
                    add(buildJsonObject { put("type","image"); put("mimeType","image/png"); put("data",encoded) })
                } }
                else -> JsonObject(emptyMap())
            }
            val id = rpc["id"]
            val document = id?.let { buildJsonObject { put("jsonrpc","2.0"); put("id",it); put("result",result) }.toString() }.orEmpty()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (id==null) 202 else 200)
                .message("OK").header("Content-Type","application/json").body(document.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val conn = McpConnection("fixture",McpServerConfig("fixture",type="http",url="http://127.0.0.1/mcp"),httpClient=client)
        val store = ImageStore(context)
        var saved: String? = null
        try {
            conn.connect(context.cacheDir)
            val adapter = McpToolAdapter("fixture",conn.tools.single(),conn,store) {}
            val ctx = ToolContext(FileFs(context.cacheDir))
            val result = adapter.execute(JsonObject(emptyMap()),ctx)
            assertTrue(result.output, result.ok); assertTrue(result.output.contains("frame 42")); assertFalse(result.output.contains(encoded))
            val ref = requireNotNull(result.image); saved = ref.name
            assertEquals(48,ref.width); assertEquals(80,ref.height)
            val resolved = requireNotNull(store.resolve(ref))
            assertEquals("image/png",resolved.mime); assertArrayEquals(png,Base64.getDecoder().decode(resolved.base64))
            val wire = OpenAiCompatProvider(OkHttpClient(), ProviderFactory.json).buildRequestBody(
                ProviderConfig("fixture", "Fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "gpt-4o"),
                "system", listOf(ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("inspect-1", adapter.name, "{}"))),
                    ChatMessage(Role.TOOL, result.output, toolCallId = "inspect-1", toolName = adapter.name,
                        images = listOf(ref), imageData = listOf(resolved))), emptyList(), RequestOptions())
            val parts = wire.getValue("messages").jsonArray.flatMap { (it.jsonObject["content"] as? JsonArray).orEmpty() }
            assertTrue(parts.any { (it.jsonObject["image_url"] as? JsonObject)?.get("url")?.jsonPrimitive?.content ==
                "data:image/png;base64,$encoded" })
            val noVision = adapter.execute(JsonObject(emptyMap()),ctx.copy(imageInputAllowed=false))
            assertTrue(noVision.ok); assertNull(noVision.image); assertTrue(noVision.output.contains("frame 42"))
            assertTrue(noVision.output.contains("does not accept image"))
        } finally { conn.close(); saved?.let(store::delete) }
    }
}
