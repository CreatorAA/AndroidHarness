package com.androidharness.app.androiduse

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LauncherJourneyTest {
    private val start = "example.files"
    private val home = "example.home"
    private val app = "example.calculator"
    private val trip = LauncherTrip(home, app, listOf("计算器"))
    private fun node(id: String, label: String, clickable: Boolean = true, editable: Boolean = false, res: String = "") =
        PhoneNode(id, label, "", editable, clickable, 10, id.length * 20, 80, id.length * 20 + 60, resource = res)
    private fun frame(pkg: String, vararg nodes: PhoneNode) =
        PhoneFrame(pkg, pkg, 400, 800, 0, nodes.toList())

    private inner class Demo(private val wrong: Boolean = false) : PhoneDriver {
        var page = "source"
        val events = mutableListOf<PhoneAction>()
        var reads = 0
        override val label = "test"
        override suspend fun capabilities() = emptySet<String>()
        override suspend fun observe(includeImage: Boolean) = error("Unscoped observation")
        fun current() = when (page) {
            "source" -> frame(start)
            "home" -> frame(home, node("1", "Toutes les applis", clickable = false, res = "example.home:id/all_apps_handle"))
            "drawer" -> frame(home, node("2", "Rechercher", editable = true, res = "example.home:id/apps_search_box"))
            "matches" -> frame(home, node("2", "计算器", editable = true), node("3", "计算器"))
            "app" -> frame(app)
            else -> frame("unapproved.package")
        }
        override suspend fun observeInApps(allowed: Set<String>): PhoneFrame {
            val f = current()
            check(f.packageName in allowed) { "An unapproved app is in the foreground." }
            reads++
            return f
        }
        override suspend fun dispatch(frame: PhoneFrame, action: PhoneAction): String {
            events += action
            page = when (page to action.kind) {
                "source" to "home" -> "home"
                "home" to "swipe" -> "drawer"
                "drawer" to "set_text" -> { check(action.text == "计算器"); "matches" }
                "matches" to "tap" -> { check(action.node == "3"); if (wrong) "wrong" else "app" }
                else -> error("Unexpected action: ${action.kind}")
            }
            return "dispatched"
        }
    }

    @Test fun crossesLauncherAndReportsOnlyVerifiedIconTap() = runBlocking {
        val driver = Demo()
        val receipt = LauncherJourney(trip, driver, {}, {}).run(driver.current())
        assertEquals(listOf("home", "swipe_up", "search_apps", "tap_icon"), receipt)
        assertEquals(listOf("home", "swipe", "set_text", "tap"), driver.events.map { it.kind })
        assertEquals(app, driver.current().packageName)
    }

    @Test fun wrongAppStopsAfterOneTap() = runBlocking {
        val driver = Demo(wrong = true)
        val error = runCatching { LauncherJourney(trip, driver, {}, {}).run(driver.current()) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("unapproved"))
        assertEquals(1, driver.events.count { it.kind == "tap" })
        assertEquals("tap", driver.events.last().kind)
    }

    @Test fun revocationStopsBeforeNextInput() = runBlocking {
        val driver = Demo()
        val error = runCatching {
            LauncherJourney(trip, driver, { check(driver.events.none { it.kind == "home" }) { "revoked" } }, {})
                .run(driver.current())
        }.exceptionOrNull()
        assertEquals("revoked", error?.message)
        assertEquals(listOf("home"), driver.events.map { it.kind })
    }

    @Test fun neitherSearchTextNorAmbiguousIconsAuthorizeATap() {
        val field = node("1", "计算器", editable = true)
        assertNull(launcherIcon(frame(home, field, node("2", "计算器使用说明")), trip.labels))
        assertNull(launcherIcon(frame(home, field, node("22", "计算器"), node("333", "计算器")), trip.labels))
        assertEquals("2", launcherIcon(frame(home, field, node("2", "计算器")), trip.labels)?.id)
    }

    @Test fun usesFolderRoleOnAnUnknownLauncherWithoutGuessingFromItsCaption() = runBlocking {
        val folder = node("1", "Dienstprogramme").copy(viewClass = "example.launcher.FolderIconView")
        var displayed = frame(home, node("2", "Galerie"), folder)
        val driver = object : PhoneDriver {
            override val label = "fixture"
            override suspend fun capabilities() = emptySet<String>()
            override suspend fun observe(includeImage: Boolean) = error("Unexpected unscoped observation")
            override suspend fun observeInApps(allowed: Set<String>) =
                displayed.also { check(it.packageName in allowed) }
            override suspend fun dispatch(frame: PhoneFrame, action: PhoneAction): String {
                check(action.kind == "tap")
                displayed = when (action.node) {
                    "1" -> this@LauncherJourneyTest.frame(home, node("3", "计算器"))
                    "3" -> this@LauncherJourneyTest.frame(app)
                    else -> error("Unrelated control")
                }
                return "dispatched"
            }
        }
        assertEquals(listOf("open_folder", "tap_icon"), LauncherJourney(trip, driver, {}, {}).run(displayed))
    }
}
