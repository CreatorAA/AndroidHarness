package com.androidharness.app.androiduse

/** What belongs to each real click target. Discovery makes no claim about authorization or what tapping will do. */
internal object PhoneTapTargets {
    data class Target(val tap: PhoneNode, val representative: PhoneNode, val evidence: List<PhoneNode>, val singleAction: Boolean) {
        val label get() = representative.text.ifBlank { representative.description }.trim()
        val composite get() = evidence.any { it.id != tap.id }
    }

    fun from(frame: PhoneFrame): List<Target> {
        val owned = frame.nodes.filter { it.tapOwner != null && (it.text.isNotBlank() || it.description.isNotBlank()) }
            .groupBy { it.tapOwner }
        return frame.nodes.mapNotNull { tap ->
            if (!tap.clickable || tap.tapOwner != tap.id) return@mapNotNull null
            val evidence = owned[tap.id] ?: return@mapNotNull null
            // Prefer the first visible text to an aggregated accessibility description on a wrapper.
            val label = evidence.firstOrNull { it.text.isNotBlank() } ?: evidence.first()
            Target(tap, label, evidence, tap.id in frame.inspectedContainers)
        }
    }

    fun resolve(targets: List<Target>, kind: String?, id: String?) =
        if (kind != "tap" || id == null) null else targets.singleOrNull { it.tap.id == id || it.evidence.any { n -> n.id == id } }

    /** Recycler rows can retain the exact same accessible button while being rebound to different content. */
    fun stillCurrent(before: PhoneFrame, now: PhoneFrame, action: PhoneAction): Boolean {
        val anchor = action.tapEvidenceNode ?: return false
        if (action.kind != "tap" || before.packageName != now.packageName || before.windowKey != now.windowKey ||
            before.nodes != now.nodes) return false
        val old = resolve(from(before), "tap", anchor) ?: return false
        return old.tap.id == action.node && resolve(from(now), "tap", anchor) == old &&
            (action.sourcePoint == null || before.touchMap?.belongs(action.sourcePoint,old.tap.id) == true &&
                now.touchMap?.belongs(action.sourcePoint,old.tap.id) == true)
    }
}
