package com.androidharness.app.androiduse

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneSubmissionGuardTest {
    @Test fun aLostReplyBlocksEvenANavigationDisguisedRetry() = runBlocking {
        val guard = PhoneSubmissionGuard()
        var count = 0
        val lost = runCatching {
            guard.dispatch("submit", true) {
                count++
                throw IOException("reply lost")
            }
        }
        assertTrue(lost.exceptionOrNull() is IOException)
        assertNotNull(guard.readOnlyReason)
        guard.verified("unrelated")
        assertTrue(runCatching { guard.dispatch("new", false) { count++ } }.isFailure)
        assertEquals(1, count)
    }

    @Test fun anAcknowledgementAloneDoesNotUnlockAnotherAction() = runBlocking {
        val guard = PhoneSubmissionGuard()
        var count = 0
        guard.dispatch("send", true) { count++ }
        assertTrue(runCatching { guard.dispatch("retry", true) { count++ } }.isFailure)
        assertEquals(1, count)
        guard.verified("send")
        assertNull(guard.readOnlyReason)
        guard.dispatch("next", false) { count++ }
        assertEquals(2, count)
    }

    @Test fun aDriverRefusalBeforeDispatchLeavesTheTaskWritable() = runBlocking {
        val guard = PhoneSubmissionGuard()
        val refusal = runCatching {
            guard.dispatch("refused", true) { throw PhoneActionNotSent(IllegalStateException("stale frame")) }
        }
        assertTrue(refusal.exceptionOrNull() is PhoneActionNotSent)
        assertNull(guard.readOnlyReason)
        var sent = false
        guard.dispatch("fresh", true) { sent = true }
        assertTrue(sent)
    }

    @Test fun cancellationDuringSubmissionIsNotAReceipt() = runBlocking {
        val guard = PhoneSubmissionGuard()
        val result = runCatching {
            guard.dispatch("send", true) { throw CancellationException("response interrupted") }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertNotNull(guard.readOnlyReason)
        assertNull(PhoneSubmissionGuard().readOnlyReason)
    }

    @Test fun uncertainNavigationIsNotAnUnconfirmedSubmission() = runBlocking {
        val guard = PhoneSubmissionGuard()
        runCatching { guard.dispatch("navigate", false) { throw IOException() } }
        assertNull(guard.readOnlyReason)
    }

    private fun page(vararg labels: String, pixels: Int = 1) = PhoneFrame("app","window",100,200,0,
        labels.mapIndexed { i,label -> PhoneNode("node$i",label,"",false,true,0,i*30,90,i*30+29) },
        PhoneImage(byteArrayOf(pixels.toByte()),"image/png",100,200))

    private fun evidence(before: PhoneFrame, expected: String? = null, ordinary: Boolean = true) =
        PhoneSubmissionGuard.Screen(before, "builtin", PhoneAction("tap",node="node0"), expected, null, ordinary, true)

    @Test fun laterObservationCanVerifyTheOriginalConditionOfAnExplicitCommit() = runBlocking {
        val g = PhoneSubmissionGuard()
        val before = page("Send","Cancel")
        g.dispatch("commit",true,evidence(before,"Received",ordinary=false)) {}
        assertNull(g.observe(before,"builtin"))
        val after = page("Received","Close")
        assertNull(g.observe(after,"autojs"))
        assertNull(g.observe(after.copy(packageName="other app"),"builtin"))
        assertNull(g.continuation(after,"builtin",true,"change","the view advanced"))
        assertNotNull(g.readOnlyReason)
        assertEquals("commit",g.observe(after,"builtin"))
        assertNull(g.readOnlyReason)
    }

    @Test fun onlyNewIndependentActionableContentCanAdvanceAnImplicitSubmissionWithoutAPicture() = runBlocking {
        val g = PhoneSubmissionGuard()
        val before = page("East","West","Back")
        g.dispatch("choose",true,evidence(before)) {}
        assertNull(g.continuation(page("East","South","Back",pixels=2),"builtin",true,"change","The clock changed.","node0"))
        assertNull(g.continuation(page("East","South","Back"),"builtin",false,null,null,"node1")) // prior input still available
        assertNull(g.continuation(page("Selected","West","Back"),"builtin",false,null,null,"node0")) // only button state changed
        val after = page("North","South","Back")  // same activity, control ids and bounds
        assertNull(g.continuation(after,"builtin",false,null,null,"node2")) // shared Back control is not evidence of a new step
        val permit = g.continuation(after,"builtin",false,null,null,"node0")
        assertEquals("choose",permit)
        assertNotNull(g.readOnlyReason) // inspecting the new step never verified delivery to an external system
        assertTrue(runCatching { g.dispatch("retry",true) {} }.isFailure)
        g.dispatch("next",false,continuation=permit) {}
        assertNull(g.readOnlyReason)
    }

    @Test fun singleControlStepsCanReuseTheSameSlotWithoutAnyScreenshot() = runBlocking {
        val before = page("Start draft")
        val next = page("Review draft")
        val g = PhoneSubmissionGuard()
        g.dispatch("old",true,evidence(before)) {}
        assertNull(g.continuation(before,"builtin",false,"change","ready","node0"))
        assertEquals("old",g.continuation(next,"builtin",false,"change","The new action is Review draft.","node0"))
        val oldTitle = before.nodes.first().copy(id="heading",text="First stage",clickable=false)
        val first = before.copy(nodes=before.nodes + oldTitle)
        val second = next.copy(nodes=next.nodes + oldTitle.copy(text="Second stage"))
        val h = PhoneSubmissionGuard()
        h.dispatch("first",true,evidence(first)) {}
        assertEquals("first",h.continuation(second,"builtin",false,null,null,"node0"))
        val sameButton = before.copy(nodes=before.nodes + oldTitle.copy(text="Shipping address"))
        assertNull(h.continuation(sameButton,"builtin",false,"change","It changed","node0"))
        assertEquals("first",h.continuation(sameButton,"builtin",false,"change","Now it asks for Shipping address.","node0"))
    }

    @Test fun changedPixelsRequireAnExplicitOrdinaryInputFromADeliveredNewScene() = runBlocking {
        val g = PhoneSubmissionGuard()
        val before = page("Canvas").let { it.copy(nodes=it.nodes.map { n -> n.copy(clickable=false) }) }
        val after = before.copy(image=page("Canvas",pixels=2).image)
        g.dispatch("old",true,evidence(before)) {}
        for (effect in listOf(null,"purchase","save","send","delete","share")) {
            assertNull(g.continuation(after,"builtin",true,effect,"a different scene"))
        }
        assertNull(g.continuation(after,"builtin",true,"change",null))
        assertNull(g.continuation(before,"builtin",true,"change","same screen"))
        assertNull(g.continuation(after,"builtin",false,"change","not yet delivered"))
        assertNull(g.continuation(after.copy(windowKey="other"),"builtin",true,"change","unrelated window"))
        assertNull(g.continuation(after,"autojs",true,"change","unrelated driver"))
        val permit = g.continuation(after,"builtin",true,"change","new scene with a different target")
        assertEquals("old",permit)
        val refused = runCatching { g.dispatch("not-sent",false,continuation=permit) { throw PhoneActionNotSent(IllegalStateException()) } }
        assertTrue(refused.exceptionOrNull() is PhoneActionNotSent)
        assertNotNull(g.readOnlyReason)
        g.dispatch("different",false,continuation=permit) {}
        assertNull(g.readOnlyReason)
    }

    @Test fun anUnknownDriverOutcomeCannotBeReleasedByAnUnrelatedChangedScene() = runBlocking {
        val g = PhoneSubmissionGuard()
        runCatching { g.dispatch("tap",true,evidence(page("First","Second"))) { throw IOException("lost") } }
        val after = page("Third","Fourth",pixels=2)
        assertNull(g.continuation(after,"builtin",true,"change","controls changed"))
        assertNull(g.observe(after,"builtin"))
        assertNotNull(g.readOnlyReason)
    }
}
