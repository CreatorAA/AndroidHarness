package com.androidharness.app.assistant.edge

import kotlin.math.abs

/** A right-edge drag only commits on release, and must be traveling inward. */
internal class AssistantEdgeSwipe(private val distance: Float, private val dragSlop: Float = distance / 4) {
    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    var repositioning = false
        private set

    fun start(x: Float, y: Float) {
        startX = x
        startY = y
        tracking = true
        repositioning = false
    }

    /** Lock vertical drags to placement until release; crossing left later must not open the panel. */
    fun verticalOffset(x: Float, y: Float): Float? {
        if (!tracking) return null
        val dy = y - startY
        if (abs(dy) >= dragSlop && abs(dy) > 1.25f * abs(x - startX)) repositioning = true
        return if (repositioning) dy else null
    }

    fun finish(x: Float, y: Float): Boolean {
        if (!tracking) return false
        tracking = false
        val inward = startX - x
        return !repositioning && inward >= distance && inward >= 1.25f * abs(y - startY)
    }

    fun cancel() {
        tracking = false
        repositioning = false
    }
}
