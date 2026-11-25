package com.androidharness.app.data

import com.androidharness.app.data.db.SessionEntity
import org.junit.Assert.*
import org.junit.Test

class PhoneConversationTest {
    private val phone = ConversationConfig(ConversationKind.PHONE)

    @Test fun persistedPhoneKeepsItsOwnKindWithoutWorkspaceOrDeviceAccess() {
        val restored = ConversationConfig.decode(ConversationConfig.encode(phone))
        assertTrue(restored.isPhone && restored.usesChatRuntime)
        assertEquals(com.androidharness.app.core.ChatExperience.tools, restored.allowedTools)
        assertFalse(restored.allowedTools!!.any { it in setOf("shell", "write_file", "android_action", "android_observe") })
        assertFalse(ConversationConfig.decode(null).usesChatRuntime)
        assertFalse(ConversationConfig.decode("""{"kind":"CHAT"}""").isPhone)
        assertEquals(ConversationKind.CHAT, ConversationConfig.decode("""{"kind":"UNKNOWN"}""").kind)
    }

    @Test fun quickPanelCanResumeOnlyItsOwnAvailableWorkspaceAndNoCodeHandoff() {
        val row = SessionEntity("p", "title", 1, 2, projectId = "local", conversationJson = ConversationConfig.encode(phone))
        assertTrue(PhonePanelHistory.canResume(row, "local", emptySet()))
        assertFalse(PhonePanelHistory.canResume(null, "local", emptySet()))
        assertFalse(PhonePanelHistory.canResume(row, "other", emptySet()))
        assertFalse(PhonePanelHistory.canResume(row, "local", setOf("p")))
        for (config in listOf(ConversationConfig(), ConversationConfig(ConversationKind.CHAT), phone.copy(phoneOriginCodeId = "code")))
            assertFalse(PhonePanelHistory.canResume(row.copy(conversationJson = ConversationConfig.encode(config)), "local", emptySet()))
    }

    @Test fun phoneSectionResumesOnlyPhoneAndHistoryPermissionsNeverLeakScreenContent() {
        val settings = AppSettings(startScreen = StartScreen.PHONE, lastActiveSessionId = "p", chatSearchCodeChats = true, codeSearchChatChats = true)
        assertEquals(ConversationStartup(ConversationKind.PHONE, "p"), ConversationStartup.resolve(settings, ConversationKind.PHONE))
        assertNull(ConversationStartup.resolve(settings, ConversationKind.CHAT).sessionId)
        assertNull(ConversationStartup.resolve(settings.copy(archivedSessions = setOf("p")), ConversationKind.PHONE).sessionId)
        assertTrue(ChatHistoryPolicy.canRead(phone, phone, settings))
        for (other in listOf(ConversationConfig(), ConversationConfig(ConversationKind.CHAT))) {
            assertFalse(ChatHistoryPolicy.canRead(other, phone, settings))
            assertFalse(ChatHistoryPolicy.canRead(phone, other, settings))
        }
    }
}
