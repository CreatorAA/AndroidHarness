package com.androidharness.app.androiduse

/** Enforce known operation types. A third-party click has no Android "read-only" guarantee:
 * an inspection can follow observed content/preview owners, but not activate an arbitrary button. */
internal object ManagedPhoneActionPolicy {
    fun screen(task: ManagedPhoneTask, kind: String?, effect: String?, submit: Boolean, node: PhoneNode?, readTarget: Boolean) {
        task.requireLive()
        effect?.let(task::requireEffect)
        val minimum = when (kind) {
            "set_text" -> "edit"
            "share_text", "open_link" -> "share"
            "long_press", "drag", "swipe", "media_volume" -> "change"
            "home" -> "navigate"
            "tap" -> if (node?.checkable == true) "change" else null
            else -> null
        }
        minimum?.let(task::requireEffect)
        if (submit) {
            require(effect != "navigate") { "A submission is not navigation." }
            task.requireEffect(effect ?: minimum ?: "save")
        }
        if (task.limits().access == ManagedPhoneTask.Access.INSPECT && kind == "tap")
            require(readTarget && node?.editable != true && node?.checkable != true) {
                "Inspection can open an observed content row or image, not an opaque control. Use its current node or inspect the visible evidence."
            }
    }

    fun native(task: ManagedPhoneTask, publicId: String?, declared: String?, submit: Boolean) {
        task.requireLive()
        declared?.let(task::requireEffect)
        val operation = when (publicId) {
            "map_search" -> "navigate"
            "calendar_draft", "note_draft" -> "edit"
            "alarm_set", "timer_set" -> "change"
            // Direct Actions have no standardized read-only semantics, regardless of their ID.
            null -> {
                require(task.limits().access == ManagedPhoneTask.Access.OPERATE) { "The app does not certify its Direct Action as read-only." }
                if (task.limits().effects != ManagedPhoneTask.effects)
                    require(declared != null && declared != "navigate") { "This opaque Direct Action needs an authorized effect; use the screen to inspect instead." }
                declared ?: "change"
            }
            else -> error("Unknown public Android contract.")
        }
        task.requireEffect(operation)
        if (submit && operation == "navigate") task.requireEffect(declared?.takeUnless { it == "navigate" } ?: "save")
    }

    fun script(task: ManagedPhoneTask, steps: List<AutoJsUiScripts.Step>) {
        task.requireLive()
        for (step in steps) when (step.op) {
            "setText" -> task.requireEffect("edit")
            // The compiler knows this is a click, not what arbitrary app code will do.
            "click" -> require(task.limits().access == ManagedPhoneTask.Access.OPERATE &&
                task.limits().effects == ManagedPhoneTask.effects) {
                "This script contains an opaque click; use scoped screen actions for a restricted task."
            }
        }
    }
}
