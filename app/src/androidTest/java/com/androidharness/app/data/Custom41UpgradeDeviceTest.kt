package com.androidharness.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MemoryFolderEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Custom41UpgradeDeviceTest {
    @Test fun folderOnlyArchiveKeepsFolderNameAndAgentLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val from = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val into = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val archive = java.io.File.createTempFile("memory-folders-", ".harnessbackup", context.cacheDir)
        try {
            val folder = MemoryFolderEntity("Lore", 13, "guide")
            from.dao().insertMemoryFolder(folder)
            val uri = Uri.fromFile(archive)
            ChatBackupManager(from, context).exportTo(uri)
            val restore = ChatBackupManager(into, context)
            val preview = restore.prepareImport(uri)
            assertEquals(0, preview.memories)
            assertTrue(preview.hasExtras)
            restore.importPrepared(preview, emptyMap(), extras = false)
            assertTrue(into.dao().memoryFolders().isEmpty())
            restore.importPrepared(preview, emptyMap())
            assertEquals(listOf(folder), into.dao().memoryFolders())
        } finally {
            from.close()
            into.close()
            archive.delete()
        }
        Unit
    }

    @Test fun upgradeFromCustom27KeepsMemoryAndPhoneHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "custom-upgrade-${System.nanoTime()}.db"
        try {
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                instrumentation.context.assets.open("database/custom-27.sql").bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.startsWith("--") }.forEach { old.execSQL(it) }
                }
                for (i in 0..11) {
                    val kind = if (i == 0) "CODE" else "PHONE"
                    old.execSQL("""INSERT INTO sessions(id,title,createdAt,updatedAt,totalInputTokens,totalOutputTokens,
                        totalCachedTokens,totalCacheWriteTokens,requestCount,lastInputTokens,compactionSummary,
                        compactionBefore,conversationJson) VALUES(?,?,?,?,0,0,0,0,0,0,'',0,?)""",
                        arrayOf<Any>("s$i", "original $i", i, i, """{"kind":"$kind"}"""))
                }
                old.execSQL("""INSERT INTO memories(id,text,category,scope,pinned,enabled,createdAt,updatedAt,lastUsedAt,useCount)
                    VALUES('m','retained original','FACT','GLOBAL',1,1,10,20,20,2)""")
                old.version = 27
            }
            val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_27_28, AppDatabase.MIGRATION_28_29).build()
            try {
                val dao = db.dao()
                val memory = dao.allMemories().single()
                assertEquals("retained original", memory.text)
                assertTrue(memory.pinned)
                assertNull(memory.folder)
                assertEquals("", memory.keywords)
                assertFalse(memory.triggerOnly)
                assertTrue(dao.memoryFolders().isEmpty())
                assertEquals(12, dao.allSessions().size)
                assertTrue(ConversationConfig.decode(dao.session("s11")!!.conversationJson).isPhone)
                assertEquals(setOf("s1"), SessionRepository(db).prunePhoneHistory { false })
                assertEquals((2..11).map { "s$it" }.toSet() + "s0", dao.allSessions().map { it.id }.toSet())
                assertEquals(memory, dao.allMemories().single())
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
