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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AttachmentImportIdentityTest {
    private lateinit var fixture: File
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository
    private lateinit var member: FamilyMemberEntity
    private lateinit var record: ClinicalRecordEntity
    private lateinit var source: File
    private val unreadableUri = Uri.parse("content://invalid-audit-provider/report.png")

    @Before fun setUp(): Unit = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        fixture = File(base.cacheDir, "attachment-identity-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val files = File(fixture, "files").apply { check(mkdirs()) }
        val cache = File(fixture, "cache").apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        member = repository.ensureDefaultMember()
        val now = Instant.now().toString()
        val id = repository.saveRecord(ClinicalRecordEntity(
            memberId = member.id, recordDate = "2026-10-11", title = "虚构病情报告", createdAt = now, updatedAt = now
        ))
        record = requireNotNull(database.clinicalRecordDao().byId(id))
        source = File(cache, "synthetic-report.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.CYAN)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    @After fun tearDown() {
        database.close()
        fixture.deleteRecursively()
    }

    @Test fun validPermanentTargetImportsARealSyntheticImage() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        assertEquals(record.id, target.recordId)
        assertEquals(record.uuid, target.recordUuid)
        assertEquals(member.uuid, target.memberUuid)
        val imported = repository.importAttachment(target, Uri.fromFile(source)).getOrThrow()
        assertEquals(record.id, imported.recordId)
        assertEquals(AttachmentKind.IMAGE.name, imported.kind)
        assertTrue(repository.attachmentStore.verifyStored(imported))
        assertArrayEquals(source.readBytes(), repository.attachmentStore.file(imported).readBytes())
        assertEquals(listOf(imported), database.attachmentDao().all())
    }

    @Test fun reusedRecordIdWithDifferentUuidRejectsBeforeReadingTheSource() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        database.clinicalRecordDao().update(record.copy(uuid = UUID.randomUUID().toString(), title = "替换后的另一病历"))
        assertRejectedWithoutChanges(target, "病历身份")
    }

    @Test fun reusedRecordIdBeforeCaptureRejectsTheStaleUiRecord() = runBlocking {
        database.clinicalRecordDao().update(record.copy(uuid = UUID.randomUUID().toString()))
        val before = state()
        val failure = runCatching { repository.captureAttachmentTarget(record, member.uuid) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("病历身份") == true)
        assertEquals(before, state())
    }

    @Test fun reusedMemberIdWithDifferentUuidRejectsEvenWhenRecordUuidIsUnchanged() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        database.familyMemberDao().update(member.copy(uuid = UUID.randomUUID().toString(), name = "替换后的成员"))
        assertRejectedWithoutChanges(target, "成员身份")
    }

    @Test fun changedMemberUuidBeforeCaptureCannotCreateATargetFromStaleUiData() = runBlocking {
        database.familyMemberDao().update(member.copy(uuid = UUID.randomUUID().toString()))
        val before = state()
        val failure = runCatching { repository.captureAttachmentTarget(record, member.uuid) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("成员身份") == true)
        assertEquals(before, state())
    }

    @Test fun changedRecordOwnerIsRejectedRatherThanImportingToAnotherFamilyMember() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        val other = addOtherMember()
        database.clinicalRecordDao().update(record.copy(memberId = other.id))
        assertRejectedWithoutChanges(target, "所属成员")
    }

    @Test fun archivingTheOwnerRejectsPendingImportsAndNewTargetCapture() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        addOtherMember()
        repository.archiveMember(member.id)
        assertRejectedWithoutChanges(target, "已归档")
        assertTrue(runCatching { repository.captureAttachmentTarget(record, member.uuid) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun recordMovedToTrashRejectsPendingImportsAndNewTargetCapture() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        repository.deleteRecord(record)
        assertRejectedWithoutChanges(target, "回收站")
        assertTrue(runCatching { repository.captureAttachmentTarget(record, member.uuid) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun laterReportsInTheSameBatchCannotFollowAReusedNumericRecordId() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        val first = repository.importAttachment(target, Uri.fromFile(source)).getOrThrow()
        database.clinicalRecordDao().update(record.copy(uuid = UUID.randomUUID().toString(), title = "另一永久身份"))
        val before = state()
        val next = repository.importAttachment(target, Uri.fromFile(source))
        assertTrue(next.isFailure)
        assertTrue(next.exceptionOrNull()?.message?.contains("病历身份") == true)
        assertEquals(before, state())
        assertEquals(listOf(first), database.attachmentDao().all())
        assertTrue(repository.attachmentStore.verifyStored(first))
    }

    @Test fun numericOnlyImportReproducesTheBugButPermanentTargetRejectsTheSameReplacement() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        database.clinicalRecordDao().update(record.copy(uuid = UUID.randomUUID().toString(), title = "虚构替换病历"))
        database.familyMemberDao().update(member.copy(uuid = UUID.randomUUID().toString(), name = "虚构替换成员"))
        // This is exactly the old repository body: a mutex plus the numeric ID alone.
        val legacy = repository.mutationMutex.withLock {
            repository.attachmentStore.import(record.id, Uri.fromFile(source)).getOrThrow()
        }
        assertEquals(record.id, legacy.recordId)
        assertNotEquals(target.recordUuid, database.clinicalRecordDao().byId(legacy.recordId)?.uuid)
        assertNotEquals(target.memberUuid, database.familyMemberDao().byId(member.id)?.uuid)
        val before = state()
        val guarded = repository.importAttachment(target, Uri.fromFile(source))
        assertTrue(guarded.isFailure)
        assertTrue(guarded.exceptionOrNull()?.message?.contains("病历身份") == true)
        assertEquals(before, state())
    }

    @Test fun cancellationPropagatesRatherThanReturningAFailureAndContinuingTheBatch() = runBlocking {
        val target = repository.captureAttachmentTarget(record, member.uuid)
        val before = state()
        var cancelled = false
        var returned = false
        coroutineScope {
            val job = launch {
                cancel(CancellationException("synthetic attachment cancellation"))
                try {
                    repository.importAttachment(target, unreadableUri)
                    returned = true
                } catch (_: CancellationException) { cancelled = true }
            }
            job.join()
        }
        assertTrue(cancelled)
        assertFalse(returned)
        assertEquals(before, state())
    }

    private suspend fun assertRejectedWithoutChanges(target: AttachmentImportTarget, message: String) {
        val before = state()
        // A valid target would fail while reading this URI; the identity error must happen first.
        val result = repository.importAttachment(target, unreadableUri)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(result.exceptionOrNull()?.message?.contains(message) == true)
        assertEquals(before, state())
    }

    private suspend fun addOtherMember(): FamilyMemberEntity {
        val now = Instant.now().toString()
        val id = repository.saveMember(FamilyMemberEntity(
            name = "虚构成员乙", nickname = "家人", relationship = "家人", createdAt = now, updatedAt = now
        ))
        return requireNotNull(database.familyMemberDao().byId(id))
    }

    private data class State(
        val members: List<FamilyMemberEntity>,
        val records: List<ClinicalRecordEntity>,
        val attachments: List<AttachmentEntity>,
        val files: Map<String, String>
    )

    private suspend fun state() = State(
        database.familyMemberDao().all(), database.clinicalRecordDao().all(), database.attachmentDao().all(),
        fixture.walkTopDown().filter { it.isFile }.associate { file ->
            file.relativeTo(fixture).invariantSeparatorsPath to MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        }
    )
}
