package com.androidharness.app.androiduse

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneExtrasTest {
    @Test fun exactMatchesLeadAndSameLabelAppsRemainDistinct() {
        val apps = listOf(LaunchableApp("c.notes", "笔记本"), LaunchableApp("b.notes", "笔记"),
            LaunchableApp("a.notes", "笔记"), LaunchableApp("a.notes", "笔记-快捷入口"))
        val result = matchLaunchableApps(" 笔记 ", apps)
        assertEquals(listOf("a.notes", "b.notes", "c.notes"),
            result.getValue("apps").jsonArray.map { it.jsonObject.getValue("packageName").jsonPrimitive.content })
        assertEquals("b.notes", matchLaunchableApps("B.NOTES", apps).getValue("apps").jsonArray.single()
            .jsonObject.getValue("packageName").jsonPrimitive.content)
    }

    @Test fun searchBoundsTheListAndReportsUnmatchedInput() {
        val apps = (1..18).map { LaunchableApp("org.notes.app$it", "Notebook $it") }
        val many = matchLaunchableApps("notes", apps)
        assertEquals(12, many.getValue("apps").jsonArray.size)
        assertEquals(18, many.getValue("totalMatches").jsonPrimitive.int)
        assertTrue(many.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(matchLaunchableApps("nonexistent", apps).getValue("apps").jsonArray.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { matchLaunchableApps(" ", apps) }
    }

    @Test fun volumeUsesRealDeviceStepsIncludingNonzeroMinimum() {
        assertEquals(1, volumeIndex(0, 1, 15))
        assertEquals(8, volumeIndex(50, 1, 15))
        assertEquals(15, volumeIndex(100, 1, 15))
        assertThrows(IllegalArgumentException::class.java) { volumeIndex(101, 0, 15) }
    }

    @Test fun webHandoffAllowsPublicWebUrlsButNotLocalOrPrivilegedIntents() {
        assertEquals("https://example.com/a?q=2#item", webHandoffLink("https://example.com/a?q=2#item").toString())
        listOf("intent:#Intent;action=android.intent.action.VIEW;end", "file:///sdcard/note", "content://source/doc",
            "https://name:password@example.com", "http://localhost.", "http://printer.local./", "http://router",
            "http://127.0.0.1", "http://0x7f.0x0.0x0.0x1", "http://[::1]", "https://appassets.androidplatform.net/assets/index.html").forEach { url ->
            assertTrue(url, runCatching { webHandoffLink(url) }.isFailure)
        }
    }
}
