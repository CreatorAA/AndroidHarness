package com.androidharness.app.agent

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageData
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.tools.ChatImageInventory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatImageContextTest {
    @Test fun `live phone request does not acquire earlier group pixels but keeps them addressable`() = runBlocking {
        val old = ImageRef("group-a.png", "image/png", caption = "旧群：真有啊！", origin = "android_observe")
        val prior = listOf(ChatMessage(Role.USER, "帮我看看旧群的引用"), ChatMessage(Role.TOOL, "旧群",
            toolName = "android_observe", images = listOf(old), imageData = listOf(ImageData("image/png", "old"))),
            ChatMessage(Role.ASSISTANT, "已核对旧群。"))
        for (request in listOf("帮我自动化分析一下这个人在惊讶什么", "对比刚才那张图与当前页")) {
            val latest = ChatMessage(Role.USER, request, imageData = listOf(ImageData("image/png", "this-turn")))
            val messages = prior + latest
            val live = ChatImageContext.attach(messages, livePhoneTask = true, resolve = { error("re-attached old group") }) { error("loaded history") }
            assertEquals(messages, live)
            assertEquals(ChatImageInventory.from(prior).single().id, ChatImageInventory.from(live).single().id)
        }
    }
    @Test fun `recent assistant pictures reach the follow up without mutating saved messages`() = runBlocking {
        val history = listOf(ChatMessage(Role.USER, "Show me"),
            ChatMessage(Role.ASSISTANT, "![first](https://example.com/1.png)\n![second](https://example.com/2.png)"),
            ChatMessage(Role.USER, "What color is the second?"))
        val result = ChatImageContext.attach(history) { ImageData("image/jpeg", it) }
        assertEquals(listOf("https://example.com/2.png", "https://example.com/1.png"), result.last().imageData.map { it.base64 })
        assertTrue(result.last().text.contains("pictures shown earlier"))
        assertTrue(history.last().imageData.isEmpty())
        assertEquals("What color is the second?", history.last().text)
    }

    @Test fun `user uploads take priority and failures are identified`() = runBlocking {
        val assistant = ChatMessage(Role.ASSISTANT, "![view](https://example.com/p.png)")
        val upload = ImageData("image/png", "user")
        val withUploads = listOf(assistant, ChatMessage(Role.USER, "Compare", imageData = listOf(upload, upload)))
        assertEquals(withUploads, ChatImageContext.attach(withUploads) { error("should not fetch") })
        val missing = ChatImageContext.attach(listOf(assistant, ChatMessage(Role.USER, "Describe it"))) { null }
        assertTrue(missing.last().text.contains("could not be loaded"))
        assertTrue(missing.last().imageData.isEmpty())
    }
}
