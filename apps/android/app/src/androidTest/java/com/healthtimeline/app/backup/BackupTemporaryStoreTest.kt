package com.healthtimeline.app.backup

import android.content.Context
import android.content.ContextWrapper
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupTemporaryStoreTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = File(base.cacheDir, "backup-cleanup-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val cache = File(fixture, "cache").apply { check(mkdirs()) }
    private val files = File(fixture, "files").apply { check(mkdirs()) }
    private val safety = File(files, "safety_backups").apply { check(mkdirs()) }
    private val context = object : ContextWrapper(base) {
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
    }
    private val store = BackupTemporaryStore(context)

    @After fun cleanupFixture() {
        // NIO's default traversal removes test symlinks themselves without following their targets.
        Files.walkFileTree(fixture.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun staging(prefix: String = "portable-restore-"): File =
        File(cache, prefix + UUID.randomUUID()).apply {
            check(mkdirs())
            File(this, "reports").apply { check(mkdirs()); File(this, "synthetic-report.pdf").writeText("test bytes") }
            File(this, "manifest.json").writeText("{}")
        }

    @Test fun snapshotRemovesOnlyOldWorkAndLeavesNewTasksAlone() {
        val oldDirectory = staging()
        val oldZip = File(cache, "health-v5-123.zip").apply { writeText("old plaintext") }
        val oldEncrypted = File(cache, "health-backup-456.encrypted").apply { writeText("old encrypted") }
        val oldSafety = File(safety, ".before-replace-789.tmp").apply { writeText("old partial") }
        val snapshot = store.snapshot()
        val newDirectory = staging()
        val newZip = File(cache, "health-v5-124.zip").apply { writeText("current task") }
        val newSafety = File(safety, ".before-replace-790.tmp").apply { writeText("current safety export") }

        assertEquals(4, store.cleanup(snapshot))
        assertFalse(oldDirectory.exists())
        assertFalse(oldZip.exists())
        assertFalse(oldEncrypted.exists())
        assertFalse(oldSafety.exists())
        assertTrue(newDirectory.exists())
        assertTrue(newZip.exists())
        assertTrue(newSafety.exists())
        assertEquals(0, store.cleanup(snapshot))
    }

    @Test fun exactNamesAreRequiredAndFormalBackupsAndAttachmentsRemain() {
        val oldRestore = staging("restore-")
        val oldImport = staging("import-new-")
        val oldFiles = listOf("health-restore-1.zip", "health-import-new-2.zip", "safety-3.zip").map {
            File(cache, it).apply { writeText("temporary") }
        }
        val kept = listOf(
            File(cache, "restored_attachments").apply { check(mkdirs()); File(this, "report.pdf").writeText("keep") },
            File(cache, "portable-restore-not-a-uuid").apply { check(mkdirs()) },
            File(cache, "restore-${UUID.randomUUID()}-extra").apply { check(mkdirs()) },
            File(cache, "health-v5-not-generated.zip").apply { writeText("keep") },
            File(cache, "health-v5-4.htbackup").apply { writeText("formal backup") },
            File(cache, "unrelated.zip").apply { writeText("keep") },
            File(safety, "before-replace-5.htbackup").apply { writeText("formal safety backup") },
            File(safety, ".before-replace-not-a-stamp.tmp").apply { writeText("keep") },
            File(files, ".before-replace-6.tmp").apply { writeText("outside approved directory") }
        )
        assertEquals(5, store.cleanup(store.snapshot()))
        assertFalse(oldRestore.exists())
        assertFalse(oldImport.exists())
        assertTrue(oldFiles.none { it.exists() })
        assertTrue(kept.all { it.exists() })
        assertEquals("keep", File(kept.first(), "report.pdf").readText())
    }

    @Test fun replacedOrModifiedCapturedPathsAreNotCurrentCleanupTargets() {
        val replaced = File(cache, "health-v5-10.zip").apply { writeText("old") }
        val changedDirectory = staging()
        val snapshot = store.snapshot()
        assertTrue(replaced.delete())
        replaced.writeText("a new task now owns this name")
        File(changedDirectory, "new-work.zip").writeText("new task")
        assertTrue(changedDirectory.setLastModified(System.currentTimeMillis() + 5000))
        assertEquals(0, store.cleanup(snapshot))
        assertEquals("a new task now owns this name", replaced.readText())
        assertTrue(File(changedDirectory, "new-work.zip").exists())
    }

    @Test fun symlinkCandidatesAndNestedSymlinksNeverDeleteOutsideTargets() {
        val outside = File(fixture, "outside").apply { check(mkdirs()) }
        val protected = File(outside, "important.pdf").apply { writeText("do not delete") }
        val linkedDirectory = File(cache, "restore-${UUID.randomUUID()}")
        Os.symlink(outside.absolutePath, linkedDirectory.absolutePath)
        val linkedFile = File(cache, "health-v5-20.zip")
        Os.symlink(protected.absolutePath, linkedFile.absolutePath)
        val oldDirectory = staging()
        val nested = File(oldDirectory, "outside-link")
        Os.symlink(outside.absolutePath, nested.absolutePath)
        store.cleanup(store.snapshot())
        assertEquals("do not delete", protected.readText())
        assertTrue(Files.isSymbolicLink(linkedDirectory.toPath()))
        assertTrue(Files.isSymbolicLink(linkedFile.toPath()))
        assertTrue(Files.isSymbolicLink(nested.toPath()))
    }

    @Test fun replacingCapturedDirectoryWithOutsideSymlinkCannotCrossTheBoundary() {
        val oldDirectory = staging()
        val snapshot = store.snapshot()
        assertTrue(oldDirectory.deleteRecursively())
        val outside = File(fixture, "outside").apply { check(mkdirs()) }
        val protected = File(outside, "important.pdf").apply { writeText("keep") }
        Os.symlink(outside.absolutePath, oldDirectory.absolutePath)
        assertEquals(0, store.cleanup(snapshot))
        assertEquals("keep", protected.readText())
        assertTrue(Files.isSymbolicLink(oldDirectory.toPath()))
    }
}
