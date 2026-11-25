package com.androidharness.app.androiduse

/** An unconfirmed commit is never replayed. Only acknowledged screen input has an observable successor. */
internal class PhoneSubmissionGuard {
    class Screen(
        val before: PhoneFrame, val backend: String, val action: PhoneAction,
        val expectedText: String?, val expectedPackage: String?,
        val ordinaryStepPossible: Boolean, val delivered: Boolean,
    ) {
        fun reached(after: PhoneFrame, driver: String) = driver == backend &&
            PhoneSessionPolicy.verifies(before, after, action, expectedText, expectedPackage, before.packageName, true)

        private fun target(frame: PhoneFrame, id: String?): Pair<PhoneNode,String>? {
            val node = frame.nodes.firstOrNull { it.id == id } ?: return null
            val item = PhoneTapTargets.resolve(PhoneTapTargets.from(frame),"tap",id)
            if (item != null) return item.tap to item.label
            val label = node.text.ifBlank { node.description }.trim()
            return (node to label).takeIf { node.clickable && !node.editable && label.isNotEmpty() }
        }

        /** Consider the old and proposed targets, not just unrelated changes elsewhere. If a
         * stable button changed only its caption, require independent progress on the page or an
         * explicit reading of what changed before using that same slot for an ordinary input. */
        private fun sourceControl(): Pair<PhoneNode,String>? {
            val oldId = action.node ?: if (action.x != null && action.y != null)
                PhoneSessionPolicy.hitAt(before,action.x,action.y)?.id else null
            return target(before,oldId)
        }

        private fun distinctControl(oldTarget: Pair<PhoneNode,String>, after: PhoneFrame, nextNode: String?, progress: String?): Boolean {
            val (old,oldLabel) = oldTarget
            val (next,nextLabel) = target(after,nextNode) ?: return false
            fun labels(frame: PhoneFrame) = frame.nodes.flatMap { listOf(it.text.trim(),it.description.trim()) }
                .filter(String::isNotEmpty).toSet()
            val oldLabels = labels(before); val newLabels = labels(after)
            if (nextLabel == oldLabel && progress != null) {
                // Consecutive steps can reuse even the "Next" button. Ask for the actual new
                // non-control content, not a claim that a clock tick or a button changed colour.
                fun body(f: PhoneFrame) = f.nodes.filter { !it.clickable && !it.editable && !it.checkable && it.tapOwner == null }
                    .flatMap { listOf(it.text.trim(),it.description.trim()) }.filter(String::isNotEmpty).toSet()
                val oldBody = body(before); val newBody = body(after)
                return (oldBody - newLabels).isNotEmpty() && (newBody - oldLabels).any { progress.contains(it) }
            }
            if (oldLabel in newLabels || nextLabel in oldLabels) return false
            if (old.resource.isNotBlank() && next.resource.isNotBlank() && old.resource != next.resource) return true
            val independentRemoval = (oldLabels - newLabels - oldLabel).isNotEmpty()
            val independentAddition = (newLabels - oldLabels - nextLabel).isNotEmpty()
            return independentRemoval && independentAddition || progress != null
        }

        fun allowsNext(after: PhoneFrame, driver: String, deliveredNow: Boolean, effect: String?,
                       observedProgress: String?, nextNode: String?): Boolean {
            if (!ordinaryStepPossible || driver != backend || after.packageName != before.packageName ||
                after.displayId != before.displayId || after.width != before.width || after.height != before.height ||
                after.rotation != before.rotation) return false
            val interpreted = effect == "change" && !observedProgress.isNullOrBlank()
            // If the source had an identifiable control, a timestamp or animation elsewhere in an
            // image cannot override it: the proposed target itself must be different.
            sourceControl()?.let { return distinctControl(it,after,nextNode,observedProgress.takeIf { interpreted }) }
            // Pixels cannot prove a business commit. Only a different ordinary input, justified by
            // the model from two delivered scenes, can use this path on an opaque interaction surface.
            return interpreted && delivered && deliveredNow && before.windowKey == after.windowKey &&
                before.image != null && after.image != null && !before.image.bytes.contentEquals(after.image.bytes)
        }
    }

    private data class Pending(val id: String, val screen: Screen? = null)
    @Volatile private var pending: Pending? = null

    val mayContinueFromScreen get() = pending?.screen?.ordinaryStepPossible == true

    val readOnlyReason: String?
        get() = pending?.let {
            val next = if (it.screen?.ordinaryStepPossible == true)
                "Observe route=screen next. Continue with new controls. If the same control now serves a different step, use effect=change and quote its new page text in observedProgress. For an opaque screen inspect fresh pixels (image=true) and describe the visible progress instead."
            else if (it.screen?.expectedText != null)
                "Observe route=screen to check the original expected result; it can arrive later."
            else "Observe the outcome. Do not send another action while this submission is unconfirmed."
            "Submission ${it.id} may already have taken effect. Do not repeat it. $next"
        }

    /** Called only with a fresh observation from the owning task, never with an assistant's assertion. */
    fun observe(frame: PhoneFrame, backend: String): String? {
        val old = pending ?: return null
        if (old.screen?.reached(frame,backend) != true) return null
        verified(old.id)
        return old.id
    }

    fun continuation(frame: PhoneFrame, backend: String, delivered: Boolean, effect: String?, progress: String?, nextNode: String? = null) =
        pending?.takeIf { it.screen?.allowsNext(frame,backend,delivered,effect,progress,nextNode) == true }?.id

    // The session serializes dispatch/observation. Approval previews may read the state concurrently.
    suspend fun dispatch(actionId: String, submit: Boolean, screen: Screen? = null, continuation: String? = null, send: suspend () -> Unit) {
        val old = pending
        if (old != null) check(old.id == continuation) { readOnlyReason.orEmpty() }
        if (submit) pending = Pending(actionId) // mark before handing off; a lost reply is not a successful handoff
        try {
            send()
            pending = if (submit) Pending(actionId, screen) else null
        } catch (e: PhoneActionNotSent) {
            pending = old
            throw e
        }
    }

    fun verified(actionId: String) {
        if (pending?.id == actionId) pending = null
    }
}
