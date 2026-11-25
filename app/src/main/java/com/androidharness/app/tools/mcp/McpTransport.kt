package com.androidharness.app.tools.mcp

import com.androidharness.app.tools.ToolFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The MCP server demands OAuth (HTTP 401 + challenge) and no valid token was available. */
class McpAuthRequiredException(
    /** `resource_metadata` URL from the WWW-Authenticate challenge, when present. */
    val resourceMetadataUrl: String?,
    message: String,
) : Exception(message)

/** The pinned AutoJs6 HTTP middleware rejects this POST before it reaches any tool. */
internal class DeviceHttpRateLimit(val operation: String, val retryMs: Long?) : Exception(
    "AutoJs6 $operation was not dispatched (HTTP 429)." +
        (retryMs?.let { " Retry after $it ms." } ?: " Wait before starting a fresh observation."),
)

/** The plugin counts all POSTs (including handshake) against the same client across sessions. */
private object DeviceHttpPacing {
    private val mutex = Mutex()
    private var lastStart: Long? = null
    suspend fun awaitSlot() = mutex.withLock {
        lastStart?.let {
            val remaining = 60_000_000L - (System.nanoTime() - it) // at most 17 starts in any one second
            if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
        }
        lastStart = System.nanoTime()
    }
    val retryable = setOf("initialize", "notifications/initialized", "tools/list", "device_ping",
        "screen_state", "ui_current_window", "ui_dump", "screen_capture")
    fun operation(message: String): String {
        val body = Json.parseToJsonElement(message) as JsonObject
        val method = (body["method"] as? JsonPrimitive)?.content.orEmpty()
        return if (method == "tools/call") (((body["params"] as? JsonObject)?.get("name")) as? JsonPrimitive)?.content.orEmpty() else method
    }
}

internal object McpProtocol {
    /** Version negotiated for NEW connections; stdio keeps the proven 2024-11-05. */
    const val REMOTE_VERSION = "2025-06-18"
    const val HEADER = "MCP-Protocol-Version"

    /** Extracts `resource_metadata="…"` from a WWW-Authenticate challenge value. */
    fun resourceMetadataFromChallenge(wwwAuthenticate: String?): String? =
        wwwAuthenticate?.let { Regex("resource_metadata=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
}

/**
 * Carries JSON-RPC messages in both directions for one MCP server.
 * Implementations push every raw server message (responses and
 * server-initiated requests/notifications) into [incoming]; [send] writes
 * one message. [start] establishes the connection and may throw
 * [McpAuthRequiredException] for HTTP transports.
 */
internal interface McpTransport {
    val incoming: Channel<String>
    suspend fun start(cwd: File)
    suspend fun send(message: String)
    fun close()
}

// ---------------------------------------------------------------------------
// stdio
// ---------------------------------------------------------------------------

/** Newline-delimited JSON-RPC over the child process's stdin/stdout. */
internal class StdioTransport(
    private val serverName: String,
    private val processFactory: (File) -> Process,
) : McpTransport {

    override val incoming = Channel<String>(Channel.UNLIMITED)
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun start(cwd: File) {
        val proc = try {
            processFactory(cwd)
        } catch (e: Exception) {
            throw ToolFailure(
                "Could not start MCP server '$serverName': ${e.message}. " +
                    "Check the command and that the Linux environment is installed.",
            )
        }
        process = proc
        writer = proc.outputStream.bufferedWriter()
        scope.launch {
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isNotBlank()) incoming.send(line)
                    }
                }
            } catch (_: Exception) {
                // Destroyed or IO error, channel close signals the dead path.
            }
            incoming.close()
        }
    }

    override suspend fun send(message: String) {
        val w = writer ?: throw ToolFailure("MCP server '$serverName' is not running")
        withContext(Dispatchers.IO) {
            w.write(message)
            w.newLine()
            w.flush()
        }
    }

    override fun close() {
        val proc = process
        process = null
        runCatching { proc?.destroy() }
        incoming.close()
    }

    fun processAlive(): Boolean = process?.isAlive == true
}

// ---------------------------------------------------------------------------
// Streamable HTTP (spec 2025-03-26+)
// ---------------------------------------------------------------------------

/**
 * One HTTP POST per JSON-RPC message to the MCP endpoint; responses come
 * back either as a single application/json body or as an SSE stream, both
 * parsed here. The Mcp-Session-Id issued at initialize is replayed on every
 * later request. Server-initiated messages outside a POST response would
 * need the optional GET SSE stream, which the harness does not use.
 */
internal class StreamableHttpTransport(
    private val url: String,
    private val staticHeaders: Map<String, String>,
    private val authHeader: suspend () -> String?,
    private val client: OkHttpClient,
    private val deviceResponseLimit: Long? = null,
    private val recheckDevice: () -> Unit = {},
) : McpTransport {

    override val incoming = Channel<String>(Channel.UNLIMITED)
    private val mediaJson = "application/json".toMediaTypeOrNull()
    @Volatile private var sessionId: String? = null
    private val active = AtomicReference<Call?>()
    private val closed = AtomicBoolean(false)
    private val stopWaiting = CompletableDeferred<Unit>()

    override suspend fun start(cwd: File) {}

    override suspend fun send(message: String) = withContext(Dispatchers.IO) {
        val jsonBody = message.toRequestBody(mediaJson)
        val post = if (deviceResponseLimit == null) jsonBody else object : RequestBody() {
            override fun contentType() = jsonBody.contentType()
            override fun contentLength() = jsonBody.contentLength()
            override fun writeTo(sink: BufferedSink) = jsonBody.writeTo(sink)
            override fun isOneShot() = true
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header(McpProtocol.HEADER, McpProtocol.REMOTE_VERSION)
            .post(post)
            .apply {
                sessionId?.let { header("Mcp-Session-Id", it) }
                authHeader()?.let { header("Authorization", it) }
                staticHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        val op = if (deviceResponseLimit == null) null else DeviceHttpPacing.operation(message)
        val job = currentCoroutineContext().job
        val stop = op?.let { stopWaiting.invokeOnCompletion { job.cancel() } }
        try {
            var retried = false
            while (true) {
                if (op != null) DeviceHttpPacing.awaitSlot()
                currentCoroutineContext().ensureActive()
                val messages = try { exchange(request, op) } catch (limit: DeviceHttpRateLimit) {
                    val ms = limit.retryMs
                    if (retried || op !in DeviceHttpPacing.retryable || ms == null || ms !in 0..2_000) throw limit
                    retried = true
                    delay(ms.coerceAtLeast(60))
                    recheckDevice()
                    continue
                }
                for (line in messages) incoming.send(line)
                break
            }
        } catch (e: java.io.IOException) {
            throw ToolFailure("MCP endpoint unreachable: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            stop?.dispose()
        }
    }

    private suspend fun exchange(request: Request, op: String?): List<String> =
        suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                active.set(call)
                continuation.invokeOnCancellation { call.cancel() }
                if (closed.get()) call.cancel()
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        active.compareAndSet(call, null)
                        continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { readMessages(it, op) } }
                        active.compareAndSet(call, null)
                        continuation.resumeWith(result)
                    }
                })
        }

    private fun readMessages(resp: Response, op: String?): List<String> {
        if (resp.code == 401) throw McpAuthRequiredException(
            McpProtocol.resourceMetadataFromChallenge(resp.header("WWW-Authenticate")),
            "The MCP server requires authorization (HTTP 401).",
        )
        if (resp.code == 429 && op != null) {
            val seconds = resp.header("Retry-After")?.toIntOrNull()?.takeIf { it >= 0 }
            throw DeviceHttpRateLimit(op, seconds?.let { it * 1000L })
        }
        if (!resp.isSuccessful) throw ToolFailure("MCP endpoint returned HTTP ${resp.code} ${resp.message}".trim())
        resp.header("Mcp-Session-Id")?.let { sessionId = it }
        val entity = resp.body ?: return emptyList()
        val body = if (deviceResponseLimit != null) {
            val source = entity.source()
            if (source.request(deviceResponseLimit + 1)) throw ToolFailure("Device MCP response exceeded the size limit.")
            source.readUtf8()
        } else entity.string()
        if (body.isBlank()) return emptyList()
        return if (resp.header("Content-Type").orEmpty().contains("text/event-stream")) sseEvents(body).map { it.second }
        else listOf(body.trim())
    }

    override fun close() {
        closed.set(true)
        stopWaiting.complete(Unit)
        active.getAndSet(null)?.cancel()
        incoming.close()
    }
}

// ---------------------------------------------------------------------------
// HTTP+SSE (the deprecated 2024-11-05 transport, still widely served)
// ---------------------------------------------------------------------------

/**
 * A persistent GET SSE stream delivers server messages; the first `endpoint`
 * event names the URL that client messages are POSTed to.
 */
internal class SseLegacyTransport(
    private val url: String,
    private val staticHeaders: Map<String, String>,
    private val authHeader: suspend () -> String?,
    private val client: OkHttpClient,
) : McpTransport {

    override val incoming = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val endpointReady = kotlinx.coroutines.CompletableDeferred<Unit>()
    @Volatile private var call: okhttp3.Call? = null
    @Volatile private var postUrl: String? = null

    override suspend fun start(cwd: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .apply {
                authHeader()?.let { header("Authorization", it) }
                staticHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        val getCall = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            .newCall(request)
        call = getCall
        val resp = try {
            getCall.execute()
        } catch (e: java.io.IOException) {
            throw ToolFailure("MCP endpoint unreachable: ${e.message ?: e.javaClass.simpleName}")
        }
        if (resp.code == 401) {
            resp.close()
            throw McpAuthRequiredException(
                McpProtocol.resourceMetadataFromChallenge(resp.header("WWW-Authenticate")),
                "The MCP server requires authorization (HTTP 401).",
            )
        }
        if (!resp.isSuccessful) {
            resp.close()
            throw ToolFailure("MCP SSE endpoint returned HTTP ${resp.code} ${resp.message}".trim())
        }
        val response = resp
        scope.launch {
            try {
                response.body?.source()?.use { source ->
                    var event: String? = null
                    val data = StringBuilder()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                val payload = data.toString()
                                val name = event
                                event = null
                                data.setLength(0)
                                if (payload.isNotEmpty()) {
                                    if (name == "endpoint") {
                                        postUrl = resolveUrl(payload.trim())
                                        endpointReady.complete(Unit)
                                    } else {
                                        incoming.send(payload)
                                    }
                                }
                            }
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).trimStart(' '))
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Cancelled or connection dropped, channel close signals it.
            }
            incoming.close()
        }
        // initialize must not race the endpoint announcement.
        withTimeout(15_000) { endpointReady.await() }
    }

    override suspend fun send(message: String) = withContext(Dispatchers.IO) {
        val target = postUrl
            ?: throw ToolFailure("MCP server has not announced its message endpoint yet")
        val request = Request.Builder()
            .url(target)
            .header(McpProtocol.HEADER, McpProtocol.REMOTE_VERSION)
            .post(message.toRequestBody("application/json".toMediaTypeOrNull()))
            .apply {
                authHeader()?.let { header("Authorization", it) }
                staticHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) {
                    throw McpAuthRequiredException(
                        McpProtocol.resourceMetadataFromChallenge(resp.header("WWW-Authenticate")),
                        "The MCP server requires authorization (HTTP 401).",
                    )
                }
                if (!resp.isSuccessful) {
                    throw ToolFailure("MCP endpoint returned HTTP ${resp.code} ${resp.message}".trim())
                }
                // The response, if any, arrives on the SSE stream.
            }
        } catch (e: java.io.IOException) {
            throw ToolFailure("MCP endpoint unreachable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun close() {
        runCatching { call?.cancel() }
        incoming.close()
    }

    private fun resolveUrl(ref: String): String = try {
        java.net.URI(url).resolve(ref).toString()
    } catch (_: Exception) {
        ref
    }
}

/** Parses a complete SSE body into (event, data) pairs, one per blank-line block. */
internal fun sseEvents(body: String): List<Pair<String?, String>> {
    val out = mutableListOf<Pair<String?, String>>()
    var event: String? = null
    val data = StringBuilder()
    fun flush() {
        if (data.isNotEmpty()) out += event to data.toString()
        event = null
        data.setLength(0)
    }
    body.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        when {
            line.isEmpty() -> flush()
            line.startsWith("event:") -> event = line.substring(6).trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).trimStart(' '))
            }
        }
    }
    flush()
    return out
}

/** Shared OkHttp client for remote MCP transports (tools can be slow: 5 min read). */
internal fun mcpHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
