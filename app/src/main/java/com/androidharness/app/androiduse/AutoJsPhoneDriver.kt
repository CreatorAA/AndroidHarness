package com.androidharness.app.androiduse

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.PowerManager
import com.androidharness.app.tools.ToolFailure
import com.androidharness.app.tools.mcp.DeviceHttpRateLimit
import com.androidharness.app.tools.mcp.McpConnection
import com.androidharness.app.tools.mcp.McpServerConfig
import com.androidharness.app.tools.mcp.decodeMcpRaster
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Restricted client for the paired AutoJs6 1.3.1 MCP plugin on this device. */
class AutoJsPhoneDriver internal constructor(
    private val cwd: File, private val port: Int, private val token: String, private val checkDevice: () -> Unit,
) : PhoneDriver {
    constructor(context: Context, port: Int, token: String) : this(context.filesDir, port, token, {
        if (context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false ||
            context.getSystemService(PowerManager::class.java)?.isInteractive != true)
            throw ToolFailure("Unlock the phone and turn its screen on before using Android Use.")
        if (context.getSystemService(DisplayManager::class.java)?.displays?.singleOrNull()?.displayId != 0)
            throw ToolFailure("Android Use requires one active default display.")
    })

    init {
        require(port in 1..65535) { "Invalid AutoJs6 port." }
        require(validAutoJsToken(token)) { "Paste the 43-character token from AutoJs6, without the Bearer prefix." }
    }

    override val label = "AutoJs6"
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS).build()
    private fun connection() = McpConnection(
        "AutoJs6", McpServerConfig("AutoJs6", type = "http", url = "http://127.0.0.1:$port/mcp"),
        authHeader = { "Bearer $token" }, httpClient = client,
        deviceResponseLimit = 5L * 1024 * 1024, recheckDevice = checkDevice,
    )
    @Volatile private var socket = connection()
    private val serial = Mutex()
    @Volatile private var closed = false
    private val stopped = CompletableDeferred<Unit>()
    private var nextScreenshotNs: Long? = null
    private var advertised: Set<String>? = null
    private var last: PhoneFrame? = null
    private var snapshotId: String? = null

    private suspend fun ready(): Set<String> {
        if (closed) throw ToolFailure("Android Use session stopped.")
        return advertised ?: run {
            try {
                socket.connect(cwd)
                val names = socket.tools.mapTo(mutableSetOf()) { it.name }
                if ("device_ping" !in names) throw ToolFailure("This endpoint did not advertise AutoJs6 device_ping.")
                // Discovery is permitted before pairing. This harmless call requests pairing.
                call("device_ping")
                names.also { advertised = it }
            } catch (e: Exception) {
                socket.close()
                if (!closed) socket = connection() // A subsequent explicit read can establish a fresh session.
                throw e
            }
        }
    }

    private suspend fun call(name: String, args: JsonObject = emptyArgs): JsonObject {
        currentCoroutineContext().ensureActive()
        if (closed) throw ToolFailure("Android Use session stopped.")
        val names = advertised
        if (names != null && name !in names) throw ToolFailure("AutoJs6 tool not enabled: $name")
        if (name == "screen_capture") {
            val remaining = nextScreenshotNs?.let { (it - System.nanoTime() + 999_999L) / 1_000_000L } ?: 0L
            if (remaining > 2_000) throw ToolFailure("AutoJs6 screen_capture: RATE_LIMITED. Retry after $remaining ms with a fresh observation.")
            if (remaining > 0) waitForScreenshot(remaining)
        }
        return readResult(name, args, mayWaitForScreenshot = name == "screen_capture")
    }

    private suspend fun readResult(name: String, args: JsonObject, mayWaitForScreenshot: Boolean): JsonObject {
        val result = socket.callToolResult(name, args, timeoutMs = if (name == "device_ping") 70_000 else 45_000)
        if (result.flag("isError")) {
            val error = (result["structuredContent"] as? JsonObject)?.get("error") as? JsonObject
            val first = result.blocks().firstOrNull { it.str("type") == "text" }?.str("text").orEmpty()
            val code = error?.str("code") ?: first.substringBefore(':').takeIf { it.matches(errorCode) } ?: "FAILED"
            val wait = error?.get("retryAfterMs") ?: (error?.get("details") as? JsonObject)?.get("retryAfterMs")
            if (name == "screen_capture" && code == "RATE_LIMITED") {
                val ms = (wait as? JsonPrimitive)?.longOrNull?.takeIf { it in 0..60_000 }?.coerceAtLeast(60)
                if (ms != null) {
                    nextScreenshotNs = System.nanoTime() + ms * 1_000_000
                    if (mayWaitForScreenshot && ms <= 2_000) {
                        waitForScreenshot(ms)
                        return readResult(name, args, mayWaitForScreenshot = false)
                    }
                }
            }
            val message = when (code) {
                "RATE_LIMITED" -> wait?.let { " Retry after $it ms." } ?: " Wait for the screenshot budget to reset."
                "NODE_REF_STALE", "NODE_NOT_FOUND" -> " The page changed; observe again."
                else -> ""
            }
            throw ToolFailure("AutoJs6 $name: $code.$message")
        }
        return result
    }

    private suspend fun waitForScreenshot(ms: Long) = kotlinx.coroutines.coroutineScope {
        val job = currentCoroutineContext().job
        val closer = stopped.invokeOnCompletion { job.cancel() }
        try {
            delay(ms)
            currentCoroutineContext().ensureActive()
            checkDevice()
        } finally { closer.dispose() }
    }

    override suspend fun capabilities(): Set<String> = serial.withLock {
        val names = ready()
        buildSet {
            if (required.all(names::contains)) { add("observe"); add("nodes") }
            if ("screen_capture" in names) add("image")
            for ((kind, tool) in actions) if (tool in names) add(kind)
        }
    }

    private data class Screen(
        val pkg: String, val activity: String, val width: Int, val height: Int, val rotation: Int,
        val key: String, val window: JsonObject, val windows: List<JsonObject>,
    ) {
        fun checkPoint(x: Int?, y: Int?) {
            if (x == null || y == null || x !in 0 until width || y !in 0 until height ||
                !window.containsPoint(x, y) || windows.any { it.int("layer") > window.int("layer") && it.containsPoint(x, y) })
                throw ToolFailure("Point outside the unobscured target window.")
        }
    }

    private suspend fun screen(): Screen {
        checkDevice()
        val geo = call("screen_state").structured()
        val info = call("ui_current_window").structured()
        val pkg = info.str("packageName").orEmpty()
        val windows = (info["windows"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val active = windows.singleOrNull { it.flag("active") && it.flag("focused") }
        if (pkg.isBlank() || active == null || !info.flag("accessibilityAvailable") || !geo.flag("screenOn") ||
            active.int("type") != 1 || active.str("packageName") != pkg || active.int("id") < 0 ||
            windows.any { it.int("displayId") != 0 } || windows.count { it.int("type") == 1 } != 1)
            throw ToolFailure("AutoJs6 needs one focused application window on the unlocked default display.")
        val w = geo.int("width"); val h = geo.int("height"); val rot = geo.int("rotation")
        if (w <= 0 || h <= 0 || rot !in 0..3) throw ToolFailure("AutoJs6 returned incomplete display geometry.")
        val activity = info.str("activityName").orEmpty()
        val key = windows.sortedBy { it.int("id") }.joinToString("|") {
            listOf(it.int("id"), it.int("type"), it.int("layer"), it.flag("active"),
                it.flag("focused"), it.str("packageName"), it["bounds"]).joinToString(":")
        }
        return Screen(pkg, activity, w, h, rot, "$activity|$key", active, windows)
    }

    private suspend fun dump(format: String, before: Screen): JsonObject {
        val result = call("ui_dump", buildJsonObject { put("format", format); put("maxNodes", 256); put("window", "active") })
        val target = result.structured()["window"] as? JsonObject
        if (target == null || target.str("packageName") != before.pkg || target.str("activityName").orEmpty() != before.activity)
            throw ToolFailure("The target window changed while reading nodes; observe again.")
        return result
    }

    private suspend fun compactDump(before: Screen): Pair<String, String> {
        val result = dump("text", before)
        val snap = result.structured().str("snapshotId")?.takeIf { it.isNotBlank() && it.length <= 80 }
            ?: throw ToolFailure("AutoJs6 did not provide versioned node references.")
        val compact = result.blocks().singleOrNull { it.str("type") == "text" }?.str("text")
            ?: throw ToolFailure("AutoJs6 returned no compact node tree.")
        if (compact.lineSequence().any { it.startsWith("#n") && "\"[password]\"" in it })
            throw ToolFailure("Private input is on screen. Please handle it yourself.")
        return snap to compact
    }

    override suspend fun observe(includeImage: Boolean) = readPage(includeImage, null)
    override suspend fun observeInApps(allowed: Set<String>) = readPage(false, allowed)
    override suspend fun observeInApps(allowed: Set<String>, includeImage: Boolean) = readPage(includeImage, allowed)

    private suspend fun readPage(includeImage: Boolean, allowedPackages: Set<String>?): PhoneFrame = serial.withLock {
        last = null; snapshotId = null
        ready()
        val before = screen()
        if (allowedPackages != null && before.pkg !in allowedPackages) throw UnexpectedPhoneApp()
        val initial = compactDump(before)
        var snap = initial.first
        val rows = initial.second.lineSequence().filter { it.startsWith("#n") }.toList()
        val clipped = rows.mapNotNull { line -> clippedRow(line)?.let { line to it } }.toMap()
        val details = if (clipped.isEmpty()) emptyList() else {
            // Compact fields are limited to 40 escaped characters. Only the JSON format
            // carries full values; the compact format alone supplies actionable snapshot IDs.
            val full = (dump("json", before).structured()["nodes"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?: throw ToolFailure("AutoJs6 returned no full node details.")
            val checked = compactDump(before)
            if (checked.second != initial.second) throw ToolFailure("Node tree changed while reading full text; observe again.")
            snap = checked.first
            full
        }
        val nodes = rows.mapNotNull { line ->
            val node = autoJsNode(line) ?: return@mapNotNull null
            clipped[line]?.complete(node, details) ?: node
        }
        val image = if (includeImage) {
            val captured = autoJsImage(call("screen_capture", buildJsonObject { put("format", "jpeg") }), before.width, before.height)
            maskAutoJsWindow(captured, before.width, before.height, before.window, before.windows)
        } else null
        if (before != screen()) throw ToolFailure("Window or display changed during observation; observe again.")
        PhoneFrame(before.pkg, before.key, before.width, before.height, before.rotation, nodes, image).also {
            last = it; snapshotId = snap
        }
    }

    override suspend fun dispatch(frame: PhoneFrame, action: PhoneAction): String = serial.withLock {
        val (tool, args) = try {
            val snap = snapshotId
            if (closed || frame !== last || snap == null) throw ToolFailure("Observation is no longer current.")
            last = null; snapshotId = null
            val tool = actions[action.kind] ?: throw ToolFailure("Unsupported Android operation.")
            val now = screen()
            if (frame.packageName != now.pkg || frame.windowKey != now.key || frame.width != now.width ||
                frame.height != now.height || frame.rotation != now.rotation || frame.displayId != 0)
                throw ToolFailure("Target window or geometry changed; observe again.")
            val args = buildJsonObject {
                when (action.kind) {
                    "back", "home" -> put("key", action.kind)
                    "launch" -> {
                        val pkg = action.packageName.orEmpty()
                        if (!pkg.matches(packageId)) throw ToolFailure("A valid package name is required.")
                        put("packageName", pkg)
                    }
                    "swipe" -> {
                        now.checkPoint(action.x, action.y); now.checkPoint(action.toX, action.toY)
                        if (action.durationMs !in 1..10_000) throw ToolFailure("Invalid swipe duration.")
                        put("x1", action.x!!); put("y1", action.y!!); put("x2", action.toX!!); put("y2", action.toY!!)
                        put("durationMs", action.durationMs)
                    }
                    else -> {
                        if (action.node != null) {
                            val node = frame.nodes.singleOrNull { it.id == action.node }
                                ?: throw ToolFailure("Node is not in this observation.")
                            put("nodeRef", node.id); put("snapshotId", snap)
                            if (action.kind == "set_text") {
                                val value = action.text ?: throw ToolFailure("Missing text.")
                                if (!node.editable || value.toByteArray().size > 16_384)
                                    throw ToolFailure("Target must be editable and text must fit 16 KiB.")
                                put("text", value)
                            }
                        } else {
                            if (action.kind == "set_text") throw ToolFailure("Select an editable node.")
                            now.checkPoint(action.x, action.y)
                            put("x", action.x!!); put("y", action.y!!)
                        }
                    }
                }
            }
            checkDevice()
            tool to args
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { throw PhoneActionNotSent(failure) }
        val receipt = try { call(tool, args).structured() } catch (rejected: DeviceHttpRateLimit) {
            // Official HTTP limiter is ahead of the tool route, unlike a lost reply after send.
            throw PhoneActionNotSent(rejected)
        }
        if (!receipt.flag(if (action.kind == "launch") "opened" else "performed"))
            throw ToolFailure("AutoJs6 did not acknowledge the action. Observe before taking another action.")
        "AutoJs6 acknowledged the action; verify with a new observation."
    }

    override fun close() { closed = true; stopped.complete(Unit); socket.close() }

    private companion object {
        val emptyArgs = JsonObject(emptyMap())
        val required = setOf("screen_state", "ui_current_window", "ui_dump")
        val actions = mapOf("tap" to "ui_click", "long_press" to "ui_long_click", "set_text" to "ui_set_text",
            "swipe" to "ui_swipe", "back" to "ui_press_key", "home" to "ui_press_key", "launch" to "app_launch")
        val errorCode = Regex("[A-Z][A-Z0-9_]{0,63}")
        val packageId = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+|android")
    }
}

internal fun autoJsImage(result: JsonObject, sourceWidth: Int, sourceHeight: Int): PhoneImage {
    val metadata = result.structured()
    val block = result.blocks().singleOrNull { it.str("type") == "image" }
        ?: throw ToolFailure("AutoJs6 returned no typed screenshot.")
    val width = metadata.int("width"); val height = metadata.int("height")
    if (block.str("mimeType") != "image/jpeg" || metadata.str("mime") != "image/jpeg" ||
        width !in 1..1568 || height !in 1..1568 ||
        abs(width.toLong()*sourceHeight - height.toLong()*sourceWidth) > maxOf(sourceWidth, sourceHeight)*2L)
        throw ToolFailure("Unexpected screenshot format, budget or geometry.")
    val image = decodeMcpRaster(block, maxEdge = 1568)
    if (image.width != width || image.height != height || image.bytes.size != metadata.int("bytes"))
        throw ToolFailure("Screenshot payload and metadata disagree.")
    return PhoneImage(image.bytes, image.mime, width, height)
}

/** The companion captures a display, not just the approved app. Keep preview coordinates unchanged. */
internal fun maskAutoJsWindow(image: PhoneImage, width: Int, height: Int, window: JsonObject, windows: List<JsonObject>): PhoneImage? {
    fun bounds(w: JsonObject): Rect? {
        val b = w["bounds"] as? JsonObject ?: return null
        fun at(k: String) = (b[k] as? JsonPrimitive)?.intOrNull
        return Rect(at("left") ?: return null, at("top") ?: return null, at("right") ?: return null, at("bottom") ?: return null)
    }
    val layer = (window["layer"] as? JsonPrimitive)?.intOrNull ?: return null
    val target = bounds(window)?.takeUnless { it.isEmpty } ?: return null
    val covers = windows.mapNotNull {
        if (it == window) null else {
            val otherLayer = (it["layer"] as? JsonPrimitive)?.intOrNull ?: return null
            if (otherLayer >= layer) bounds(it) ?: return null else null
        }
    }
    val src = BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size) ?: return null
    try {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            canvas.drawColor(Color.BLACK)
            canvas.scale(image.width.toFloat() / width, image.height.toFloat() / height)
            canvas.clipRect(target)
            covers.forEach { canvas.clipOutRect(it) }
            canvas.drawBitmap(src, null, Rect(0, 0, width, height), null)
            val bytes = ByteArrayOutputStream()
            check(out.compress(Bitmap.CompressFormat.JPEG, 85, bytes))
            return PhoneImage(bytes.toByteArray(), "image/jpeg", image.width, image.height)
        } finally { out.recycle() }
    } finally { src.recycle() }
}

private fun JsonObject.str(k: String) = (get(k) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.int(k: String) = (get(k) as? JsonPrimitive)?.intOrNull ?: -1
private fun JsonObject.flag(k: String) = (get(k) as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.structured() = get("structuredContent") as? JsonObject ?: throw ToolFailure("AutoJs6 returned no structured result.")
private fun JsonObject.blocks() = (get("content") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
private fun JsonObject.containsPoint(x: Int, y: Int): Boolean {
    val b = get("bounds") as? JsonObject ?: throw ToolFailure("Missing window bounds.")
    return x >= b.int("left") && x < b.int("right") && y >= b.int("top") && y < b.int("bottom")
}

// The compact wire format has versioned node refs; its JSON tree does not. A leaf reports only its centre.
private val compactLine = Regex("""^(#n[1-9]\d{0,3}) +(\S+)(.*?) (?:\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]|c=\((-?\d+),(-?\d+)\))$""")
private val quoted = Regex("""(?:^| )(?:(desc)=)?("(?:\\.|[^"\\])*")""")
/** Full labels belong to the same visible node, never to an index from a different traversal. */
private data class ClippedRow(val className: String, val resource: String, val text: String, val desc: String) {
    fun complete(row: PhoneNode, candidates: List<JsonObject>): PhoneNode {
        val match = candidates.singleOrNull { candidate ->
            val b = candidate["bounds"] as? JsonObject ?: return@singleOrNull false
            val left = b.int("left"); val top = b.int("top"); val right = b.int("right"); val bottom = b.int("bottom")
            val samePosition = if (row.left == row.right && row.top == row.bottom)
                (left + right) / 2 == row.left && (top + bottom) / 2 == row.top
            else left == row.left && top == row.top && right == row.right && bottom == row.bottom
            samePosition && candidate.str("className")?.substringAfterLast('.') == className &&
                candidate.str("id").orEmpty() == resource && candidate.flag("editable") == row.editable &&
                candidate.flag("clickable") == row.clickable && candidate.flag("enabled") && candidate.flag("visible") &&
                compactLabel(candidate.str("text").orEmpty()) == text && compactLabel(candidate.str("desc").orEmpty()) == desc
        } ?: throw ToolFailure("AutoJs6 could not uniquely match the clipped field to its node; observe again.")
        return row.copy(text = match.str("text").orEmpty(), description = match.str("desc").orEmpty())
    }
}

private val compactId = Regex("""(?:^| )id=(\S+)$""")
private fun clippedRow(line: String): ClippedRow? {
    if (!line.contains("...\"")) return null
    val match = compactLine.matchEntire(line) ?: return null
    val fields = match.groupValues[3].trim()
    val labels = quoted.findAll(fields).associate { it.groupValues[1] to it.groupValues[2].removeSurrounding("\"") }
    if (labels.values.none { it.length == 43 && it.endsWith("...") }) return null
    val id = compactId.find(quoted.replace(fields, ""))?.groupValues?.get(1).orEmpty()
    return ClippedRow(match.groupValues[2], id, labels[""].orEmpty(), labels["desc"].orEmpty())
}

// Wire comparison must clip after escaping (a line break consumes two characters).
private fun compactLabel(value: String): String {
    val encoded = buildString {
        for (ch in value.take(128)) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(if (ch.isISOControl()) ' ' else ch)
            }
            if (length > 40) break
        }
    }
    return if (encoded.length <= 40) encoded else encoded.take(40) + "..."
}

internal fun autoJsNode(line: String): PhoneNode? {
    val groups = compactLine.matchEntire(line)?.groupValues ?: return null
    val fields = groups[3].trim()
    val flags = fields.split(' ').takeWhile { it in nodeFlags }
    if ("hidden" in flags || "!enabled" in flags) return null
    var text = ""; var desc = ""
    for (value in quoted.findAll(fields)) {
        val string = runCatching { Json.parseToJsonElement(value.groupValues[2]).jsonPrimitive.content }.getOrNull() ?: continue
        if (value.groupValues[1] == "desc") desc = string else text = string
    }
    // AutoJs6 CompactNodeText emits this literal for a password node, without its value.
    if (text == "[password]") return null
    val leaf = groups[4].isEmpty()
    val left = groups[if (leaf) 8 else 4].toIntOrNull() ?: return null
    val top = groups[if (leaf) 9 else 5].toIntOrNull() ?: return null
    return PhoneNode(groups[1], text, desc, "editable" in flags, "clickable" in flags,
        left, top, if (leaf) left else groups[6].toIntOrNull() ?: return null,
        if (leaf) top else groups[7].toIntOrNull() ?: return null,
        groups[2], compactId.find(quoted.replace(fields, ""))?.groupValues?.get(1).orEmpty(),
        scrollable = "scrollable" in flags, checkable = "checkable" in flags)
}
private val nodeFlags = setOf("clickable","long_clickable","scrollable","checkable","checked","editable","focused","selected","!enabled","hidden")

// AutoJs6 BearerTokens: 32 random bytes encoded as unpadded base64url.
private val tokenFormat = Regex("[A-Za-z0-9_-]{43}")
internal fun validAutoJsToken(token: String) = tokenFormat.matches(token)
