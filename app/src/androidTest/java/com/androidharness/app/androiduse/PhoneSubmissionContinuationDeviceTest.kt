package com.androidharness.app.androiduse

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.assistant.PhonePanel
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.tools.AndroidUseScope
import com.androidharness.app.tools.ToolContext
import com.androidharness.app.workspace.FileFs
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run the real scope, observation, approval, image-delivery and action code without touching the user's display. */
@RunWith(AndroidJUnit4::class)
class PhoneSubmissionContinuationDeviceTest {
    private class Quiz(val pixels: Boolean) : PhoneDriver {
        override val label = "in-memory quiz"
        val pkg = "com.androidharness.fixture.quiz"
        val sent = mutableListOf<PhoneAction>()
        var step = 1
        private val headings = listOf("Which shape has three sides?", "Which direction is right on a north-up map?")
        private val answers = listOf(listOf("Triangle", "Square", "Circle"), listOf("East", "South", "West"))

        // The app reuses the same window, node IDs and tap locations on every question.
        override suspend fun observe(includeImage: Boolean): PhoneFrame {
            val nodes = if (pixels) listOf(PhoneNode("0", "", "", false, false, 0, 0, 360, 640,
                viewClass = "android.view.SurfaceView")) else buildList {
                add(PhoneNode("0", headings[step-1], "", false, false, 0, 0, 360, 36))
                answers[step-1].forEachIndexed { index, text ->
                    val id = "0.${index+1}"
                    add(PhoneNode(id, text, "", false, true, 0, 44+44*index, 360, 86+44*index,
                        viewClass = "android.widget.Button", resource = "$pkg:id/option$index", tapOwner = id))
                }
            }
            return PhoneFrame(pkg, "QuizActivity|1", 360, 640, 0, nodes,
                image = if (includeImage) drawBoard() else null)
        }

        private fun drawBoard(): PhoneImage {
            val bitmap = Bitmap.createBitmap(180, 320, Bitmap.Config.ARGB_8888)
            return try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                val paint = Paint()
                for (row in 0..7) for (col in 0..4) {
                    paint.color = if ((row + col + step) % 2 == 0) Color.BLUE else Color.YELLOW
                    canvas.drawRect((col*36+1).toFloat(), (row*40+1).toFloat(),
                        (col*36+35).toFloat(), (row*40+39).toFloat(), paint)
                }
                val png = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png))
                PhoneImage(png.toByteArray(), "image/png", bitmap.width, bitmap.height)
            } finally { bitmap.recycle() }
        }

        override suspend fun capabilities() = setOf("observe", "nodes", "image", "tap", "drag")
        override suspend fun observeInApps(allowed: Set<String>, includeImage: Boolean): PhoneFrame {
            check(pkg in allowed)
            return observe(includeImage)
        }
        override suspend fun dispatch(frame: PhoneFrame, action: PhoneAction): String {
            sent += action
            return "dispatched"
        }
    }

    private class Trial(val app: HarnessApp, val quiz: Quiz, val managed: Boolean) {
        val sid = "fixture-quiz"
        val phone = AndroidUseSession(app, app.container.images) { quiz }
        val ctx = ToolContext(FileFs(app.cacheDir), sessionId = sid, imageInputAllowed = quiz.pixels)
        val tools = phone.tools().associateBy { it.name }
        val action = tools.getValue("android_action")
        val storedFrames = mutableListOf<String>()

        suspend fun observe(first: Boolean = false, deliver: Boolean = true, allowSend: Boolean = false): JsonObject {
            val result = tools.getValue("android_observe").execute(buildJsonObject {
                put("driver", "builtin"); put("route", "screen"); put("image", quiz.pixels)
                if (first) {
                    val effects = if (allowSend) arrayOf("send", "change") else arrayOf("save", "change")
                    put("taskScope", fixtureTaskScope("operate", *effects, capture = quiz.pixels))
                }
            }, ctx)
            assertTrue(result.output, result.ok)
            if (quiz.pixels) {
                val ref = checkNotNull(result.image)
                storedFrames += ref.name
                val image = checkNotNull(app.container.images.resolve(ref))
                val delivered = ChatMessage(Role.TOOL, toolName = AndroidUseScope.OBSERVE,
                    images = listOf(ref), imageData = listOf(image))
                if (deliver) phone.markDelivered(AndroidUseScope.deliveredFrames(listOf(delivered)), sid)
            }
            return Json.parseToJsonElement(result.output).jsonObject
        }

        suspend fun send(args: JsonObject): JsonObject {
            val permission = phone.managedScreenApproval(sid, args)
            assertEquals(managed, permission.coveredByTask)
            if (!managed) {
                val count = quiz.sent.size
                assertFalse("Previewing this action is not user approval", action.execute(args, ctx).ok)
                assertEquals(count, quiz.sent.size)
            }
            assertTrue(permission.confirm())
            val result = action.execute(args, ctx)
            assertTrue(result.output, result.ok)
            return Json.parseToJsonElement(result.output).jsonObject
        }

        suspend fun assertBlocked(args: JsonObject) {
            val before = quiz.sent.size
            assertFalse(runCatching { phone.managedScreenApproval(sid, args).confirm() }.getOrDefault(false))
            assertFalse(action.execute(args, ctx).ok)
            assertEquals(before, quiz.sent.size)
        }
    }

    private fun trial(pixels: Boolean, managed: Boolean = true, test: suspend Trial.() -> Unit) = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp
        val trial = Trial(app, Quiz(pixels), managed)
        with(trial) {
            assertTrue(phone.begin(sid, quiz.pkg, managed = managed, userInstruction = "Complete the two rounds."))
            try {
                if (!managed) app.container.deviceAccess.store.allowPhoneControl(sid, true)
                withContext(Dispatchers.Main) { PhonePanel.bind(sid) { true } }
                test()
            } finally {
                phone.end(sid)
                if (!managed) app.container.deviceAccess.store.allowPhoneControl(sid, false)
                withContext(Dispatchers.Main) { PhonePanel.unbind(sid) }
                storedFrames.forEach(app.container.images::delete)
            }
        }
    }

    @Test fun uncertainSubmitBlocksReplayButTheNextObservedQuestionIsActionableInTheSameTask() = runQuestions(true)
    @Test fun approvalModeRequiresANewExactApprovalForTheNextQuestion() = runQuestions(false)

    private fun runQuestions(managed: Boolean) = trial(false, managed) {
        fun submit(frame: JsonObject, answer: String) = buildJsonObject {
            put("kind", "tap"); put("intent", "submit")
            put("observationId", frame.getValue("observationId"))
            val node = frame.getValue("nodes").jsonArray.map { it.jsonObject }
                .single { it["text"]?.jsonPrimitive?.content == answer }
            put("node", node.getValue("id"))
            // The next question was not visible when this answer was picked: no guessed expectedText.
        }
        val first = observe(first = true)
        val firstTap = submit(first, "Triangle")
        assertEquals("dispatched_unverified", send(firstTap).getValue("status").jsonPrimitive.content)
        assertEquals(1, quiz.sent.size)

        // A new observation ID with identical screen content is not proof the submit finished.
        val unchanged = observe()
        assertNotEquals(first["observationId"], unchanged["observationId"])
        val replay = submit(unchanged, "Triangle")
        assertBlocked(replay)

        // The fixture's delayed transition now completes. The original lease remains active.
        quiz.step = 2
        val second = observe()
        assertEquals(first["window"], second["window"])
        assertFalse(action.execute(firstTap, ctx).ok)
        val secondTap = submit(second, "East")
        assertEquals(firstTap["node"], secondTap["node"])
        send(secondTap)
        assertTrue(phone.isActive(sid))
        assertEquals(2, quiz.sent.size)
    }

    @Test fun changedCanvasCanContinueAtTheSameCoordinatesWithoutAnyTextOrNewActivity() = trial(true) {
        fun gesture(frame: JsonObject, kind: String, intent: String, progress: String? = null) = buildJsonObject {
            put("observationId", frame.getValue("observationId")); put("kind", kind); put("intent", intent)
            if (intent == "navigate") put("effect", "change")
            progress?.let { put("observedProgress", it) }
            put("x", 40); put("y", 75)
            if (kind == "drag") { put("toX", 125); put("toY", 97) }
        }
        val first = observe(first = true)
        val tap = gesture(first, "tap", "submit")
        assertEquals("dispatched_unverified", send(tap).getValue("status").jsonPrimitive.content)
        val progress = "The blue and yellow squares have swapped places across the board."
        assertBlocked(gesture(observe(), "tap", "navigate", progress))
        quiz.step++
        assertBlocked(gesture(observe(deliver = false), "tap", "navigate", progress))
        val next = observe()
        assertEquals(first["window"], next["window"])
        assertEquals(first["nodes"], next["nodes"])
        assertFalse(action.execute(tap, ctx).ok)
        assertBlocked(gesture(next, "tap", "navigate"))
        send(gesture(next, "tap", "navigate", progress))
        repeat(2) { send(gesture(observe(), "drag", "navigate")) }
        assertEquals(listOf("tap", "tap", "drag", "drag"), quiz.sent.map { it.kind })
        assertTrue(quiz.sent.all { it.x == 80 && it.y == 150 })
        assertTrue(quiz.sent.drop(2).all { it.toX == 250 && it.toY == 194 })
        assertTrue(phone.isActive(sid))
    }

    @Test fun changedCanvasCannotAuthorizeAnotherActionAfterAnUnconfirmedExternalSend() = trial(true) {
        fun tap(frame: JsonObject, initial: Boolean) = buildJsonObject {
            put("observationId", frame.getValue("observationId")); put("kind", "tap"); put("x", 40); put("y", 75)
            put("effect", if (initial) "send" else "change")
            put("intent", if (initial) "submit" else "navigate")
            if (!initial) put("observedProgress", "The blue and yellow squares have swapped places across the board.")
        }
        val first = observe(first = true, allowSend = true)
        assertEquals("dispatched_unverified", send(tap(first, true)).getValue("status").jsonPrimitive.content)
        quiz.step++
        assertBlocked(tap(observe(), false))
        assertEquals(1, quiz.sent.size)
    }
}
