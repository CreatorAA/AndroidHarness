package com.androidharness.app.androiduse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoJsUiScriptsTest {
    @Test fun onlyLibraryInspectionIsReadOnly() {
        for (op in listOf("list","read","test","save","enable","disable","delete","other")) {
            assertEquals(op in setOf("list","read","test"),
                AutoJsUiScripts.isLibraryInspection(buildJsonObject { put("action", op) }))
        }
        assertFalse(AutoJsUiScripts.isLibraryInspection(buildJsonObject {}))
    }
    @Test fun guidanceFollowsThisRoundsAvailableTools() {
        val lib = AutoJsUiScripts.LIBRARY
        val run = AutoJsUiScripts.RUN
        val phone = com.androidharness.app.tools.AndroidUseScope.ACTION
        assertEquals("", AutoJsUiScripts.guidance(setOf(phone)))
        val libraryOnly = AutoJsUiScripts.guidance(setOf(lib))
        assertTrue(libraryOnly.contains("validated_no_execution"))
        assertFalse(libraryOnly.contains("android_observe"))
        val beforeProbe = AutoJsUiScripts.guidance(setOf(lib, phone))
        assertTrue(beforeProbe.contains("android_capabilities(driver=autojs)"))
        assertFalse(beforeProbe.contains("autojs_run("))
        val ready = AutoJsUiScripts.guidance(setOf(lib, run, phone), listOf("Travel form"))
        assertTrue(ready.contains("autojs_run(name=") && ready.contains("autojs_run(source="))
        assertTrue(ready.contains("[\"Travel form\"]"))
        val example = ready.lineSequence().filter { it.endsWith(";") }.joinToString("\n")
        assertEquals(2, AutoJsUiScripts.check(example).size)
    }
    @get:Rule val temp = TemporaryFolder()
    private val field = PhoneNode("#n2", "草稿", "留言", true, true, 0, 0, 150, 70, "EditText", "test.notes:id/content")
    private val frame = PhoneFrame("test.notes", "w", 100, 100, 0, listOf(field))
    private val source = """// Part of a note; not a form submission.
        |id("content").findOne(250).setText("周五 9:30");
        |text('周五 9:30').waitFor();""".trimMargin()

    @Test fun checkIsPureAndInvalidCodeNeverReachesRuntime() {
        val summaries = AutoJsUiScripts.check(source)
        assertEquals(2,summaries.size)
        assertTrue(summaries[0].contains("周五 9:30"))
        for (bad in listOf("text('x').findOne(0).click();", "sleep(100000);", "shell('id');",
                "text('x').findOne(100).click('unexpected');", "id('x').findOne().setText();",
                "text(foo).waitFor();", "eval('arbitrary');", "var other = 1;", "id('x').findOne(); launchApp('x');",
                List(33) { "text('x').waitFor();" }.joinToString("\n"))) {
            assertThrows(IllegalArgumentException::class.java) { AutoJsUiScripts.check(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { AutoJsUiScripts.check("text('" + "x".repeat(9_000) + "').waitFor();") }
        assertEquals("A\"B", AutoJsUiScripts.compile("""desc("A\"B").findOne();""").single().value)
    }

    @Test fun checksTheActualResultOnAFreshFrameAndDoesNotReadAfterCancellation() = runBlocking {
        var screen = frame
        var writes = 0
        val steps = AutoJsUiScripts.compile(source)
        val good = AutoJsUiScripts.execute(steps, read = { screen }, apply = { observed,node,step ->
            assertSame(screen,observed); assertSame(field,node)
            writes++
            screen = frame.copy(nodes=listOf(node.copy(text = step.input!!)))
        })
        assertEquals(AutoJsUiScripts.Result(2,2,1, finalScreenChecked = true),good); assertEquals(1,writes)
        var reads = 0
        val cancel = runCatching { AutoJsUiScripts.execute(steps, read = {
            if (++reads > 1) throw CancellationException("user stopped")
            frame
        }, apply = { _,_,_ -> writes++ }) }.exceptionOrNull()
        assertTrue(cancel is CancellationException); assertEquals(2,writes)
    }

    @Test fun repeatedLabelsAreNotClickedAndTimeoutNeverDispatches() = runBlocking {
        var now = 0L
        val click = AutoJsUiScripts.compile("""text("草稿").findOne(50).click();""")
        val ambiguous = AutoJsUiScripts.execute(click, { frame.copy(nodes=listOf(field,field.copy(id="#n3"))) }, { _,_,_ -> fail() })
        assertEquals(1,ambiguous.failedLine); assertEquals(0,ambiguous.completed)
        val timeout = AutoJsUiScripts.execute(click, { frame.copy(nodes=emptyList()) }, { _,_,_ -> fail() },
            now = { now }, pause = { now += it })
        assertEquals(50L,now); assertEquals(1,timeout.failedLine)
        val crossApp = AutoJsUiScripts.execute(click, { frame }, { _,_,_ -> },
            afterAction = { error("Foreground changed; no more steps.") })
        assertEquals(1,crossApp.completed); assertNotNull(crossApp.message)
    }

    @Test fun waitingBeforeATapDoesNotVerifyItsOutcome() = runBlocking {
        val steps = AutoJsUiScripts.compile("id('content').waitFor();\nid('content').findOne().click();")
        val result = AutoJsUiScripts.execute(steps, { frame }, { _,_,_ -> })
        assertEquals(2, result.completed)
        assertEquals(1, result.verifiedChecks)
        assertFalse(result.finalScreenChecked)
    }

    @Test fun libraryPersistsEditsDisablesAndValidatesBeforeEnabling() = runBlocking {
        val path = temp.root.resolve("scripts/catalog.json")
        val store = AutoJsScriptStore(path)
        store.save("笔记草稿",source)
        assertEquals(source,AutoJsScriptStore(path).get("笔记草稿")?.source)
        store.setEnabled("笔记草稿",false)
        assertFalse(store.get("笔记草稿")!!.enabled)
        store.save("笔记草稿","unfinished(",false)
        assertTrue(runCatching { store.setEnabled("笔记草稿",true) }.isFailure)
        assertFalse(AutoJsScriptStore(path).get("笔记草稿")!!.enabled)
        store.delete("笔记草稿")
        assertTrue(AutoJsScriptStore(path).entries.value.isEmpty())
    }
}
