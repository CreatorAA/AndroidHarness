package com.androidharness.app.phoneevents

import org.junit.Assert.*
import org.junit.Test

class PhoneEventPolicyTest {
    private val note = EventRule("com.example.one", PhoneEventKind.NOTIFICATION, "task1")
    private val opened = EventRule("com.example.one", PhoneEventKind.FOREGROUND)
    @Test fun disabledOrUnselectedEventsDoNotCreateReminders() {
        val initial = EventPolicy().setRule(note)
        assertSame(initial, initial.accept(note.app, note.kind, 20))
        val enabled = initial.copy(enabled=true)
        assertSame(enabled, enabled.accept("com.example.two",note.kind,20))
        assertSame(enabled, enabled.accept(note.app,opened.kind,20))
    }

    @Test fun oneOutstandingReminderPerRuleWithoutBlockingOtherRules() {
        val enabled = EventPolicy(true).setRule(note).setRule(opened)
        val pending = enabled.accept(note.app,note.kind,20)
        assertSame(pending,pending.accept(note.app,note.kind,30))
        val both = pending.accept(opened.app,opened.kind,31)
        assertEquals(listOf(EventReminder(note.id,20), EventReminder(opened.id,31)),both.pending)
        assertEquals(listOf(EventReminder(opened.id,31)),both.remove(note.id).pending)
        assertTrue(both.setRule(note.copy(taskId="other")).pending.none { it.ruleId==note.id })
    }

    @Test fun notificationUpdatesAndForegroundWindowChangesAreEdgesNotRepeatedTasks() {
        val edges = EventEdges()
        assertTrue(edges.posted("key"))
        assertFalse(edges.posted("key"))
        edges.removed("key")
        assertTrue(edges.posted("key"))
        assertTrue(edges.enter(note.app))
        assertFalse(edges.enter(note.app))
        assertTrue(edges.enter("com.example.other"))
        assertTrue(edges.enter(note.app))
    }
}
