package com.androidharness.app.phoneevents

import kotlinx.serialization.Serializable

@Serializable
internal enum class PhoneEventKind { NOTIFICATION, FOREGROUND }

@Serializable
internal data class EventRule(val app: String, val kind: PhoneEventKind, val taskId: String? = null) {
    val id get() = kind.name + ":" + app
}

@Serializable
internal data class EventReminder(val ruleId: String, val time: Long)

/** Only explicit rules and one unresolved reminder per rule survive a process restart. */
@Serializable
internal data class EventPolicy(
    val enabled: Boolean = false,
    val rules: List<EventRule> = emptyList(),
    val pending: List<EventReminder> = emptyList(),
) {
    fun setRule(rule: EventRule): EventPolicy =
        copy(rules = rules.filterNot { it.id == rule.id } + rule, pending = pending.filterNot { it.ruleId == rule.id })

    fun remove(id: String): EventPolicy =
        copy(rules = rules.filterNot { it.id == id }, pending = pending.filterNot { it.ruleId == id })
    fun accept(app: String, kind: PhoneEventKind, now: Long): EventPolicy {
        if (!enabled) return this
        val rule = rules.find { it.app == app && it.kind == kind } ?: return this
        if (pending.any { it.ruleId == rule.id }) return this
        return copy(pending = pending + EventReminder(rule.id, now))
    }
}

/** A posted notification can be updated many times. Updates aren't new events. */
internal class EventEdges {
    private var foreground: String? = null
    private val posted = mutableSetOf<String>()
    fun enter(app: String): Boolean {
        if (app == foreground) return false
        foreground = app
        return true
    }
    fun posted(key: String) = posted.add(key)
    fun removed(key: String) { posted.remove(key) }
    fun resetNotifications() = posted.clear()
    fun resetForeground() { foreground = null }
}
