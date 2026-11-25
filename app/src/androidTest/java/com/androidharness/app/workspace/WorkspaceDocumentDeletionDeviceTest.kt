package com.androidharness.app.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceDocumentDeletionDeviceTest {
    @Test fun deletingFolderRemovesLinksWithoutTouchingTheirTargets() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val temp = Files.createTempDirectory(cache, "saf-delete-").toFile()
        val target = temp.resolve("other/keep.txt")
        val selected = temp.resolve("selected")
        try {
            target.parentFile!!.mkdirs()
            target.writeText("untouched")
            selected.mkdir()
            Files.createSymbolicLink(selected.resolve("link").toPath(), target.parentFile!!.toPath())
            WorkspaceDocumentsProvider.deleteTree(selected)
            assertFalse(selected.exists())
            assertEquals("untouched", target.readText())
        } finally {
            WorkspaceDocumentsProvider.deleteTree(temp)
        }
    }
}
