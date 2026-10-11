package com.healthtimeline.app.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CameraCaptureStoreTest {
    private val base = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = File(base.cacheDir, "camera-audit-${UUID.randomUUID()}").apply { check(mkdirs()) }
    private val context = object : ContextWrapper(base) { override fun getCacheDir(): File = fixture }
    private val store = CameraCaptureStore(context)
    private fun capture(name: String = "report-${UUID.randomUUID()}.jpg"): Pair<File, Uri> {
        val directory = File(fixture, "camera").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, name).apply { writeText("synthetic photo bytes") }
        return file to Uri.Builder().scheme("content").authority("${base.packageName}.files").appendPath("camera").appendPath(name).build()
    }

    @After fun cleanup() { fixture.deleteRecursively() }

    @Test fun completedImportRemovesOnlyTheTemporaryCopyAfterImportFinishes() {
        val (file, uri) = capture()
        val job = Job()
        store.discardAfter(job, uri)
        assertTrue(file.exists())
        val imported = File(fixture, "kept-attachment.jpg")
        file.copyTo(imported)
        job.complete()
        assertFalse(file.exists())
        assertEquals("synthetic photo bytes", imported.readText())
    }

    @Test fun cancelledOrFailedImportAlsoCleansItsTemporaryCapture() {
        val (cancelled, cancelUri) = capture()
        val cancelJob = Job()
        store.discardAfter(cancelJob, cancelUri)
        cancelJob.cancel()
        assertFalse(cancelled.exists())
        val (failed, failedUri) = capture()
        val failedJob = Job()
        store.discardAfter(failedJob, failedUri)
        failedJob.completeExceptionally(IllegalStateException("synthetic import failure"))
        assertFalse(failed.exists())
    }

    @Test fun interruptedCleanupLeavesRecentCapturesAndUnrelatedFilesAlone() {
        val (stale, _) = capture()
        val (recent, _) = capture()
        val unrelated = File(stale.parentFile, "important.jpg").apply { writeText("keep") }
        val now = System.currentTimeMillis()
        assertTrue(stale.setLastModified(now - CameraCaptureStore.STALE_AFTER_MS - 1000))
        assertTrue(unrelated.setLastModified(now - CameraCaptureStore.STALE_AFTER_MS - 1000))
        assertEquals(1, store.cleanupInterrupted(now))
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun unrelatedUrisTraversalAndRepeatedCleanupCannotDeleteOtherFiles() {
        val (file, uri) = capture()
        val other = File(fixture, "important.jpg").apply { writeText("keep") }
        assertFalse(store.discard(Uri.parse("content://different-provider/camera/${file.name}")))
        assertFalse(store.discard(Uri.parse("content://${base.packageName}.files/camera/..%2Fimportant.jpg")))
        assertTrue(other.exists())
        assertTrue(file.exists())
        assertTrue(store.discard(uri))
        assertTrue(store.discard(uri))
    }

    @Test fun productionCaptureUsesTheConfiguredPrivateCameraProvider() {
        val production = CameraCaptureStore(base)
        val uri = production.createUri()
        assertEquals("${base.packageName}.files", uri.authority)
        assertEquals("camera", uri.pathSegments.first())
        assertTrue(production.discard(uri))
    }
}
