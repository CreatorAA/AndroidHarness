package com.androidharness.app.assistant

import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.*
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.ImageRef
import com.androidharness.app.core.Role
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MemoryEntity
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePanelHistoryDeviceTest {
    @Test fun keepsTenByRecentUseAcrossWorkspacesWithoutRemovingSharedDataOrActiveTasks() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val files = File(context.cacheDir, "phone-retention-" + UUID.randomUUID())
        val images = ImageStore(object : ContextWrapper(context) { override fun getFilesDir() = files })
        val repo = SessionRepository(db, images)
        val dao = db.dao()
        try {
            val phone = ConversationConfig(ConversationKind.PHONE)
            for (project in listOf("one", "two")) dao.insertProject(ProjectEntity(project, project, "APP", null, 0))
            val code = repo.createSession("Source Code", "one")
            val chat = repo.createSession("Not a Phone", "two", ConversationConfig(ConversationKind.CHAT, instructions = "PHONE"))
            for (i in 0..12) {
                val config = if (i == 2) phone.copy(phoneOriginCodeId = code) else phone
                dao.insertSession(SessionEntity("p$i", "Phone $i", 10, 10,
                    projectId = if (i % 2 == 0) "one" else "two", conversationJson = ConversationConfig.encode(config)))
            }
            val privateImage = ImageRef("phone-only.jpg", "image/jpeg")
            val sharedImage = ImageRef("shared.jpg", "image/jpeg")
            for (ref in listOf(privateImage, sharedImage)) File(images.imagesDir, ref.name).writeBytes(byteArrayOf(1))
            repo.addMessage("p2", ChatMessage(role = Role.USER, text = "expired", images = listOf(privateImage, sharedImage)))
            repo.addMessage(chat, ChatMessage(role = Role.USER, text = "keep", images = listOf(sharedImage)))
            dao.upsertMemory(MemoryEntity("memory", "kept fact", sourceSessionId = "p2", createdAt = 1, updatedAt = 1))
            repeat(12) { repo.addMessage("p0", ChatMessage(role = Role.USER, text = "message $it")) }
            for (i in 0..12) dao.touchSession("p$i", 10)
            repo.openSession("p0")

            assertEquals(setOf("p2", "p3"), repo.prunePhoneHistory { it == "p1" })
            assertEquals(setOf("p1"), repo.prunePhoneHistory { false })
            assertTrue(repo.prunePhoneHistory { false }.isEmpty())
            assertEquals(setOf(code, chat, "p0") + (4..12).map { "p$it" }, dao.allSessionIds().toSet())
            assertEquals(12, repo.messages("p0").size)
            assertTrue(repo.messages("p2").isEmpty())
            assertFalse(File(images.imagesDir, privateImage.name).exists())
            assertTrue(File(images.imagesDir, sharedImage.name).isFile)
            assertEquals("kept fact", dao.memory("memory")?.text)
            assertNotNull(dao.project("one"))
        } finally { db.close(); files.deleteRecursively() }
    }

    @Test fun resumesTheChosenQuickConversationAndNeverPicksTheNewestProjectRow() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repo = SessionRepository(db)
        val settings = SettingsRepository(context)
        val project = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val first = PhonePanelHistory(context, repo, settings)
        try {
            for (id in listOf(project, other)) db.dao().insertProject(ProjectEntity(id, "fixture", "APP", null, 0))
            val phone = ConversationConfig(ConversationKind.PHONE)
            val quick = repo.createSession("Quick panel", project, phone)
            first.save(project, quick)
            repo.createSession("Manually created phone conversation", project, phone)
            val next = PhonePanelHistory(context, repo, settings)
            assertEquals(quick, next.load(project))
            assertNull(next.load(other))
            next.save(project, repo.createSession("Different project", other, phone))
            next.save(project, repo.createSession("Separate Code handoff", project, phone.copy(phoneOriginCodeId = "source")))
            assertEquals(quick, next.load(project))
            repo.deleteSession(requireNotNull(repo.session(quick)))
            assertNull(next.load(project))
        } finally {
            first.clear(project)
            db.close()
        }
    }
}
