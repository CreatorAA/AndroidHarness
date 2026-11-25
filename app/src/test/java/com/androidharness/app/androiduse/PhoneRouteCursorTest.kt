package com.androidharness.app.androiduse

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneRouteCursorTest {
    private val pkg = "example.maps"
    private val public = buildJsonArray { add(buildJsonObject { put("id", "map_search") }) }
    private val page = buildJsonArray { add(buildJsonObject { put("id", "list_routes"); put("ref", "ref-42") }) }
    private val empty = JsonArray(emptyList())

    @Test fun selectingAnObservationSourceSupersedesAnyOfferWithoutDecliningIt() {
        val c = PhoneRouteCursor()
        c.found(pkg, public, 1)
        val first = c.offer!!.id
        c.select(PhoneRouteCursor.Tier.SCREEN)
        assertEquals(PhoneRouteCursor.Tier.SCREEN, c.tier)
        assertFalse(c.accepts(first, "public_api", "map_search", 2))
        c.select(PhoneRouteCursor.Tier.PAGE)
        c.found(pkg, page, 3)
        assertTrue(c.accepts(c.offer!!.id, "page_action", "ref-42", 4))
    }

    @Test fun onlyTheIssuedOfferCanBeDeclinedOrSelected() {
        val c = PhoneRouteCursor()
        assertThrows(IllegalArgumentException::class.java) { c.reconcile(pkg, "guessed", "use clicks", 1) }
        c.found(pkg, public, 2)
        val first = c.offer!!
        assertTrue(c.accepts(first.id, "public_api", "map_search", 3))
        assertFalse(c.accepts(first.id, "page_action", "map_search", 3))
        c.reconcile(pkg, null, null, 4)
        assertSame(first, c.offer)
        assertThrows(IllegalArgumentException::class.java) { c.reconcile(pkg, first.id, "  ", 5) }
        assertThrows(IllegalArgumentException::class.java) { c.reconcile(pkg, "stale", "already on map", 5) }
        c.reconcile(pkg, first.id, "Need available routes, not a place search.", 6)
        assertEquals(PhoneRouteCursor.Tier.PAGE, c.tier)
        c.found(pkg, page, 7)
        val second = c.offer!!
        assertFalse(c.accepts(first.id, "public_api", "map_search", 8))
        assertFalse(c.accepts(second.id, "page_action", "list_routes", 8))
        assertTrue(c.accepts(second.id, "page_action", "ref-42", 8))
        c.reconcile(pkg, second.id, "Need the displayed distance, not route generation.", 9)
        assertEquals(PhoneRouteCursor.Tier.SCREEN, c.tier)
        assertNull(c.offer)
    }

    @Test fun emptyTiersProceedWithoutAskingTheModel() {
        val c = PhoneRouteCursor()
        c.found(pkg, empty, 1)
        assertEquals(PhoneRouteCursor.Tier.PAGE, c.tier)
        c.found(pkg, empty, 2)
        assertEquals(PhoneRouteCursor.Tier.SCREEN, c.tier)
        c.restart()
        assertEquals(PhoneRouteCursor.Tier.PUBLIC, c.tier)
        assertNull(c.offer)
    }

    @Test fun expiredOrRetargetedOfferDoesNotAuthorizeOrSkipANewRoute() {
        val c = PhoneRouteCursor()
        c.found(pkg, public, 1)
        val id = c.offer!!.id
        assertFalse(c.accepts(id, "public_api", "map_search", 90_002))
        assertThrows(IllegalArgumentException::class.java) { c.reconcile(pkg, id, "no", 90_002) }
        assertNull(c.offer)
        c.found(pkg, empty, 100_000)
        c.found(pkg, page, 100_001)
        val ref = c.offer!!.id
        assertThrows(IllegalArgumentException::class.java) { c.reconcile("different.app", ref, "no", 100_002) }
        assertEquals(PhoneRouteCursor.Tier.PUBLIC, c.tier)
    }

    @Test fun twoDifferentPlacesKeepTheirPublicRouteButTheSameRequestCannotBeSentTwice() {
        val ledger = PhonePublicHandoffs()
        val map = PublicHandoffKey(pkg, "map_search", "geo:0,0?q=West", emptyMap())
        ledger.record(map, buildJsonObject { put("query", "West") })
        assertThrows(IllegalArgumentException::class.java) { ledger.requireNew(map.copy()) }
        ledger.requireNew(map.copy(data = "geo:0,0?q=East"))
        val editor = PublicHandoffKey(pkg, "note_draft", null, emptyMap())
        ledger.record(editor, JsonObject(emptyMap()))
        val remaining = ledger.available(pkg, buildJsonArray {
            add(buildJsonObject { put("id", "map_search"); putJsonObject("parameters") { put("query", "Place") } })
            add(buildJsonObject { put("id", "note_draft"); putJsonObject("parameters") {} })
        }).single().jsonObject
        assertEquals("map_search", remaining.getValue("id").jsonPrimitive.content)
        assertEquals("West", remaining.getValue("lastHandedOffArguments").jsonObject.getValue("query").jsonPrimitive.content)

        // Both ISO timestamp spellings in model input produce this same Android millisecond extra.
        val event = PublicHandoffKey(pkg, "calendar_draft", null, mapOf("beginTime" to 60_000L))
        ledger.record(event, buildJsonObject { put("begin", "1970-01-01T00:01:00Z") })
        assertThrows(IllegalArgumentException::class.java) { ledger.requireNew(event.copy(extras = mapOf("beginTime" to 60_000L))) }
    }
}
