package com.androidharness.app.tools

import com.androidharness.app.core.ImageRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The screen the user called the assistant over. The assistant keeps it here for its chat, and
 * the model only receives it through [ReadScreenTool] when the question is about that screen.
 *
 * Android can deliver the screen a moment after the user sends a quick first question, so the
 * screen is loaded the first time the model asks for it.
 */
class SharedScreen(private val load: suspend () -> Content?) {
    class Content(
        /** The app, page and visible text, ready for the model. */
        val text: String,
        /** Saves the screenshot for the model; null when there is none. */
        val saveShot: (suspend () -> ImageRef?)?,
        /** Reads the text in the screenshot on the device, for models that cannot see it. Null when off or unavailable. */
        val readShot: (suspend () -> String?)? = null,
    )

    private val lock = Mutex()
    private var loaded = false
    private var content: Content? = null
    private var shotSaved = false
    private var shot: ImageRef? = null
    private var shotRead = false
    private var shotText: String? = null

    suspend fun content(): Content? = lock.withLock {
        if (!loaded) { content = load(); loaded = true }
        content
    }

    suspend fun screenshot(): ImageRef? {
        val saver = content()?.saveShot ?: return null
        return lock.withLock {
            if (!shotSaved) { shot = saver(); shotSaved = true }
            shot
        }
    }

    /** The screenshot's text, read once. Null when reading is off, failed or there is no screenshot. */
    suspend fun screenshotText(): String? {
        val reader = content()?.readShot ?: return null
        return lock.withLock {
            if (!shotRead) { shotText = reader(); shotRead = true }
            shotText
        }
    }
}

/** One assistant panel shares one screen at a time, so only the newest chat keeps one. */
object SharedScreens {
    @Volatile private var current: Pair<String, SharedScreen>? = null

    fun put(sessionId: String, screen: SharedScreen) { current = sessionId to screen }
    fun get(sessionId: String?): SharedScreen? = current?.takeIf { it.first == sessionId }?.second
    fun clear(sessionId: String) { if (current?.first == sessionId) current = null }
}

class ReadScreenTool : Tool {
    override val name = NAME
    override val description = "Read the static screen Android shared when the user invoked the assistant. " +
        "It does not navigate or capture the live app. Use only the evidence channels the user allows."
    override val parametersSchema = Schema.obj(mapOf(
        "image" to Schema.boolean("Read the shared screenshot; false excludes both pixels and OCR. Default true."),
        "text" to Schema.boolean("Read the app's shared structured text; false for screenshot-only requests. Default true."),
    ))
    override val isReadOnly = true

    override fun isAvailable(ctx: ToolContext): Boolean = SharedScreens.get(ctx.sessionId) != null

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val wantImage = args["image"]?.jsonPrimitive?.booleanOrNull != false
        val wantText = args["text"]?.jsonPrimitive?.booleanOrNull != false
        if (!wantImage && !wantText) return ToolResult(false, "Choose the shared text, the screenshot, or both.")
        val shared = SharedScreens.get(ctx.sessionId)
        val content = shared?.content() ?: return ToolResult(false, "Android did not share this screen with the assistant.")
        val text = content.text.takeIf { wantText && it.isNotBlank() }
        val image = if (wantImage && ctx.imageInputAllowed) shared.screenshot() else null
        val read = if (wantImage && image == null) shared.screenshotText() else null
        val note = when {
            !wantImage || image != null -> null
            read != null -> screenshotTextBlock(read)
            else -> "No screenshot or text read from its pixels is available."
        }
        return ToolResult(text != null || image != null || read != null,
            listOfNotNull(text, note).joinToString("\n\n").ifEmpty {
                if (image != null) "Invocation screenshot." else "No structured text was shared."
            }, image = image)
    }

    companion object {
        const val NAME = "read_screen"

        /** The screenshot's text for a model that cannot see it. */
        fun screenshotTextBlock(text: String): String =
            if (text.isBlank()) "[No text was found in the screenshot.]"
            else "[Text read from the screenshot. Layout, pictures and colors are not included.]\n$text"
    }
}
