package com.healthtimeline.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentImportCancellationTest {
    private lateinit var fixture: File
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var member: FamilyMemberEntity
    private lateinit var record: ClinicalRecordEntity
    private lateinit var source: File

    @Before fun setUp(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        fixture = File(base.cacheDir, "attachment-cancellation-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val files = File(fixture, "files").apply { check(mkdirs()) }
        val cache = File(fixture, "cache").apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        member = repository.ensureDefaultMember()
        val now = Instant.now().toString()
        val recordId = repository.saveRecord(ClinicalRecordEntity(
            memberId = member.id, recordDate = "2026-10-11", title = "虚构取消边界报告", createdAt = now, updatedAt = now
        ))
        record = requireNotNull(database.clinicalRecordDao().byId(recordId))
        source = File(cache, "synthetic-report.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.GREEN)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @After fun tearDown() {
        database.close()
        fixture.deleteRecursively()
    }

    @Test fun legacyCompensationControlReproducesMissingFileAfterRealRoomCommit() = runBlocking {
        val caller = CompletableDeferred<Job>()
        val realDao = database.attachmentDao()
        var confirmedCommittedId: Long? = null
        val cancellingDao = object : AttachmentDao by realDao {
            override suspend fun insert(value: AttachmentEntity): Long {
                val id = realDao.insert(value)
                assertEquals(value.uuid, realDao.byId(id)?.uuid)
                confirmedCommittedId = id
                caller.await().cancel(CancellationException("controlled legacy post-commit cancellation"))
                currentCoroutineContext().ensureActive()
                return id
            }
        }
        var cancellationObserved = false
        coroutineScope {
            val task = launch {
                caller.complete(currentCoroutineContext().job)
                try {
                    legacyMovedFileCommit(cancellingDao)
                } catch (_: CancellationException) {
                    cancellationObserved = true
                }
            }
            task.join()
        }

        assertTrue(cancellationObserved)
        assertNotNull(confirmedCommittedId)
        val row = realDao.all().single()
        assertEquals(confirmedCommittedId, row.id)
        assertFalse("The exact old compensation catch deletes a file whose real Room row already committed",
            AttachmentStore(context, realDao).file(row).exists())
        assertTrue(attachmentFiles().isEmpty())
        assertTrue(source.isFile)
    }

    @Test fun cancellationAfterRealRoomCommitPreservesBothRowAndFile() = runBlocking {
        val caller = CompletableDeferred<Job>()
        val realDao = database.attachmentDao()
        var confirmedCommittedId: Long? = null
        // The wrapper controls cancellation timing, but the insert and verification use real Room.
        val cancellingDao = object : AttachmentDao by realDao {
            override suspend fun insert(value: AttachmentEntity): Long {
                val id = realDao.insert(value)
                assertEquals(value.uuid, realDao.byId(id)?.uuid)
                confirmedCommittedId = id
                caller.await().cancel(CancellationException("controlled cancellation after the real insert committed"))
                currentCoroutineContext().ensureActive()
                return id
            }
        }
        val store = AttachmentStore(context, cancellingDao)
        val repository = HealthRepository(database, store)
        val target = repository.captureAttachmentTarget(record, member.uuid)
        var cancellationObserved = false
        var resultReturned = false
        coroutineScope {
            val task = launch {
                caller.complete(currentCoroutineContext().job)
                try {
                    repository.importAttachment(target, Uri.fromFile(source))
                    resultReturned = true
                } catch (_: CancellationException) {
                    cancellationObserved = true
                }
            }
            task.join()
        }

        assertNotNull("The real Room insert must commit before this test injects cancellation", confirmedCommittedId)
        assertTrue("Cancellation must still reach the importing caller", cancellationObserved)
        assertFalse("A cancelled caller must not receive a successful result", resultReturned)
        val rows = realDao.all()
        assertEquals(1, rows.size)
        val imported = rows.single()
        assertEquals(confirmedCommittedId, imported.id)
        assertEquals(record.id, imported.recordId)
        assertTrue("A committed attachment row must retain its file after cancellation", store.verifyStored(imported))
        assertArrayEquals(source.readBytes(), store.file(imported).readBytes())
        assertTrue(attachmentFiles().none { it.name.endsWith(".partial") })
    }

    @Test fun failureBeforeRoomCommitDeletesTheMovedFileAndDoesNotCreateARow() = runBlocking {
        val realDao = database.attachmentDao()
        var movedFileExisted = false
        val rejectingDao = object : AttachmentDao by realDao {
            override suspend fun insert(value: AttachmentEntity): Long {
                movedFileExisted = File(context.filesDir, value.relativePath).isFile
                throw IllegalStateException("controlled failure before insert")
            }
        }
        val store = AttachmentStore(context, rejectingDao)
        val repository = HealthRepository(database, store)
        val target = repository.captureAttachmentTarget(record, member.uuid)
        val result = repository.importAttachment(target, Uri.fromFile(source))

        assertTrue(movedFileExisted)
        assertTrue(result.isFailure)
        assertEquals("controlled failure before insert", result.exceptionOrNull()?.message)
        assertTrue(realDao.all().isEmpty())
        assertTrue(attachmentFiles().isEmpty())
        assertTrue(source.isFile)
        assertEquals(record, database.clinicalRecordDao().byId(record.id))
    }

    @Test fun normalImportStillStoresExactlyOneValidatedAttachment() = runBlocking {
        val store = AttachmentStore(context, database.attachmentDao())
        val repository = HealthRepository(database, store)
        val target = repository.captureAttachmentTarget(record, member.uuid)
        val imported = repository.importAttachment(target, Uri.fromFile(source)).getOrThrow()

        assertEquals(listOf(imported), database.attachmentDao().all())
        assertTrue(store.verifyStored(imported))
        assertArrayEquals(source.readBytes(), store.file(imported).readBytes())
        assertEquals(listOf(store.file(imported)), attachmentFiles())
    }

    @Test fun cancellationDuringTheSecondCopyReadStopsBeforeWritingTheCancelledChunk() = runBlocking {
        val original = ByteArray(DEFAULT_BUFFER_SIZE * 3) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        var reads = 0
        var cancellationObserved = false
        var copyReturned = false
        coroutineScope {
            val task = launch {
                val copyJob = currentCoroutineContext().job
                val input = object : ByteArrayInputStream(original) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        val count = super.read(buffer, offset, length)
                        if (++reads == 2) copyJob.cancel(CancellationException("controlled cancellation during copy"))
                        return count
                    }
                }
                try {
                    input.use { copyAttachmentBytes(it, output, digest) }
                    copyReturned = true
                } catch (_: CancellationException) {
                    cancellationObserved = true
                }
            }
            task.join()
        }

        assertTrue(cancellationObserved)
        assertFalse(copyReturned)
        assertEquals(2, reads)
        assertArrayEquals(original.copyOf(DEFAULT_BUFFER_SIZE), output.toByteArray())
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()), digest.digest())
        assertTrue(database.attachmentDao().all().isEmpty())
        assertTrue(attachmentFiles().isEmpty())
    }

    // Test-only control: real copy/rename followed by the old DAO + unconditional compensation
    // body. It is not an alternative production importer and does not simulate Room's insert.
    private suspend fun legacyMovedFileCommit(dao: AttachmentDao): AttachmentEntity = withContext(Dispatchers.IO) {
        val recordDir = File(context.filesDir, "attachments/${record.id}").apply { check(mkdirs()) }
        val destination = File(recordDir, "legacy-control-${UUID.randomUUID()}.png")
        val partial = File(recordDir, "${destination.name}.partial")
        var moved = false
        try {
            source.inputStream().use { input ->
                FileOutputStream(partial).use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(partial.renameTo(destination))
            moved = true
            val entity = AttachmentEntity(
                recordId = record.id, kind = AttachmentKind.IMAGE.name, displayName = source.name,
                mimeType = "image/png", relativePath = destination.relativeTo(context.filesDir).invariantSeparatorsPath,
                sizeBytes = destination.length(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) },
                createdAt = Instant.now().toString()
            )
            val id = dao.insert(entity)
            entity.copy(id = id)
        } catch (error: Throwable) {
            if (moved) destination.delete()
            throw error
        } finally {
            partial.delete()
        }
    }

    private fun attachmentFiles(): List<File> =
        File(context.filesDir, "attachments").takeIf { it.isDirectory }
            ?.walkTopDown()?.filter { it.isFile }?.toList().orEmpty()
}
