package com.androidharness.app.androiduse

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagedPhoneTaskTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun task(text: String) = ManagedPhoneTask().apply { bind(obj(text)) }
    private val inspect = """{"access":"inspect","apps":[],"capture":false}"""
    private val operate = """{"access":"operate"}"""
    private fun denied(block: () -> Unit) { assertTrue("Policy should refuse this operation",runCatching(block).isFailure) }
    private val button = PhoneNode("b","任何标题","",false,true,0,0,50,25,viewClass="android.widget.Button")

    @Test fun firstCallBindsBeforeReadingAndNeitherScreensNorRepliesCanExpandIt() {
        val task = ManagedPhoneTask()
        denied { task.bind(null) }
        denied { task.requireLive() }
        task.bind(obj(inspect)); task.bind(null)
        assertFalse(task.permitsVisualRead())
        denied { task.bind(obj(operate)) }
        denied { task.requireApp("current","other",listOf("其他应用")) }
        task.humanReplied()
        denied { task.requireLive() }
        denied { task.bind(obj("""{"access":"inspect","apps":[]}""")) }
        task.bind(obj(inspect))
        task.bind(obj("""{"access":"snapshot","apps":[],"capture":false}"""))
        denied { task.requireLive() }
    }

    @Test fun normalDelegationDoesNotRequireEnumeratingStepsAppsOrSubmissions() {
        val t = task(operate)
        t.requireApp("one","another",listOf("从未出现过的应用"))
        for (effect in ManagedPhoneTask.effects) t.requireEffect(effect)
        ManagedPhoneActionPolicy.screen(t,"tap",null,false,button,false)
        ManagedPhoneActionPolicy.native(t,null,null,false)
        ManagedPhoneActionPolicy.script(t, AutoJsUiScripts.compile("""text("OK").findOne(300).click();"""))
    }

    @Test fun aDraftCanBeEditedAndSavedWithoutSendingAndCanFurtherRestrictItsDestination() {
        val t = task("""{"access":"operate","denyEffects":["send"],"apps":["地图"]}""")
        t.requireApp("one","maps.pkg",listOf("地图")); t.requireApp("one","one",emptyList())
        denied { t.requireApp("one","never.allowed",listOf("地图助手")) }
        ManagedPhoneActionPolicy.screen(t,"set_text",null,false,null,false)
        ManagedPhoneActionPolicy.screen(t,"tap","save",true,button,false)
        denied { ManagedPhoneActionPolicy.screen(t,"tap","send",true,button,false) }
        t.humanReplied()
        t.bind(obj("""{"access":"operate","effects":["edit"],"apps":[]}"""))
        denied { t.requireApp("one","maps.pkg",listOf("地图")) }
        denied { ManagedPhoneActionPolicy.screen(t,"tap","save",true,button,false) }
    }

    @Test fun inspectionCanNavigateButCannotUseAKnownWriteOrAnOpaqueAction() {
        val t = task(inspect)
        for (kind in listOf("scroll_forward","scroll_backward","back")) ManagedPhoneActionPolicy.screen(t,kind,null,false,null,false)
        ManagedPhoneActionPolicy.screen(t,"tap","navigate",false,button.copy(viewClass="android.widget.ImageView"),true)
        for (label in listOf("删除","查看内容","see notes")) denied {
            ManagedPhoneActionPolicy.screen(t,"tap","navigate",false,button.copy(text=label),false)
        }
        for (kind in listOf("set_text","drag","long_press","media_volume","share_text","open_link","swipe"))
            denied { ManagedPhoneActionPolicy.screen(t,kind,"navigate",false,button,false) }
        denied { ManagedPhoneActionPolicy.screen(t,"tap",null,true,button,true) }
        denied { ManagedPhoneActionPolicy.screen(t,"tap",null,false,button.copy(checkable=true),true) }
        for (id in listOf("alarm_set","timer_set","calendar_draft","note_draft",null))
            denied { ManagedPhoneActionPolicy.native(t,id,"navigate",false) }
        ManagedPhoneActionPolicy.native(t,"map_search","navigate",false)
        ManagedPhoneActionPolicy.script(t,AutoJsUiScripts.compile("""text("删除").waitFor();"""))
        for (step in listOf("""id("input").findOne(2000).setText("write");""","""text("资料").findOne(300).click();"""))
            denied { ManagedPhoneActionPolicy.script(t,AutoJsUiScripts.compile(step)) }
    }

    @Test fun malformedAndInconsistentScopesDoNotGrantAnyAccess() {
        val t = ManagedPhoneTask()
        for (s in listOf("""{"access":"sometimes"}""","""{"access":"inspect","effects":["delete"]}""",
            """{"access":"operate","effects":["all"]}""","""{"access":"inspect","capture":"false"}""",
            """{"access":"operate","apps":[1]}""","""{"access":"operate","override":true}""")) {
            denied { t.bind(obj(s)) }
            denied { t.requireLive() }
        }
    }
}
