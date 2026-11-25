package com.androidharness.app.assistant

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantBodyHeightDeviceTest {
    @Test fun replyAndActionsExpandWithoutDraggingAndOnlyOverflowScrolls() {
        val replyAndActions = mutableStateOf(0.dp)
        val limit = mutableStateOf(240.dp)
        val manual = mutableStateOf<Dp?>(null)
        val measured = AtomicInteger(-1)
        val scroll = ScrollState(0)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { host ->
            var density = 1f
            host.onActivity { activity ->
                density = activity.resources.displayMetrics.density
                activity.setContent {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().onSizeChanged { measured.set(it.height) }
                            .assistantBodyHeight(limit.value, manual.value, reduceMotion = false)) {
                            Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                                Spacer(Modifier.height(56.dp))
                                Spacer(Modifier.height(replyAndActions.value))
                            }
                        }
                    }
                }
            }
            fun expect(dp: Int, canScroll: Boolean) {
                val px = (dp * density).roundToInt()
                val until = SystemClock.uptimeMillis() + 5_000
                fun matches() = abs(measured.get() - px) <= 1 && scroll.maxValue != Int.MAX_VALUE &&
                    (scroll.maxValue > 0) == canScroll
                while (!matches() && SystemClock.uptimeMillis() < until) SystemClock.sleep(16)
                assertTrue("Expected ${px}px, scroll=$canScroll; got ${measured.get()}px, range=${scroll.maxValue}", matches())
            }
            expect(56, false)
            host.onActivity { replyAndActions.value = 124.dp }
            expect(180, false) // question, reply and confirmation together, with no scroll
            host.onActivity { replyAndActions.value = 370.dp }
            expect(240, true) // a long streamed answer scrolls inside the cap
            host.onActivity { limit.value = 190.dp }
            expect(190, true) // keyboard leaves less room
            host.onActivity { manual.value = 88.dp; replyAndActions.value = 100.dp }
            expect(88, true)
            host.onActivity { manual.value = null }
            expect(156, false) // next question returns to content sizing
        }
    }
}
