package com.androidharness.app.androiduse

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.androidharness.app.HarnessApp
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Each frame belongs to one driver instance and exactly one display. */
interface PhoneDriver {
    val label: String

    suspend fun capabilities(): Set<String>

    suspend fun observe(includeImage: Boolean): PhoneFrame

    /** Refuse unrelated foreground packages before traversing their controls during a launcher trip. */
    suspend fun observeInApps(allowed: Set<String>): PhoneFrame =
        error("This driver does not support scoped launcher observations.")
    suspend fun observeInApps(allowed: Set<String>, includeImage: Boolean): PhoneFrame =
        if (!includeImage) observeInApps(allowed) else error("Scoped screenshots are unavailable.")

    suspend fun dispatch(frame: PhoneFrame, action: PhoneAction): String

    fun close() {}
}

/** The driver refused before handing this action to Android or its companion. */
internal class PhoneActionNotSent(cause: Exception) : Exception(cause.message, cause)
internal class UnexpectedPhoneApp : IllegalStateException("A different app is in front. No UI was read or operated.")

data class PhoneFrame(
    val packageName: String,
    val windowKey: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val nodes: List<PhoneNode>,
    val image: PhoneImage? = null,
    val displayId: Int = 0,
    /** Native paths of clickable containers whose visible children were all inspected. No OCR/flat-dump inference. */
    val inspectedContainers: Set<String> = emptySet(),
    /** Only built when a native screenshot is requested, or to recheck its proposed point. */
    val touchMap: PhoneTouchMap? = null,
)

data class PhonePoint(val x: Int, val y: Int)
data class PhoneBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    fun contains(p: PhonePoint) = p.x in left until right && p.y in top until bottom
}
data class PhoneTouchArea(val path: String, val owner: String?, val independent: Boolean, val bounds: PhoneBox)
data class PhoneTouchMap(val window: PhoneBox, val blocked: List<PhoneBox>, val areas: List<PhoneTouchArea>) {
    fun belongs(p: PhonePoint, tapId: String): Boolean {
        if (!window.contains(p) || blocked.any { it.contains(p) }) return false
        val hits = areas.filter { it.bounds.contains(p) }
        return hits.any { it.path == tapId && it.owner == tapId } && hits.all {
            it.owner == tapId || (!it.independent && tapId.startsWith(it.path + "."))
        }
    }
}

data class PhoneNode(
    val id: String,
    val text: String,
    val description: String,
    val editable: Boolean,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val viewClass: String = "",
    val resource: String = "",
    /** Actual click owner from native accessibility traversal, never guessed from bounds or an ID string. */
    val tapOwner: String? = null,
    val collection: String? = null,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
) {
    // The exported label stays short. This local value is used only to check the designated editor.
    // Longer values cannot equal any supported set_text argument; never compare just their prefix.
    private var editorValue: String? = text
    internal fun readEditorValue(actual: CharSequence?) = apply {
        if (editable && actual != null && actual.length > text.length)
            editorValue = actual.takeIf { it.length <= MAX_INPUT_CHARS }?.toString()
    }
    internal fun hasEditorValue(value: String) = editable && editorValue == value

    internal fun approvalLabel() = text.ifBlank { description }.ifBlank {
        val type = viewClass.substringAfterLast('.').ifBlank { id }
        listOf(type, resource.takeIf(String::isNotBlank), "[$left,$top]–[$right,$bottom]").filterNotNull().joinToString(" · ")
    }
    internal companion object { const val MAX_INPUT_CHARS = 8192 }
}

/** id addresses this observation only; resourceId is an app-provided selector usable on a fresh frame. */
internal fun PhoneNode.observationJson() = buildJsonObject {
    put("id", id)
    if (resource.isNotBlank()) put("resourceId", resource)
    if (text.isNotBlank()) put("text", text)
    if (description.isNotBlank()) put("description", description)
    if (clickable) put("clickable", true)
    if (editable) put("editable", true)
    if (scrollable) put("scrollable", true)
    if (checkable) put("checkable", true)
    putJsonArray("screenBounds") { add(left); add(top); add(right); add(bottom) }
}

data class PhoneImage(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

data class PhoneAction(
    val kind: String,
    val node: String? = null,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val toX: Int? = null,
    val toY: Int? = null,
    val durationMs: Int = 350,
    val packageName: String? = null,
    val tapEvidenceNode: String? = null,
    val sourcePoint: PhonePoint? = null,
)

class PhoneSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("android_use", Context.MODE_PRIVATE)
    // This user decision belongs to this installation, not to a cloud backup or a new phone.
    private val grants = context.getSharedPreferences("phone_control_grants", Context.MODE_PRIVATE)
    var autoManageUserTasks: Boolean
        get() = grants.getBoolean("auto_manage_user_tasks", false)
        set(value) { grants.edit().putBoolean("auto_manage_user_tasks", value).apply() }

    private val secrets
        get() = (context.applicationContext as HarnessApp).container.keys

    var backend: String
        get() = if (prefs.getString("backend", "builtin") == "autojs" && autoJsEnabled) "autojs" else "builtin"
        set(value) {
            require(value in setOf("builtin", "autojs"))
            prefs.edit().putString("backend", value).also { if (value == "autojs") it.putBoolean("autojs_extension", true) }.apply()
        }

    /** New installs keep the built-in service; preserve the previous choice on upgraded devices. */
    var autoJsEnabled: Boolean
        get() = prefs.getBoolean("autojs_extension", prefs.getString("backend", "builtin") == "autojs")
        set(value) { prefs.edit().putBoolean("autojs_extension", value).apply() }

    var port: Int
        get() = prefs.getInt("port", 9637)
        set(value) {
            require(value in 1..65535)
            prefs.edit().putInt("port", value).apply()
        }

    var token: String
        get() = secrets.localSecret("android_use_autojs").orEmpty()
        set(value) {
            if (value.isBlank()) secrets.removeLocalSecret("android_use_autojs")
            else secrets.putLocalSecret("android_use_autojs", value.trim())
        }

    fun isAccessibilityEnabled(): Boolean {
        val component = ComponentName(context, PhoneAccessibilityService::class.java)
        return Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            .orEmpty()
            .split(':')
            .any { ComponentName.unflattenFromString(it) == component }
    }
}
