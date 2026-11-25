package com.androidharness.app.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.ChatAnswerVersionEntity
import com.androidharness.app.data.db.MessageEntity
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ChatBackupImagesDeviceTest {
    private fun Context.withFiles(root: File) = object : ContextWrapper(this) {
        override fun getFilesDir(): File = root
    }

    private fun row(id: String, vararg images: String) = MessageEntity(id, "chat", "USER", id, "[]", null, null, false,
        imagesJson = images.joinToString(",", "[", "]") { """{"name":"$it","mime":"image/png"}""" },
        createdAt = 1)

    @Test fun legacyJsonStillRestoresWithoutAnAttachmentPhase() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File.createTempFile("legacy-chat-", ".json", context.cacheDir)
        try {
            val project = ProjectEntity("local", "App workspace", "APP", null, 0)
            db.dao().insertProject(project)
            val chat = BackupSession("chat", "Old chat", 1, 1, projectId = project.id,
                messages = listOf(BackupMessage("question", "USER", "Keep this", createdAt = 1)))
            file.writeText(ChatBackupCodec.encode(ChatBackupFile(sessions = listOf(chat))))
            val manager = ChatBackupManager(db, context)
            val uri = Uri.fromFile(file)
            val preview = manager.prepareImport(uri)
            assertTrue(preview.legacy)
            assertEquals(1, manager.importPrepared(preview, emptyMap()).sessions)
            assertEquals("Keep this", db.dao().messages("chat").single().text)
            assertEquals(1, manager.importPrepared(manager.prepareImport(uri), emptyMap()).skipped)
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun historyImagesRoundTripAndSkippedChatsRepairAnInterruptedRestore() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "chat-backup-").toFile()
        val from = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val into = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val source = context.withFiles(root.resolve("source"))
        val target = context.withFiles(root.resolve("target"))
        val exporter = ChatBackupManager(from, source)
        val importer = ChatBackupManager(into, target)
        val uri = Uri.fromFile(root.resolve("archive.harnessbackup"))
        try {
            val project = ProjectEntity("workspace", "App workspace", "APP", null, 0)
            from.dao().insertProject(project)
            into.dao().insertProject(project)
            val session = SessionEntity("chat", "Photo", 1, 1, projectId = project.id)
            from.dao().insertSession(session)
            from.dao().insertSession(session.copy(id = "later", createdAt = 2))
            from.dao().insertMessage(row("latest", "shared.png", "removed.png"))
            val history = Json.encodeToString(listOf(row("previous", "old.png", "shared.png", "original-only.png")))
            from.dao().insertChatAnswerVersion(ChatAnswerVersionEntity("v1", session.id, "first", history, 1))
            val entries = listOf("shared.png", "removed.png", "old.png", "originals/old.png", "originals/original-only.png")
            val incoming = source.filesDir.resolve("images")
            val restored = target.filesDir.resolve("images")
            for (name in entries) incoming.resolve(name).apply { parentFile!!.mkdirs(); writeText(name) }
            assertEquals(4, exporter.exportTo(uri).images)

            suspend fun restore() = importer.importPrepared(importer.prepareImport(uri), emptyMap())
            val result = restore()
            assertEquals(4, result.images)
            for (name in entries) assertEquals(name, restored.resolve(name).readText())
            assertEquals(history, into.dao().answerVersionsFor(session.id).single().messagesJson)

            // Interrupt after the first chat is committed and before the stream reaches any files.
            for (s in into.dao().allSessions()) SessionRepository(into).deleteSession(s)
            for (name in entries) restored.resolve(name).delete()
            try {
                importer.importPrepared(importer.prepareImport(uri), emptyMap()) {
                    if (it.done == 1) throw CancellationException("Stopped during restore")
                }
                fail("Restore was not stopped")
            } catch (_: CancellationException) {}
            assertEquals(listOf("chat"), into.dao().allSessionIds())
            assertFalse(restored.resolve("old.png").exists())

            // Local edits win, existing bytes are not replaced and deleted references stay deleted.
            into.dao().deleteMessages("chat")
            into.dao().insertMessage(row("local-edit", "shared.png"))
            restored.resolve("shared.png").writeText("local pixels")
            val repaired = restore()
            assertEquals(1, repaired.skipped)
            assertEquals(2, repaired.images)
            assertEquals("local-edit", into.dao().messages("chat").single().id)
            assertEquals("local pixels", restored.resolve("shared.png").readText())
            assertFalse(restored.resolve("removed.png").exists())
            for (name in entries.filter { "old.png" in it || "original-only" in it })
                assertEquals(name, restored.resolve(name).readText())
            assertEquals(0, restore().images)
        } finally {
            from.close()
            into.close()
            root.deleteRecursively()
        }
    }
}
