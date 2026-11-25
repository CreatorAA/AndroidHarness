package com.androidharness.app.androiduse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.androidharness.app.R
import java.time.OffsetDateTime
import java.util.Locale
import kotlinx.serialization.json.*

/** Only a matching handler inside the user-approved app may receive the request. */
internal class PublicPhoneActions(private val context: Context) {
    class Prepared internal constructor(
        val id: String, val targetPackage: String, val preview: String, val isCommit: Boolean,
        internal val intent: Intent,
    )

    fun available(targetPackage: String): JsonArray {
        PhoneScope.requireApp(targetPackage, context.packageName)
        return buildJsonArray {
            val offered = mutableSetOf<String>()
            for (spec in PublicPhoneContract.entries) {
                if (spec.id in offered || resolve(spec, targetPackage) == null) continue
                offered += spec.id
                add(buildJsonObject {
                    put("id", spec.id)
                    put("description", spec.description)
                    put("parameters", buildJsonObject { spec.parameters.forEach { (k, v) -> put(k, v) } })
                })
            }
        }
    }

    /** No app launch or other business operation happens while preparing the confirmation. */
    fun prepare(targetPackage: String, id: String, args: JsonObject): Prepared? {
        PhoneScope.requireApp(targetPackage, context.packageName)
        for (spec in PublicPhoneContract.entries.filter { it.id == id }) {
            val component = resolve(spec, targetPackage) ?: continue
            val request = spec.parse(args)
            val intent = if (request is PublicPhoneContract.Request.Place) {
                mapSearchHandlers(context, request.query, targetPackage).firstOrNull()?.explicitIntent(request.query) ?: continue
            } else spec.intent(request).setPackage(targetPackage).setComponent(component)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return Prepared(id, targetPackage,
                listOf(context.getString(spec.title), targetPackage, request.details)
                    .filter(String::isNotEmpty).joinToString("\n"), spec.commits, intent)
        }
        return null
    }

    /** Sends exactly once. None of these activity contracts returns a receipt for the business operation. */
    fun dispatch(prepared: Prepared): JsonObject {
        context.startActivity(prepared.intent)
        return buildJsonObject {
            put("status", "dispatched_unverified")
            put("id", prepared.id)
            put("packageName", prepared.targetPackage)
            put("message", when (prepared.id) {
                "calendar_draft", "note_draft" -> "Requested the editor. Saving has not been verified."
                "map_search" -> "Sent the location query to the map app. Inspect the page to verify the result."
                else -> "Sent the request to the Clock app. Do not repeat it; creation is not verified."
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun resolve(spec: PublicPhoneContract, pkg: String): ComponentName? {
        if (spec == PublicPhoneContract.MAP) return mapSearchHandlers(context, "", pkg).firstOrNull()?.component
        if (spec == PublicPhoneContract.XIAOMI_NOTE && pkg != "com.miui.notes") return null
        val info = context.packageManager.resolveActivity(
            spec.intent().setPackage(pkg), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo ?: return null
        // Normal resolution already excludes effectively disabled packages/components. ActivityInfo.enabled
        // is the manifest default and can still be false on an alias explicitly enabled at runtime.
        if (info.packageName != pkg || !info.exported) return null
        if (info.permission != null && context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) return null
        return ComponentName(pkg, info.name)
    }
}

/** Android common intents, plus the empty note editor advertised by Xiaomi Notes on HyperOS. */
internal enum class PublicPhoneContract(
    val id: String, val title: Int, val description: String, val parameters: Map<String, String>,
    val commits: Boolean = false,
) {
    ALARM("alarm_set", R.string.phone_public_alarm, "Set a one-time alarm for the next occurrence of a local time.",
        mapOf("hour" to "Required integer 0–23 (local time)", "minute" to "Required integer 0–59",
            "label" to "Optional alarm label (1–200 characters)"), true),
    TIMER("timer_set", R.string.phone_public_timer, "Start a countdown timer.",
        mapOf("seconds" to "Required integer 1–86400", "label" to "Optional timer label (1–200 characters)"), true),
    MAP("map_search", R.string.phone_public_map, "Search for a place in this map app; does not start navigation.",
        mapOf("query" to "Required place name or address (1–500 characters)")),
    CALENDAR("calendar_draft", R.string.phone_public_calendar, "Open an editable calendar event; this does not confirm a save.",
        mapOf("title" to "Required event title (1–300 characters)",
            "begin" to "Optional ISO 8601 start date and time with UTC offset",
            "end" to "Optional ISO 8601 end date and time with UTC offset, after begin",
            "location" to "Optional location (1–500 characters)", "description" to "Optional description (1–4096 characters)")),
    NOTE("note_draft", R.string.phone_public_note, "Request a new editable note.",
        mapOf("text" to "Optional note body (1–4096 characters)")),
    NOTE_EDITOR("note_draft", R.string.phone_public_note, "Open this app's new-note editor; fill the body separately.",
        emptyMap()),
    XIAOMI_NOTE("note_draft", R.string.phone_public_note, "Open the blank Xiaomi Notes editor; fill the body separately.",
        emptyMap());

    sealed interface Request {
        val details: String
        data class Clock(val hour: Int, val minute: Int, val label: String?) : Request {
            override val details = listOfNotNull(String.format(Locale.ROOT, "%02d:%02d", hour, minute), label).joinToString("\n")
        }
        data class Timer(val seconds: Int, val label: String?) : Request {
            override val details = listOfNotNull("${seconds}s", label).joinToString("\n")
        }
        data class Place(val query: String) : Request { override val details = query }
        data class Event(val title: String, val begin: OffsetDateTime?, val end: OffsetDateTime?,
            val location: String?, val description: String?) : Request {
            override val details = listOfNotNull(title, listOfNotNull(begin, end).joinToString(" → ").ifEmpty { null },
                location, description).joinToString("\n")
        }
        data class Note(val text: String?) : Request { override val details = text.orEmpty() }
    }

    fun parse(args: JsonObject): Request {
        require(args.keys.all(parameters::containsKey)) { "Unknown parameter for $id. Use only: ${parameters.keys}." }
        fun text(key: String, max: Int): String? {
            val raw = args[key] ?: return null
            val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(value != null && value.isNotBlank() && value.length <= max) { "$key must be text (1–$max characters)." }
            return value
        }
        fun integer(key: String, range: IntRange): Int {
            val n = (args[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            require(n != null && n in range) { "$key must be an integer in $range." }
            return n
        }
        fun date(key: String): OffsetDateTime? = text(key, 45)?.let {
            requireNotNull(runCatching { OffsetDateTime.parse(it).also { date -> date.toInstant().toEpochMilli() } }.getOrNull()) {
                "$key must be an ISO date and time with an explicit UTC offset."
            }
        }
        return when (this) {
            ALARM -> Request.Clock(integer("hour", 0..23), integer("minute", 0..59), text("label", 200))
            TIMER -> Request.Timer(integer("seconds", 1..86_400), text("label", 200))
            MAP -> Request.Place(requireNotNull(text("query", 500)) { "query is required" })
            CALENDAR -> {
                val begin = date("begin")
                val end = date("end")
                require(end == null || begin != null && end.isAfter(begin)) { "end must follow begin." }
                Request.Event(requireNotNull(text("title", 300)) { "title is required" }, begin, end,
                    text("location", 500), text("description", 4096))
            }
            NOTE, NOTE_EDITOR, XIAOMI_NOTE -> Request.Note(text("text", 4096))
        }
    }

    /** Omitting request produces only the intent filter used in discovery; it is never dispatched. */
    fun intent(request: Request? = null): Intent = when (this) {
        ALARM -> Intent(AlarmClock.ACTION_SET_ALARM).apply {
            (request as Request.Clock?)?.let {
                putExtra(AlarmClock.EXTRA_HOUR, it.hour); putExtra(AlarmClock.EXTRA_MINUTES, it.minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                it.label?.let { label -> putExtra(AlarmClock.EXTRA_MESSAGE, label) }
            }
        }
        TIMER -> Intent(AlarmClock.ACTION_SET_TIMER).apply {
            (request as Request.Timer?)?.let {
                putExtra(AlarmClock.EXTRA_LENGTH, it.seconds); putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                it.label?.let { label -> putExtra(AlarmClock.EXTRA_MESSAGE, label) }
            }
        }
        MAP -> mapIntent(MapHandoffRoute.GEO, (request as Request.Place?)?.query.orEmpty())
        CALENDAR -> Intent(Intent.ACTION_INSERT).apply {
            setDataAndType(CalendarContract.Events.CONTENT_URI, "vnd.android.cursor.dir/event")
            (request as Request.Event?)?.let {
                putExtra(CalendarContract.Events.TITLE, it.title)
                it.begin?.let { t -> putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t.toInstant().toEpochMilli()) }
                it.end?.let { t -> putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t.toInstant().toEpochMilli()) }
                it.location?.let { where -> putExtra(CalendarContract.Events.EVENT_LOCATION, where) }
                it.description?.let { body -> putExtra(CalendarContract.Events.DESCRIPTION, body) }
            }
        }
        NOTE -> Intent("com.google.android.gms.actions.CREATE_NOTE").setType("text/plain").apply {
            (request as Request.Note?)?.text?.let { putExtra("com.google.android.gms.actions.extra.TEXT", it) }
        }
        NOTE_EDITOR -> Intent("android.intent.action.CREATE_NOTE")
        XIAOMI_NOTE -> Intent(Intent.ACTION_INSERT_OR_EDIT).setType("vnd.android.cursor.item/text_note")
    }
}
