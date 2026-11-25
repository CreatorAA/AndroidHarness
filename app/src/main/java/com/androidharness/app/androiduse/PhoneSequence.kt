package com.androidharness.app.androiduse

import com.androidharness.app.tools.Schema
import kotlinx.serialization.json.*

/** A short sequence on one observed screen. The model never supplies coordinates or a new selector. */
internal object PhoneSequence {
    // The same Activity (and e.g. its toolbar) can survive a full page replacement. Compare
    // actionable structure, not readouts or editable values that are supposed to change.
    private data class Control(val type: String, val resource: String, val label: String, val editable: Boolean, val clickable: Boolean)
    private fun layout(frame: PhoneFrame) = frame.nodes.asSequence().filter { it.editable || it.clickable }
        .map { n -> Control(n.viewClass, n.resource, if (n.resource.isNotBlank() || n.editable) ""
            else n.description.ifBlank { n.text }, n.editable, n.clickable) }.groupingBy { it }.eachCount()

    fun matchesPage(original: PhoneFrame, current: PhoneFrame): Boolean =
        current.packageName == original.packageName && current.windowKey == original.windowKey &&
            current.rotation == original.rotation && current.width == original.width && current.height == original.height &&
            layout(current) == layout(original)

    val schema = Schema.obj(mapOf(
        "observationId" to Schema.string("ID of the current screen observation."),
        "steps" to Schema.array(Schema.obj(mapOf(
            "kind" to Schema.string("tap or set_text"),
            "node" to Schema.string("Control ID visible in that same initial observation."),
            "effect" to ManagedPhoneTask.effectSchema,
            "intent" to Schema.string("navigate or submit; prefer a single action for a commitment."),
            "text" to Schema.string("Exact user-requested value for set_text only.")
        ), listOf("kind", "node")), "1–12 ordered actions on the same page; each control is checked before use."),
        "expectedText" to Schema.string("Optional distinctive new text to verify after the last step.")
    ), listOf("observationId", "steps"))

    data class Step(val kind: String, val initial: PhoneNode, val value: String?, val effect: String? = null, val submit: Boolean = false) {
        fun resolve(frame: PhoneFrame): PhoneNode {
            val matches = frame.nodes.filter { n ->
                n.viewClass == initial.viewClass && n.resource == initial.resource && n.description == initial.description &&
                    n.editable == initial.editable && n.clickable == initial.clickable &&
                    (kind == "set_text" && initial.resource.isNotBlank() || n.text == initial.text)
            }
            // Repeated rows may have identical labels and resource IDs. Never guess between them.
            return matches.singleOrNull() ?: error("The control moved to a different page, disappeared, or became ambiguous.")
        }
    }
    fun read(steps: JsonArray, frame: PhoneFrame): List<Step> {
        require(steps.size in 1..12) { "Use 1–12 steps per sequence." }
        return steps.map { item ->
            val args = item.jsonObject
            require(args.keys.all { it in setOf("kind","node","text","intent","effect") })
            val kind = args.getValue("kind").jsonPrimitive.content
            require(kind == "tap" || kind == "set_text") { "Batch only taps and text entry; use a separate action for other operations." }
            val node = frame.nodes.singleOrNull { it.id == args.getValue("node").jsonPrimitive.content }
                ?: error("A step references a control not in the initial observation.")
            val value = args["text"]?.jsonPrimitive?.contentOrNull
            require(if (kind == "tap") node.clickable && value == null else node.editable && !value.isNullOrBlank() && value.length <= PhoneNode.MAX_INPUT_CHARS)
            require(node.resource.isNotBlank() || node.description.isNotBlank() || node.text.isNotBlank()) { "Use an identifiable control." }
            val intent = args["intent"]?.jsonPrimitive?.content
            require(intent == null || intent in setOf("navigate","submit")) { "Unknown intent." }
            Step(kind, node, value, args["effect"]?.jsonPrimitive?.content,intent == "submit").also { it.resolve(frame) }
        }
    }
}
