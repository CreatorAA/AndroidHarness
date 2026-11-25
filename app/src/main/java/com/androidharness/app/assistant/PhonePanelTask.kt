package com.androidharness.app.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One explicitly granted turn. Only Android's matching window callbacks complete a transition. */
internal class PhonePanelTask(
    val sid: String, val returnToken: String, val hideWindow: () -> Unit, val showWindow: () -> Unit,
    val release: () -> Unit, val loseWindow: () -> Unit,
) {
    private enum class Stage { SHOWN, HIDING, HIDDEN, RETURNING, CLOSED }
    private val stage = MutableStateFlow(Stage.SHOWN)
    val returning get() = stage.value == Stage.RETURNING
    var backgrounded = false
        private set
    private var returnAfterHide = false
    private var finished = false
    private var finishOffscreen = false

    suspend fun awaitHidden(authorized: () -> Boolean): Boolean {
        if (finished || !authorized()) return false
        if (stage.value == Stage.SHOWN) {
            stage.value = Stage.HIDING
            hideWindow()
        }
        val result = withTimeoutOrNull(4_000L) { stage.first { it != Stage.HIDING } }
        if (result == null && stage.value == Stage.HIDING) {
            // A timeout is not evidence the display is clear. Do not leave teardown stuck HIDING.
            stage.value = Stage.SHOWN
            loseWindow()
            if (finished) close()
        }
        return result == Stage.HIDDEN && !finished && authorized()
    }

    fun onHidden(): Boolean {
        when (stage.value) {
            Stage.SHOWN, Stage.RETURNING -> backgrounded = true
            Stage.HIDING -> Unit // the driver requested this transition
            else -> return false
        }
        stage.value = Stage.HIDDEN
        if (finished && (backgrounded || finishOffscreen && !returnAfterHide)) close()
        else if (returnAfterHide) { returnAfterHide = false; requestReturn() }
        return true
    }

    /** An independent summons inspects the same turn. It is not a new device grant. */
    fun inspect() {
        if (stage.value == Stage.CLOSED) return
        backgrounded = false
        returnAfterHide = false
        stage.value = Stage.SHOWN
        if (finished) close()
    }

    fun returnUnavailable() {
        if (!returning) return
        backgrounded = true
        stage.value = Stage.HIDDEN
        if (finished) close()
    }

    fun onShown(token: String?): Boolean {
        if (token != returnToken || !returning) return false
        inspect()
        return true
    }

    fun requestReturn() {
        when (stage.value) {
            Stage.HIDING -> returnAfterHide = true
            Stage.HIDDEN -> { stage.value = Stage.RETURNING; showWindow() }
            else -> Unit
        }
    }

    fun finish(keepTargetVisible: Boolean = false) {
        finished = true
        finishOffscreen = keepTargetVisible
        when (stage.value) {
            Stage.SHOWN -> close()
            Stage.HIDDEN -> if (backgrounded || keepTargetVisible) close() else requestReturn()
            Stage.HIDING -> if (!keepTargetVisible) requestReturn()
            Stage.RETURNING, Stage.CLOSED -> Unit
        }
    }

    fun close() {
        if (stage.value == Stage.CLOSED) return
        stage.value = Stage.CLOSED
        release()
    }
}
