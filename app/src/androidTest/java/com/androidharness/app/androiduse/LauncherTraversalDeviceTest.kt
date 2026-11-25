package com.androidharness.app.androiduse

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.data.env.DeviceAccessStore
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.tools.ToolResult
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Opt-in on the Pixel Launcher test AVD. No adb gestures or package launches drive the tested steps. */
@RunWith(AndroidJUnit4::class)
class LauncherTraversalDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
    private val c get() = app.container
    private val phone get() = c.androidUse
    private val fixture = "com.androidharness.fixture.phoneuse"
    private val fixtureLabel = "手机操作验证"
    private val files = "com.google.android.documentsui"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val grants by lazy { DeviceAccessStore(app) }
    private val tools by lazy { phone.tools().associateBy { it.name } }
    private lateinit var id: String
    private val ctx get() = ToolContext(FileFs(app.cacheDir), sessionId = id)
    private val frames = mutableListOf<String>()

    /** The harness explicitly starts a new human-approved lease at each boundary; it never retargets an old one. */
    private suspend fun begin(pkg: String) {
        id = "launcher-traversal-" + UUID.randomUUID()
        assertTrue(phone.begin(id, pkg))
        grants.allowPhoneControl(id, true)
        withContext(Dispatchers.Main) { PhonePanel.bind(id) { true } }
    }

    private suspend fun end() {
        if (!::id.isInitialized) return
        phone.end(id)
        grants.allowPhoneControl(id, false)
        withContext(Dispatchers.Main) { PhonePanel.unbind(id) }
    }

    private fun body(r: ToolResult): JsonObject {
        assertTrue(r.output, r.ok)
        return Json.parseToJsonElement(r.output).jsonObject
    }

    private suspend fun screen(image: Boolean = false, lookup: Boolean = false, tripOnly: Boolean = false): JsonObject {
        var offer: String? = null
        repeat(3) {
            val r = tools.getValue("android_observe").execute(buildJsonObject {
                put("image", image)
                if (lookup) put("appQuery", fixtureLabel)
                if (tripOnly) put("forLauncherTrip", true)
                offer?.let { put("declineOffer", it); put("reason", "The requested task specifically examines the launcher and taps the icon.") }
            }, ctx)
            val b = body(r)
            if (b.getValue("route").jsonPrimitive.content == "screen") {
                if (image) {
                    // Deliver actual pixels to this scripted test consumer before submitting its swipe.
                    val ref = requireNotNull(r.image)
                    frames += ref.name
                    val data = requireNotNull(c.images.resolve(ref))
                    val bytes = Base64.decode(data.base64, Base64.DEFAULT)
                    val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    try {
                        assertEquals(bitmap.width, b.getValue("previewWidth").jsonPrimitive.int)
                        assertEquals(bitmap.height, b.getValue("previewHeight").jsonPrimitive.int)
                    } finally { bitmap.recycle() }
                    phone.markDelivered(setOf(ref.name), id)
                }
                return b
            }
            offer = b.getValue("observationId").jsonPrimitive.content
        }
        error("Screen route not available")
    }

    private suspend fun act(f: JsonObject, kind: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject {
        var current = f
        repeat(3) { attempt ->
            val args = buildJsonObject { put("observationId", current.getValue("observationId")); put("kind", kind); fields() }
            val r = tools.getValue("android_action").execute(args, ctx)
            val metadata = runCatching { Json.parseToJsonElement(r.output).jsonObject }.getOrNull()
            if (attempt == 2 || r.ok || metadata?.get("status")?.jsonPrimitive?.content != "not_dispatched") {
                assertTrue("$kind in ${current["packageName"]}: ${r.output}", r.ok)
                return body(r)
            }
            // Android marked the action definitely not dispatched while the window animation settled.
            delay(650)
            current = screen(image = "x" in args)
            assertEquals(f["packageName"], current["packageName"])
            if ("node" in args) {
                fun selected(frame: JsonObject) = nodes(frame).single { it["id"] == args["node"] }
                // The status bar/IME can translate the same controls, so re-match the same labeled
                // node in the same relative order instead of replaying its previous coordinates.
                fun identity(n: JsonObject) = n.filterKeys { it != "screenBounds" }
                assertEquals(identity(selected(f)), identity(selected(current)))
                fun peers(frame: JsonObject) = nodes(frame).filter { label(it) == label(selected(frame)) }
                    .sortedBy { it.getValue("screenBounds").jsonArray[1].jsonPrimitive.int }.map { it.getValue("id") }
                assertEquals(peers(f), peers(current))
            }
        }
        error("No action receipt")
    }

    private fun nodes(f: JsonObject) = f.getValue("nodes").jsonArray.map { it.jsonObject }
    private fun label(n: JsonObject) = listOf("text", "description").mapNotNull { n[it]?.jsonPrimitive?.content }
    private fun note(msg: String) { Log.i("HarnessLauncherTrial", msg) }

    private suspend fun settledFixtureScreen(): JsonObject {
        var previous = screen()
        repeat(14) {
            delay(240)
            val next = screen()
            if (previous["window"] == next["window"] && previous["nodes"] == next["nodes"] &&
                next.getValue("packageName").jsonPrimitive.content == fixture) return next
            previous = next
        }
        error("Fixture was still changing.")
    }

    private suspend fun fillAndChoose() {
        val initial = settledFixtureScreen()
        val edit = nodes(initial).single { it["editable"]?.jsonPrimitive?.booleanOrNull == true }
        val text = if (edit["text"]?.jsonPrimitive?.content == "跨应用核对") "跨应用核对二" else "跨应用核对"
        val typed = act(initial, "set_text") { put("node", edit.getValue("id")); put("text", text) }
        note("Fixture input receipt: $typed")
        assertEquals(typed.toString(), "verified", typed.getValue("status").jsonPrimitive.content)
        val filled = settledFixtureScreen()
        val choice = nodes(filled).filter { "选择" in label(it) && it["clickable"]?.jsonPrimitive?.booleanOrNull == true }
            .maxBy { it.getValue("screenBounds").jsonArray[1].jsonPrimitive.int }
        val result = act(filled, "tap") { put("node", choice.getValue("id")); put("expectedText", "第二步 · 核对") }
        assertEquals(result.toString(), "verified", result.getValue("status").jsonPrimitive.content)
        assertTrue(nodes(screen()).flatMap(::label).any { "选项：白鹭" in it && text in it })
        note("Fixture verified text entry and lower choice: 白鹭 / $text. No submit.")
    }

    /** The single narrowly approved trip may span packages. A reordered or different request may not. */
    @Test fun approvedIconJourneyContinuesInTheChosenApp() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        assumeTrue(phone.activeSession.value == null)
        val original = phone.settings.backend
        try {
            phone.settings.backend = "builtin"
            app.startActivity(requireNotNull(app.packageManager.getLaunchIntentForPackage(files)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1200)
            begin(files)
            val f = screen(lookup = true, tripOnly = true)
            assertTrue(nodes(f).isEmpty())
            assertFalse(f.getValue("image_attached").jsonPrimitive.boolean)
            val match = f.getValue("matchingApps").jsonObject.getValue("apps").jsonArray.single {
                it.jsonObject.getValue("label").jsonPrimitive.content == fixtureLabel
            }
            val args = buildJsonObject {
                put("kind", "find_on_home"); put("observationId", f.getValue("observationId"))
                put("packageName", match.jsonObject.getValue("packageName"))
            }
            assertEquals(fixture, args.getValue("packageName").jsonPrimitive.content)
            val action = tools.getValue("android_action")
            assertFalse(action.execute(args, ctx).ok)
            val approval = phone.launcherApproval(id, args)
            assertTrue(approval.preview, files in approval.preview && fixture in approval.preview)
            assertTrue(approval.confirm())
            val otherTarget = JsonObject(args + ("packageName" to JsonPrimitive("com.android.chrome")))
            assertFalse("User only authorized the fixture", action.execute(otherTarget, ctx).ok)
            val result = body(action.execute(args, ctx))
            note("Single foreground lease, authorized fixture icon trip receipt: $result")
            assertEquals("verified", result.getValue("status").jsonPrimitive.content)
            assertEquals("find_on_home", result.getValue("route").jsonPrimitive.content)
            assertEquals(fixture, result.getValue("packageName").jsonPrimitive.content)
            val methods = result.getValue("methods").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(methods.toString(), "home" in methods && "tap_icon" in methods && "launch" !in methods)
            assertTrue(phone.isActive(id))
            assertFalse("Cannot replay a consumed trip", action.execute(args, ctx).ok)
            fillAndChoose()
        } finally {
            end()
            phone.settings.backend = original
        }
        Unit
    }

    @Test fun managedDirectSwitchVisitsOnlyTheNamedFixtureThenReturnsWithoutRestarting() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        val backend = phone.settings.backend
        assumeTrue(phone.activeSession.value == null)
        id = "direct-switch-" + UUID.randomUUID()
        val staleBinder = object : PageActionSource {
            override suspend fun isCurrent(packageName: String, authorized: () -> Boolean) = false
            override suspend fun discover(packageName: String, authorized: () -> Boolean) =
                if (packageName == fixture) PageActionList("wrong_target") else PageActionList("unavailable")
            override suspend fun perform(packageName: String, ref: String, arguments: JsonObject, authorized: () -> Boolean): PageActionReply =
                error("Must not invoke stale Direct Actions.")
        }
        // DocumentsUI first resumes a short-lived LauncherActivity under the same package.
        // Wait for the real file browser, not a package match or a fixed launch delay.
        suspend fun awaitFilesUi() = kotlinx.coroutines.withTimeout(8_000) {
            while (!withContext(Dispatchers.Main) {
                val root = PhoneAccessibilityService.current?.rootInActiveWindow
                root?.packageName?.toString() == files &&
                    root.findAccessibilityNodeInfosByViewId("$files:id/container_directory").isNotEmpty()
            }) delay(50)
        }
        try {
            phone.settings.backend = "builtin"
            app.startActivity(requireNotNull(app.packageManager.getLaunchIntentForPackage(files)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            awaitFilesUi()
            assertTrue(phone.begin(id, files, true, "对照‘手机操作验证’与当前文件应用的页面，然后回到起始应用。"))
            phone.bindTaskScope(id,buildJsonObject { put("taskScope",fixtureTaskScope("inspect",apps=listOf(fixtureLabel))) })
            withContext(Dispatchers.Main) {
                PhonePanel.bind(id) { true }
                PageActions.bind(id, staleBinder) { phone.isActive(id) }
            }
            suspend fun go(pkg: String) {
                var from = screen()
                suspend fun tryLaunch(): ToolResult {
                    val args = buildJsonObject { put("kind","launch"); put("observationId",from.getValue("observationId")); put("packageName",pkg) }
                    val review = phone.managedScreenApproval(id, args)
                    assertTrue(review.preview, pkg in review.preview)
                    assertTrue(review.coveredByTask)
                    assertTrue(review.confirm())
                    return tools.getValue("android_action").execute(args, ctx)
                }
                var result = tryLaunch()
                if (!result.ok && Json.parseToJsonElement(result.output).jsonObject["status"]?.jsonPrimitive?.content == "not_dispatched") {
                    val old = from
                    // The driver guarantees nothing was sent. Re-observe once; never retry an unknown outcome.
                    from = screen()
                    assertNotEquals(old.getValue("observationId"), from.getValue("observationId"))
                    note("Before $pkg: first window=${old["window"]}, fresh=${from["window"]}")
                    result = tryLaunch()
                }
                assertTrue("To $pkg from ${from["window"]}: ${result.output}",result.ok)
                val payload = body(result)
                assertEquals(payload.toString(), "verified", payload.getValue("status").jsonPrimitive.content)
                assertEquals(pkg, payload.getValue("packageName").jsonPrimitive.content)
                if (pkg == files) awaitFilesUi()
                val arrived = screen()
                assertEquals(pkg, arrived.getValue("packageName").jsonPrimitive.content)
                assertTrue(phone.isActive(id))
                if (pkg == fixture) assertEquals("activity_not_current", arrived.getValue("pageActionsStatus").jsonPrimitive.content)
            }
            go(fixture)
            go(files)
            go(fixture)
            val oldQuestion = requireNotNull(phone.clarification(id))
            phone.end(id)
            assertTrue(phone.begin(id, fixture, true, "核对当前页"))
            assertTrue(oldQuestion.ended.isCompleted)
            assertFalse(oldQuestion.accept("然后打开地图"))
            oldQuestion.revoke()
            assertTrue(phone.isActive(id))
            note("Three real package switches on one managed task; no per-destination confirmations or stale page action, no resummons.")
        } finally {
            withContext(Dispatchers.Main) { PageActions.unbind(id, staleBinder) }
            end()
            phone.settings.backend = backend
        }
    }

    @Test fun homeAndIconTapsRespectAppBoundaries() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneUseFixture") == "true" &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        awaitFixturePhoneService()
        val original = phone.settings.backend
        assumeTrue(phone.activeSession.value == null)
        try {
            phone.settings.backend = "builtin"
            // Setup only: Files is the starting app, never the searched-for target.
            app.startActivity(requireNotNull(app.packageManager.getLaunchIntentForPackage(files)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1200)
            begin(files)
            val start = screen(lookup = true)
            assertEquals(files, start.getValue("packageName").jsonPrimitive.content)
            assertTrue(start.getValue("matchingApps").jsonObject.getValue("apps").jsonArray.any {
                it.jsonObject.getValue("packageName").jsonPrimitive.content == fixture
            })
            val home = act(start, "home"); assertFalse(phone.isActive(id))
            note("Files identified the fixture by its display name. Home result: $home")
            end()

            delay(900)
            begin(launcher)
            var tile: JsonObject? = null
            var frame = screen(image = true)
            var swipes = 0
            while (true) {
                assertEquals(launcher, frame.getValue("packageName").jsonPrimitive.content)
                tile = nodes(frame).firstOrNull { n -> label(n).any { it == fixtureLabel } && n["clickable"]?.jsonPrimitive?.booleanOrNull == true }
                // Examine a different launcher page even if predictions already include this fixture on Home.
                if (tile != null && swipes > 0 || swipes == 5) break
                val w = frame.getValue("previewWidth").jsonPrimitive.int
                val h = frame.getValue("previewHeight").jsonPrimitive.int
                val result = act(frame, "swipe") {
                    put("x", w / 2); put("y", h * 4 / 5); put("toX", w / 2); put("toY", h / 4); put("durationMs", 400)
                }
                assertNotEquals(result.toString(), "not_dispatched", result["status"]?.jsonPrimitive?.content)
                delay(900); swipes++
                frame = screen(image = true)
            }
            val icon = requireNotNull(tile) { "No fixture tile after $swipes swipes. Launcher labels: " + nodes(frame).flatMap(::label) }
            val clicked = act(frame, "tap") { put("node", icon.getValue("id")) }
            note("Pixel Launcher revealed the fixture icon after $swipes app-drawer swipes; dispatched node tap: $clicked")
            delay(1200)
            val afterTap = tools.getValue("android_observe").execute(buildJsonObject { put("image", false) }, ctx)
            assertFalse("The launcher lease must not inspect a newly opened app.", afterTap.ok)
            assertFalse(phone.isActive(id)); note("Launcher scope after icon tap: " + afterTap.output)
            end()

            begin(fixture)
            fillAndChoose()
        } finally {
            end()
            frames.forEach { c.images.delete(it) }
            phone.settings.backend = original
        }
        Unit
    }
}
