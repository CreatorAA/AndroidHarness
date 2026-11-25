package com.androidharness.app.assistant

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PageActionHandlesTest {
    private val handles = PageActionHandles<String>()
    private fun catalog(vararg ids: String) = handles.replace(ids.toList()) { it }

    @Test fun previousPageCannotReuseAnIdenticallyNamedAction() {
        val previous = catalog("play").single()
        val fresh = catalog("play").single()
        assertNotEquals(previous.ref, fresh.ref)
        assertNull(handles.take(previous.ref))
        assertEquals("play", handles.take(fresh.ref))
        assertNull(handles.take(fresh.ref))
    }

    @Test fun platformInvalidationAndDispatchInvalidateEveryOutstandingReference() {
        val before = catalog("pause", "play")
        handles.clear()
        assertNull(handles.take(before[0].ref))
        val next = catalog("pause", "play")
        assertEquals("play", handles.take(next[1].ref))
        assertNull(handles.take(next[0].ref))
    }

    @Test fun onlyUnambiguousBoundedIdsAreOffered() {
        val list = catalog("play", "only_me", "play", "", "line\nbreak", "a".repeat(129), "播放")
        assertEquals(listOf("only_me", "播放"), list.map { it.id })
        assertEquals(40, handles.replace((1..100).map { it.toString() }) { it }.size)
    }

    @Test fun argumentTypesPreserveStringsIncludingUnicodeAndTypedNumbers() {
        val args = Json.parseToJsonElement("""{"label":"雪🌿","text":"001","flag":true,"count":3,"long":9223372036854775807,"fraction":0.75}""").jsonObject
        assertEquals(mapOf("label" to "雪🌿", "text" to "001", "flag" to true, "count" to 3,
            "long" to Long.MAX_VALUE, "fraction" to 0.75), pageArguments(args))
    }

    @Test fun nestedOversizeAndNullArgumentsAreRejectedBeforeDispatch() {
        for (args in listOf("""{"path":{"URI":"test"}}""", """{"list":[1]}""", """{"result":null}""", """{"":1}""",
            """{"label":"${"a".repeat(1025)}"}""", """{"number":1e999}""")) {
            assertThrows(IllegalArgumentException::class.java) { pageArguments(Json.parseToJsonElement(args).jsonObject) }
        }
    }
}
