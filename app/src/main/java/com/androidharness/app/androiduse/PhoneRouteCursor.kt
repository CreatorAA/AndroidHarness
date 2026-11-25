package com.androidharness.app.androiduse

import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Owns the current offer. Selecting another source invalidates its action references. */
internal class PhoneRouteCursor {
    enum class Tier(val wire: String) { PUBLIC("public_api"), PAGE("page_action"), SCREEN("screen") }

    data class Offer(val tier: Tier, val target: String, val actions: JsonArray, val time: Long,
                     val id: String = UUID.randomUUID().toString()) {
        fun member(reference: String) = actions.filterIsInstance<JsonObject>().singleOrNull {
            (it[if (tier == Tier.PUBLIC) "id" else "ref"] as? JsonPrimitive)?.contentOrNull == reference
        }
    }

    @Volatile var tier = Tier.PUBLIC
        private set
    @Volatile var offer: Offer? = null
        private set

    fun select(source: Tier) { offer = null; tier = source }
    fun restart() = select(Tier.PUBLIC)

    fun reconcile(target: String, declinedId: String?, reason: String?, now: Long) {
        val old = offer
        if (old != null && (target != old.target || !PhoneSessionPolicy.isFresh(old.id, old.id, old.time, now))) restart()
        if (declinedId == null && reason == null) return
        require(offer != null && offer?.id == declinedId && reason != null && reason.trim().length in 1..500) {
            "Decline only the current route offer using its exact observationId and a reason related to the task."
        }
        advance()
    }

    fun found(target: String, actions: JsonArray, now: Long) {
        check(tier != Tier.SCREEN && offer == null)
        if (actions.isEmpty()) advance() else offer = Offer(tier, target, actions, now)
    }

    fun accepts(offerId: String?, kind: String?, ref: String, now: Long): Boolean {
        val current = offer ?: return false
        return current.tier.wire == kind && PhoneSessionPolicy.isFresh(current.id, offerId, current.time, now) &&
            current.member(ref) != null
    }

    private fun advance() {
        offer = null
        tier = when (tier) { Tier.PUBLIC -> Tier.PAGE; Tier.PAGE -> Tier.SCREEN; Tier.SCREEN -> error("No further route.") }
    }
}
