package com.androidharness.app.androiduse

/** Observed image controls that might open a better source. They are hints, not evidence of a viewer. */
internal object PhoneImagePreviews {
    data class Preview(val imageNode: String, val tapNode: String)

    fun potential(role: String, width: Int, height: Int, displayWidth: Int, displayHeight: Int): Boolean {
        val side = minOf(displayWidth, displayHeight)
        return (role.endsWith("ImageView") || role == "Image") && side > 0 &&
            minOf(width, height) * 6 >= side && width.toLong() * height < displayWidth.toLong() * displayHeight / 2
    }

    fun from(frame: PhoneFrame): List<Preview> {
        val byId = frame.nodes.associateBy { it.id }
        return frame.nodes.mapNotNull { n ->
            if (n.editable || !potential(n.viewClass, n.right - n.left, n.bottom - n.top, frame.width, frame.height)) return@mapNotNull null
            // Flat drivers can prove that this node itself is clickable. Do not manufacture a parent from geometry.
            val owner = (n.tapOwner?.let(byId::get) ?: n.takeIf { it.clickable })?.takeIf { it.clickable && !it.editable }
                ?: return@mapNotNull null
            if (owner.left > n.left || owner.right < n.right || owner.top > n.top || owner.bottom < n.bottom ||
                (owner.right - owner.left).toLong() * (owner.bottom - owner.top) >
                (n.right - n.left).toLong() * (n.bottom - n.top) * 2) return@mapNotNull null
            Preview(n.id, owner.id)
        }.groupBy { it.tapNode }.mapNotNull { (_, previews) -> previews.singleOrNull() }
    }
}
