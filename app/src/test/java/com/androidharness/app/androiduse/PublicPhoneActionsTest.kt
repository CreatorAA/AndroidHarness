package com.androidharness.app.androiduse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PublicPhoneActionsTest {
    private fun parse(spec: PublicPhoneContract, raw: String) = spec.parse(Json.parseToJsonElement(raw).jsonObject)
    private fun refuses(spec: PublicPhoneContract, raw: String) {
        assertThrows(IllegalArgumentException::class.java) { parse(spec, raw) }
    }

    @Test fun clockRequestsAreBoundedCommitsWithTheExactLabelInThePreview() {
        val alarm = parse(PublicPhoneContract.ALARM, """{"hour":7,"minute":3,"label":"出门 🚌"}""")
        assertEquals("07:03\n出门 🚌", alarm.details)
        assertTrue(PublicPhoneContract.ALARM.commits)
        assertTrue(PublicPhoneContract.TIMER.commits)
        listOf("""{"hour":24,"minute":0}""", """{"hour":7,"minute":60}""",
            """{"hour":"7","minute":4}""", """{"hour":7}""", """{"hour":7,"minute":0,"days":[2]}""")
            .forEach { refuses(PublicPhoneContract.ALARM, it) }
        assertEquals("86400s", parse(PublicPhoneContract.TIMER, """{"seconds":86400}""").details)
        listOf("""{"seconds":0}""", """{"seconds":86401}""", """{"seconds":2.5}""")
            .forEach { refuses(PublicPhoneContract.TIMER, it) }
    }

    @Test fun calendarUsesExplicitOffsetsAndRejectsReversedOrAmbiguousTimes() {
        val request = parse(PublicPhoneContract.CALENDAR,
            """{"title":"交接","begin":"2026-11-26T09:30:00+08:00","end":"2026-11-26T02:45:00Z","location":"北门","description":"带上钥匙"}""")
            as PublicPhoneContract.Request.Event
        assertEquals(75L * 60, request.end!!.toEpochSecond() - request.begin!!.toEpochSecond())
        listOf("交接", "09:30+08:00", "02:45Z", "北门", "带上钥匙").forEach { assertTrue(request.details.contains(it)) }
        listOf("""{"title":"午饭","begin":"2026-11-26T12:00"}""",
            """{"title":"午饭","end":"2026-11-26T12:00Z"}""",
            """{"title":"午饭","begin":"2026-11-26T12:00Z","end":"2026-11-26T12:30+08:00"}""")
            .forEach { refuses(PublicPhoneContract.CALENDAR, it) }
    }

    @Test fun strictContractsNeverDropRequestedContentOrAcceptAReceiverOverride() {
        val body = "第一行\n第二行 🙂"
        assertEquals(body, parse(PublicPhoneContract.NOTE, """{"text":"第一行\n第二行 🙂"}""").details)
        // This phone-specific editor has no support contract for prefilled text.
        assertEquals("", parse(PublicPhoneContract.XIAOMI_NOTE, "{}").details)
        refuses(PublicPhoneContract.XIAOMI_NOTE, """{"text":"不能悄悄丢掉"}""")
        refuses(PublicPhoneContract.MAP, """{"query":"站点","packageName":"other.app"}""")
        refuses(PublicPhoneContract.MAP, """{"query":{"intent":"intent:#Intent;end"}}""")
        assertEquals("西站 & A/B?", parse(PublicPhoneContract.MAP, """{"query":"西站 & A/B?"}""").details)
    }
}
