package com.androidharness.app.androiduse

import kotlinx.serialization.json.*

/** The trusted, current observation's choices for a human-delegated read; not a prediction or a click. */
internal class PhoneReadScope(val kind: String, val candidates: List<PhoneTapTargets.Target>) {
    fun contains(target: PhoneTapTargets.Target) = candidates.any { it.tap.id == target.tap.id }

    fun atDeliveredPoint(frame: PhoneFrame, delivered: Boolean, point: PhonePoint): PhoneTapTargets.Target? =
        if (!delivered) null else candidates.singleOrNull { frame.touchMap?.belongs(point,it.tap.id) == true }

    fun json() = buildJsonObject {
        put("kind", kind)
        putJsonArray("candidates") {
            for (item in candidates) add(buildJsonObject {
                put("tapNode", item.tap.id)
                putJsonArray("evidence") {
                    for (n in item.evidence) add(buildJsonObject {
                        put("node", n.id)
                        if (n.text.isNotBlank()) put("text", n.text)
                        if (n.description.isNotBlank() && n.description != n.text) put("description", n.description)
                    })
                }
            })
        }
    }

    /** Candidate ranking never restricts a valid control; only a missing input needs correction. */
    fun invalidTap(frame: PhoneFrame, step: JsonObject, delivered: Boolean): String? {
        if ((step["kind"] as? JsonPrimitive)?.contentOrNull != "tap") return null
        if (("x" in step || "y" in step) && !delivered) return "current_image_required"
        if ("node" in step && frame.nodes.none { it.id == (step["node"] as? JsonPrimitive)?.contentOrNull }) return "unknown_observed_target"
        return null
    }

    fun retry(observationId: String, reason: String, failedStep: Int? = null) = buildJsonObject {
        put("status","not_dispatched"); put("reason",reason); put("observationId",observationId)
        if (failedStep != null) { put("completedSteps",0); put("failedStep",failedStep) }
        put("message",if (candidates.isEmpty())
            "Nothing was clicked. Re-observe this page; request the current screenshot and/or OCR if the controls are unclear. If you still cannot verify the requested target, state what you could establish. Do not guess a point or request a generic approval."
            else "Nothing was clicked. Match the user's request to the visible evidence below. Call android_action(kind=tap) with the matching tapNode and this observationId, then observe the destination. If none matches, re-observe; do not guess or request approval for an unrelated control.")
        put("readScope", json())
    }.toString()

    companion object {
        // These are candidates by ownership, never a title/subject match or a claim about app code.
        fun from(targets: List<PhoneTapTargets.Target>): PhoneReadScope = PhoneReadScope("inspect_this_source",
            targets.filter { item ->
                item.singleAction && item.composite && !item.tap.editable && !item.tap.checkable && !item.tap.viewClass.endsWith("Button", true) &&
                    item.evidence.none { it.editable || it.checkable }
            })
    }
}

