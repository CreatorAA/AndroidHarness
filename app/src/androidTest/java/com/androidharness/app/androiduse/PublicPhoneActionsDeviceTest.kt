package com.androidharness.app.androiduse

import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect the actual platform payloads without opening apps or creating user data. */
@RunWith(AndroidJUnit4::class)
class PublicPhoneActionsDeviceTest {
    private fun intent(spec: PublicPhoneContract, raw: String) =
        spec.intent(spec.parse(Json.parseToJsonElement(raw).jsonObject))

    @Test fun mapEncodesTheEntireQueryAndCalendarSendsTypedPrefill() {
        val map = intent(PublicPhoneContract.MAP, """{"query":"纸月城 & A/B? #1"}""")
        assertEquals("geo:0,0?q=%E4%B8%8A%E6%B5%B7%20%26%20A%2FB%3F%20%231", map.dataString)
        val event = intent(PublicPhoneContract.CALENDAR,
            """{"title":"Meeting","begin":"1970-01-01T08:01:00+08:00","location":"北门"}""")
        assertEquals(Intent.ACTION_INSERT, event.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, event.data)
        assertEquals("vnd.android.cursor.dir/event", event.type)
        assertEquals(60_000, event.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, -1))
        assertEquals("北门", event.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertFalse(event.hasExtra(CalendarContract.EXTRA_EVENT_END_TIME))
    }

    @Test fun notesAndClocksUseTheirActualContracts() {
        val note = intent(PublicPhoneContract.NOTE, """{"text":"Unicode ✅"}""")
        assertEquals("Unicode ✅", note.getStringExtra("com.google.android.gms.actions.extra.TEXT"))
        val xiaomi = intent(PublicPhoneContract.XIAOMI_NOTE, "{}")
        assertEquals("android.intent.action.CREATE_NOTE", intent(PublicPhoneContract.NOTE_EDITOR, "{}").action)
        assertEquals("vnd.android.cursor.item/text_note", xiaomi.type)
        assertEquals(Intent.ACTION_INSERT_OR_EDIT, xiaomi.action)
        assertNull(xiaomi.data)
        val timer = intent(PublicPhoneContract.TIMER, """{"seconds":123,"label":"散步"}""")
        assertTrue(timer.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false))
        assertEquals(123, timer.getIntExtra(AlarmClock.EXTRA_LENGTH, -1))
        assertEquals("散步", timer.getStringExtra(AlarmClock.EXTRA_MESSAGE))
    }
}
