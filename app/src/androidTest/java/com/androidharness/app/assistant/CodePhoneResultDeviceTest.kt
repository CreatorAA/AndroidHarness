package com.androidharness.app.assistant

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.ConversationConfig
import com.androidharness.app.data.ConversationKind
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodePhoneResultDeviceTest {
    @Test fun resultOpensOnlyTheActualPhoneChatForItsCodeOrigin() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repo = SessionRepository(db)
            val a = repo.createSession("A")
            val b = repo.createSession("B")
            assertNull(repo.latestPhoneTask(a))
            val accepted = repo.createSession("Confirmed A", conversation = ConversationConfig(ConversationKind.CHAT, phoneOriginCodeId = a))
            val other = repo.createSession("Confirmed B", conversation = ConversationConfig(ConversationKind.CHAT, phoneOriginCodeId = b))
            repo.createSession("Ordinary chat", conversation = ConversationConfig(ConversationKind.CHAT))
            assertEquals(accepted, repo.latestPhoneTask(a))
            assertEquals(other, repo.latestPhoneTask(b))
            assertNull(repo.latestPhoneTask(accepted))
        } finally { db.close() }
    }
}
