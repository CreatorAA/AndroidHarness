package com.androidharness.app.androiduse

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PhoneTapTargetsTest {
    // Opaque IDs intentionally do not encode ancestry. Only the driver's actual traversal knows it.
    private fun tap(id: String) = PhoneNode(id, "", "", false, true, 20, 180, 380, 275,
        viewClass = "android.widget.LinearLayout", resource = "sample:id/result", tapOwner = id, collection = "list")
    private fun label(id: String, text: String, owner: String) =
        PhoneNode(id, text, "", false, false, 30, 190, 370, 215, viewClass = "android.widget.TextView", tapOwner = owner)
    private val card = tap("row-a")
    private val name = label("deep-label", "气相色谱仪", card.id)
    private val description = label("deep-summary", "设备编号：紫藤甲型", card.id)
    private val other = tap("row-b")
    private val otherTitle = label("b-label", "微量天平", other.id)
    private val otherBody = label("b-summary", "设备编号：云石甲型", other.id)
    private val avatar = tap("adjacent").copy(description = "某人的头像")
    private val frame = PhoneFrame("demo", "window", 400, 820, 0, listOf(card, name, description, avatar, other, otherTitle, otherBody),
        inspectedContainers = setOf(card.id,other.id,avatar.id))
    @Test fun genericTextBelongsOnlyToItsNativeTapOwnerEvenThroughLayoutWrappers() {
        val targets = PhoneTapTargets.from(frame)
        val target = PhoneTapTargets.resolve(targets, "tap", name.id)!!
        assertEquals(name.text, target.label)
        assertEquals(card.id, target.tap.id)
        assertEquals(listOf(name,description),target.evidence)
        assertSame(target,PhoneTapTargets.resolve(targets, "tap", description.id))
        assertSame(target,PhoneTapTargets.resolve(targets, "tap", card.id))
        assertEquals(avatar.id, PhoneTapTargets.resolve(targets,"tap",avatar.id)?.tap?.id)
        assertNull(PhoneTapTargets.resolve(targets,"long_press",name.id))
        assertNull(PhoneTapTargets.resolve(targets,"tap","unknown"))
        assertTrue(PhoneTapTargets.from(frame.copy(nodes=frame.nodes.map { it.copy(tapOwner=null) })).isEmpty())
    }

    @Test fun nestedIndependentAndUnverifiedActionsRemainObservableButNotAutomaticallyPermitted() {
        val field = tap("nested-button").copy(text = "保存")
        val f = frame.copy(nodes = frame.nodes + field, inspectedContainers = setOf(field.id,other.id))
        val targets = PhoneTapTargets.from(f)
        assertEquals(card.id,PhoneTapTargets.resolve(targets,"tap",description.id)?.tap?.id)
        assertEquals(field.id,PhoneTapTargets.resolve(targets,"tap",field.id)?.tap?.id)
        assertFalse(PhoneTapTargets.resolve(targets, "tap", card.id)!!.singleAction)
        val scope = PhoneReadScope.from(targets)
        assertFalse(scope.contains(targets.single { it.tap.id == card.id }))
        assertFalse(scope.contains(targets.single { it.tap.id == field.id }))
        assertFalse(scope.contains(targets.single { it.tap.id == avatar.id }))
    }

    @Test fun readCandidatesFollowOwnershipNotQuotedWordsLanguageOrATaskTitle() {
        val f = frame.copy(nodes=frame.nodes.map { if (it == name) it.copy(text="关于删除、付款和转发的文章") else it })
        val targets = PhoneTapTargets.from(f)
        val candidates = PhoneReadScope.from(targets).candidates
        assertEquals(setOf(card.id,other.id), candidates.map { it.tap.id }.toSet())
        assertTrue(candidates.first { it.tap.id == card.id }.evidence.any { "付款" in it.text })
        val button = f.copy(nodes=f.nodes.map { if (it == card) it.copy(viewClass="android.widget.Button") else it })
        assertFalse(PhoneReadScope.from(PhoneTapTargets.from(button)).candidates.any { it.tap.id == card.id })
        val toggle = f.copy(nodes=f.nodes.map { if (it == card) it.copy(checkable=true) else it })
        assertFalse(PhoneReadScope.from(PhoneTapTargets.from(toggle)).candidates.any { it.tap.id == card.id })
    }

    @Test fun recycledActionNodeCannotUseEvidenceFromPriorPageOrOldItem() {
        val action=PhoneAction("tap",node=card.id,tapEvidenceNode=name.id)
        assertTrue(PhoneTapTargets.stillCurrent(frame,frame.copy(),action))
        for(changed in listOf(frame.copy(packageName="other"),frame.copy(windowKey="another"),
            frame.copy(inspectedContainers=emptySet()),
            frame.copy(nodes=frame.nodes.map { if(it==description) it.copy(text="另一条记录") else it }),
            frame.copy(nodes=frame.nodes.map { if(it==name) it.copy(tapOwner=other.id) else it }),
            frame.copy(nodes=frame.nodes.map { if(it==avatar) it.copy(description="另一页") else it }))) {
            assertFalse(PhoneTapTargets.stillCurrent(frame,changed,action))
        }
        assertFalse(PhoneTapTargets.stillCurrent(frame,frame,action.copy(node=avatar.id)))
        assertFalse(PhoneTapTargets.stillCurrent(frame,frame,action.copy(kind="long_press")))
    }

    @Test fun suggestionsAreNotAnAllowlistAndMissingInputsGetEvidenceForRecovery() {
        val targets=PhoneTapTargets.from(frame)
        val scope=PhoneReadScope.from(targets)
        assertTrue(scope.contains(targets.single { it.tap.id==card.id }))
        assertFalse(scope.contains(targets.single { it.tap.id==avatar.id }))
        fun node(id: String) = buildJsonObject { put("kind","tap"); put("node",id) }
        val pixel=buildJsonObject { put("kind","tap"); put("x",22); put("y",18) }
        val empty=PhoneReadScope("inspect_this_source", emptyList())
        for (s in listOf(scope,empty)) {
            assertNull(s.invalidTap(frame,node(avatar.id),false))
            assertNull(s.invalidTap(frame,node(card.id),false))
            assertEquals("unknown_observed_target",s.invalidTap(frame,node("absent"),true))
            assertEquals("current_image_required",s.invalidTap(frame,pixel,false))
            assertNull(s.invalidTap(frame,pixel,true))
        }
        val correction = scope.retry("frame-7","unknown_observed_target",2)
        assertTrue(correction.contains("frame-7") && correction.contains("not_dispatched") &&
            correction.contains(description.text) && correction.contains("\"completedSteps\":0") && !correction.contains(avatar.description))
        assertTrue(empty.retry("frame-8","unknown_observed_target").contains("OCR"))
    }

    @Test fun deliveredTextAndPaddingResolveToParentButOverlapMasksMissingFramesAndNodeRequestsNeverDo() {
        val root=PhoneBox(0,0,400,820)
        val base=tap("0.2"); val child=label("0.2.5.1","同一条资料",base.id)
        val second=label("0.2.5.2","这条资料的摘要",base.id)
        val sibling=tap("0.1").copy(description="相邻按钮",left=12,right=40)
        val areas=listOf(PhoneTouchArea("0",null,false,root),
            PhoneTouchArea(base.id,base.id,true,PhoneBox(20,180,380,275)),
            PhoneTouchArea("0.2.5",base.id,false,PhoneBox(30,190,370,260)),
            PhoneTouchArea(child.id,base.id,false,PhoneBox(30,190,370,215)),
            PhoneTouchArea(second.id,base.id,false,PhoneBox(30,220,370,260)),
            PhoneTouchArea(sibling.id,sibling.id,true,PhoneBox(12,180,40,275)))
        val f=frame.copy(nodes=listOf(base,child,second,sibling),inspectedContainers=setOf(base.id,sibling.id),touchMap=PhoneTouchMap(root,emptyList(),areas))
        val targets=PhoneTapTargets.from(f); val actual=targets.single { it.tap.id==base.id }
        val scope=PhoneReadScope("inspect_this_source",listOf(actual))
        val padding=PhonePoint(200,185); val titlePoint=PhonePoint(180,200); val summary=PhonePoint(180,227)
        for (p in listOf(padding,titlePoint,summary)) assertEquals(base.id,scope.atDeliveredPoint(f,true,p)?.tap?.id)
        assertNull(scope.atDeliveredPoint(f,true,PhonePoint(30,205))) // overlapping independent button
        assertNull(scope.atDeliveredPoint(f,true,PhonePoint(15,205))) // neighbor alone
        assertNull(scope.atDeliveredPoint(f,true,PhonePoint(380,200))) // exclusive right edge
        assertNull(scope.atDeliveredPoint(f,false,padding))
        assertNull(scope.atDeliveredPoint(f.copy(touchMap=null),true,padding))
        val masked=f.copy(touchMap=PhoneTouchMap(root,listOf(PhoneBox(194,180,214,196)),areas))
        assertNull(scope.atDeliveredPoint(masked,true,padding))
        val relayed=PhoneAction("tap",node=base.id,tapEvidenceNode=child.id,sourcePoint=padding)
        assertTrue(PhoneTapTargets.stillCurrent(f,f.copy(),relayed))
        assertFalse(PhoneTapTargets.stillCurrent(f,masked,relayed))
        assertFalse(PhoneTapTargets.stillCurrent(f,f.copy(nodes=listOf(base,child.copy(text="另一页"),second,sibling)),relayed))
        assertFalse(scope.contains(PhoneTapTargets.resolve(targets,"tap",sibling.id)!!)) // never rebind a node ID by bounds
    }
}
