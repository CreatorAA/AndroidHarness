package com.androidharness.app.tools.mcp

import com.androidharness.app.tools.ToolFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch

class McpModelsTest {

    @Test
    fun `config parser reads the standard mcpServers format`() {
        val text = """
            {"mcpServers": {
                "fs": {"command": "npx", "args": ["-y", "server-fs", "/tmp"], "env": {"ROOT": "/a"}},
                "off": {"command": "slow", "disabled": true}
            }}
        """.trimIndent()
        val configs = McpConfigParser.parse(text)
        assertEquals(2, configs.size)
        val fs = configs.first { it.name == "fs" }
        assertEquals("npx", fs.command)
        assertEquals(listOf("-y", "server-fs", "/tmp"), fs.args)
        assertEquals(mapOf("ROOT" to "/a"), fs.env)
        assertTrue(fs.enabled)
        val off = configs.first { it.name == "off" }
        assertTrue(!off.enabled)
    }

    @Test
    fun `config parser survives garbage and non-command entries`() {
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parse("not json"))
        assertEquals(emptyList<McpServerConfig>(), McpConfigParser.parse("{}"))
        assertEquals(
            emptyList<McpServerConfig>(),
            McpConfigParser.parse("""{"mcpServers": {"bad": {"args": ["no command"]}}}"""),
        )
    }

    @Test
    fun `tool names are sanitized to the mcp__server__tool shape`() {
        assertEquals(
            "mcp__github_tools__create_issue",
            McpNames.toolName("GitHub Tools!", "Create Issue?"),
        )
        assertEquals("mcp__x__x", McpNames.toolName("---", "***"))
        // Length caps keep provider-visible names bounded: 5 + 32 + 2 + 64.
        assertEquals(103, McpNames.toolName("s".repeat(100), "t".repeat(200)).length)
    }

    @Test
    fun `schemas are normalized to an object root`() {
        val empty = normalizeMcpSchema(buildJsonObject {})
        assertEquals("object", empty["type"]?.let { (it as JsonPrimitive).content })

        val noType = buildJsonObject { put("properties", buildJsonObject {}) }
        assertEquals("object", normalizeMcpSchema(noType)["type"]?.let { (it as JsonPrimitive).content })

        val withType = buildJsonObject { put("type", "object") }
        assertEquals(withType, normalizeMcpSchema(withType))
    }
}

class McpConnectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Newline-delimited JSON over actual streams, without requiring a shell on the host. */
    private class FakeServer : Process() {
        private val exit = CountDownLatch(1)
        private val output = PipedInputStream()
        private val replies = PipedOutputStream(output)
        val waiting = CompletableDeferred<Unit>()
        private val input = object : ByteArrayOutputStream() {
            override fun flush() {
                if (!isAlive) throw IOException("Server closed")
                val request = Json.parseToJsonElement(toString("UTF-8").trim()).jsonObject
                reset()
                val id = request["id"] ?: return
                val result = when (request["method"]?.jsonPrimitive?.content) {
                    "initialize" -> "{}"
                    "tools/list" -> """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                    "tools/call" -> {
                        if (request["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "wait") {
                            waiting.complete(Unit)
                            return
                        }
                        """{"content":[{"type":"text","text":"hello from fake"}]}"""
                    }
                    else -> error("Unexpected request: $request")
                }
                replies.write(("""{"jsonrpc":"2.0","id":$id,"result":$result}""" + "\n").toByteArray())
                replies.flush()
            }
        }
        override fun getInputStream() = output
        override fun getOutputStream(): ByteArrayOutputStream = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun isAlive() = exit.count != 0L
        override fun waitFor(): Int { exit.await(); return 0 }
        override fun exitValue(): Int = if (isAlive) throw IllegalThreadStateException() else 0
        override fun destroy() { replies.close(); exit.countDown() }
    }

    private fun connection(server: FakeServer) = McpConnection("fake", McpServerConfig("fake", "fixture"),
        processFactory = { server }, handshakeTimeoutMs = 5_000)

    @Test fun `handshake discovery and call work over stdio`() = runBlocking {
        val conn = connection(FakeServer())
        try {
            conn.connect(tmp.root)
            assertEquals(listOf("echo"), conn.tools.map { it.name })
            val (text, isError) = conn.callTool("echo", buildJsonObject { put("text", "hi") })
            assertEquals("hello from fake", text)
            assertTrue(!isError)
        } finally { conn.close() }
    }

    @Test fun `a server that dies immediately fails the handshake with ToolFailure`() = runBlocking {
        val conn = connection(FakeServer().also { it.destroy() })
        try {
            conn.connect(tmp.root)
            error("Expected the handshake to fail")
        } catch (expected: ToolFailure) {
            assertTrue(expected.message.orEmpty().contains("fake"))
        } finally { conn.close() }
    }

    @Test fun `closing a connection or losing stdout fails the waiting tool not the caller`() = runBlocking {
        for (disconnectInSettings in listOf(true, false)) {
            val server = FakeServer()
            val conn = connection(server)
            try {
                conn.connect(tmp.root)
                val call = async { runCatching { conn.callTool("wait", buildJsonObject {}) }.exceptionOrNull() }
                withTimeout(5_000) {
                    server.waiting.await()
                    if (disconnectInSettings) conn.close() else server.destroy()
                    assertTrue(call.await() is ToolFailure)
                }
                assertEquals(ConnectionState.DEAD, conn.state)
            } finally { conn.close() }
        }
    }

    @Test fun `cancelling a caller leaves the stdio connection usable`() = runBlocking {
        val server = FakeServer()
        val conn = connection(server)
        try {
            conn.connect(tmp.root)
            val observed = CompletableDeferred<Throwable?>()
            val call = async { observed.complete(runCatching { conn.callTool("wait", buildJsonObject {}) }.exceptionOrNull()) }
            withTimeout(5_000) { server.waiting.await() }
            call.cancelAndJoin()
            assertTrue(observed.await() is CancellationException)
            assertEquals("hello from fake", conn.callTool("echo", buildJsonObject {}).first)
        } finally { conn.close() }
    }
}
