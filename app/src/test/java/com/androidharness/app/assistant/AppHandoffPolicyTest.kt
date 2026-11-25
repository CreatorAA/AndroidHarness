package com.androidharness.app.assistant

import com.androidharness.app.androiduse.MapHandoffRoute

import org.junit.Assert.*
import org.junit.Test

class AppHandoffPolicyTest {
    private val amap = HandoffApp("高德地图", AppHandoffPolicy.AMAP, "Main", "Poi", MapHandoffRoute.AMAP)
    private val baidu = HandoffApp("百度地图", AppHandoffPolicy.BAIDU, "Main", "Search", MapHandoffRoute.BAIDU)
    private val notes = HandoffApp("记事本", "local.notes", "NoteHome")
    private fun find(name: String, vararg apps: HandoffApp) =
        AppHandoffPolicy.plan(AppHandoffRequest("map_search", name, "纸月测试公园 & A/B?"), apps.toList())

    @Test fun missingBaiduNeverTurnsAnAmapChoiceIntoTheRequestedApp() {
        val p = find("百度地图", amap, notes)
        assertEquals(AppHandoffStatus.APP_NOT_FOUND, p.status)
        assertEquals("百度地图", p.requestedApp)
        assertEquals("纸月测试公园 & A/B?", p.query)
        assertEquals(listOf(amap.search()), p.choices)
        assertTrue(find("百度地图", notes).choices.isEmpty())
    }

    @Test fun canonicalMapWinsEvenWhenTranslatedOrImpersonatedByLabel() {
        val other = amap.copy(label = "百度地图", pkg = "another.brand")
        val p = find("Baidu Maps", other, amap, baidu.copy(label = "百度地圖"))
        assertEquals(AppHandoffStatus.READY, p.status)
        assertEquals(AppHandoffPolicy.BAIDU, p.choices.single().packageName)
        assertTrue(find("百度地图", other).choices.isEmpty())
        assertEquals(AppHandoffPolicy.AMAP, find("高德", baidu, amap).choices.single().packageName)
    }

    @Test fun installedWithoutSearchCanOfferOpeningButNeverClaimsToSearch() {
        val noSearch = baidu.copy(mapActivity = null, mapRoute = null)
        val p = find("百度地图", noSearch, amap)
        assertEquals(AppHandoffStatus.OPEN_ONLY, p.status)
        assertEquals(listOf(noSearch.open(), amap.search()), p.choices)
        val noEntry = find("百度地图", noSearch.copy(launcher = null))
        assertEquals(AppHandoffStatus.NO_HANDLER, noEntry.status)
        assertTrue(noEntry.choices.isEmpty())
    }

    @Test fun genericSearchAndAmbiguousAppNamesRequireAChoice() {
        assertEquals(AppHandoffStatus.AMBIGUOUS, find("地图", amap, baidu).status)
        assertEquals(AppHandoffStatus.READY, find("", amap).status)
        assertEquals(AppHandoffStatus.NO_HANDLER, find("").status)
        val app1 = HandoffApp("同名", "one", "1")
        val app2 = HandoffApp("另一个名称", "two", "2", otherLabels = setOf("同名"))
        val p = AppHandoffPolicy.plan(AppHandoffRequest("open_app", "同名"), listOf(app1, app2))
        assertEquals(AppHandoffStatus.AMBIGUOUS, p.status)
        assertEquals(listOf("one", "two"), p.choices.map { it.packageName })
    }

    @Test fun openingAnAppIsNotASearchAndDoesNotAllowArbitraryActionsOrUriArguments() {
        val p = AppHandoffPolicy.plan(AppHandoffRequest("open_app", "记事本"), listOf(notes))
        assertEquals(AppHandoffStatus.READY, p.status)
        assertEquals(notes.open(), p.choices.single())
        val baiduSearch = HandoffApp("百度", "com.baidu.searchbox", "Main")
        assertEquals(baiduSearch.open(), AppHandoffPolicy.plan(AppHandoffRequest("open_app", "百度"),
            listOf(baidu, baiduSearch)).choices.single())
        for (bad in listOf(AppHandoffRequest("open_app", notes.label, "intent:foo"),
            AppHandoffRequest("VIEW", "", "https://example.invalid"), AppHandoffRequest("map_search", "地图", " "))) {
            assertThrows(IllegalArgumentException::class.java) { AppHandoffPolicy.plan(bad, listOf(notes)) }
        }
    }
}
