package com.healthtimeline.app.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PortableBackupRecoveryTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository
    private lateinit var service: PortableBackupService
    private lateinit var fixtureDirectory: File
    private lateinit var backup: File
    private lateinit var attachment: AttachmentEntity
    private var memberId = 0L
    private val original = "synthetic report bytes".toByteArray()

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        service = PortableBackupService(context, repository)
        memberId = repository.ensureDefaultMember().id
        val now = Instant.now().toString()
        val recordId = repository.saveRecord(ClinicalRecordEntity(memberId = memberId,
            recordDate = "2026-09-03", title = "虚构报告", createdAt = now, updatedAt = now))
        val name = "audit-test-${UUID.randomUUID()}"
        fixtureDirectory = File(context.filesDir, "attachments/$name").apply { check(mkdirs()) }
        val file = File(fixtureDirectory, "report.pdf").apply { writeBytes(original) }
        val value = AttachmentEntity(recordId = recordId, kind = AttachmentKind.PDF.name,
            displayName = "虚构报告.pdf", mimeType = "application/pdf", relativePath = "attachments/$name/report.pdf",
            sizeBytes = file.length(), sha256 = hash(original), createdAt = now)
        val id = database.attachmentDao().insert(value)
        attachment = value.copy(id = id)
        backup = File.createTempFile("audit-backup-", ".htbackup", context.cacheDir)
        service.export(Uri.fromFile(backup), password()).getOrThrow()
    }

    @After fun tearDown(): Unit = runBlocking {
        // Only remove the synthetic fixture and restored directories belonging to it.
        database.attachmentDao().all().filter { it.uuid == attachment.uuid }.forEach { value ->
            val file = repository.attachmentStore.file(value)
            if (file.parentFile?.parentFile?.canonicalFile == File(context.filesDir, "restored_attachments").canonicalFile) {
                file.parentFile?.deleteRecursively()
            }
        }
        database.close()
        fixtureDirectory.deleteRecursively()
        backup.delete()
        Unit
    }

    @Test fun sameLengthCorruptionIsRepairedAndRepeatedMergeDoesNotDuplicate() = runBlocking {
        repository.attachmentStore.file(attachment).writeBytes(ByteArray(original.size) { 33 })
        service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).getOrThrow()
        val restored = database.attachmentDao().all().single()
        assertEquals(attachment.id, restored.id)
        assertEquals(attachment.uuid, restored.uuid)
        assertTrue(repository.attachmentStore.verifyStored(restored))
        assertArrayEquals(original, repository.attachmentStore.file(restored).readBytes())
        service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).getOrThrow()
        assertEquals(1, database.clinicalRecordDao().all().size)
        assertEquals(1, database.attachmentDao().all().size)
        assertEquals(restored.relativePath, database.attachmentDao().all().single().relativePath)
    }

    @Test fun missingLocalFileCanBeRepairedFromVerifiedBackup() = runBlocking {
        assertTrue(repository.attachmentStore.file(attachment).delete())
        service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).getOrThrow()
        assertTrue(repository.attachmentStore.verifyStored(database.attachmentDao().all().single()))
    }

    @Test fun databaseFailureRollsBackAndRemovesUncommittedAttachmentSet() = runBlocking {
        val corrupted = ByteArray(original.size) { 33 }
        repository.attachmentStore.file(attachment).writeBytes(corrupted)
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        val restoredRoot = File(context.filesDir, "restored_attachments")
        val beforeDirectories = restoredRoot.list()?.toSet().orEmpty()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER audit_fail_attachment BEFORE INSERT ON attachments BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END"
        )
        try {
            assertTrue(service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).isFailure)
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(corrupted, repository.attachmentStore.file(attachment).readBytes())
            assertEquals(beforeDirectories, restoredRoot.list()?.toSet().orEmpty())
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER audit_fail_attachment")
        }
    }

    @Test fun incorrectPasswordDoesNotChangeDatabaseOrFiles() = runBlocking {
        val before = database.attachmentDao().all()
        assertTrue(service.mergeImport(Uri.fromFile(backup), "wrong-password".toCharArray(), emptyMap(), memberId).isFailure)
        assertEquals(before, database.attachmentDao().all())
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
    }

    @Test fun uppercaseLegacyManifestAndZipPathKeepIdentitiesDuringRepair() = runBlocking {
        rewriteFixtureWithUppercaseUuids()
        repository.attachmentStore.file(attachment).delete()
        service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).getOrThrow()
        val restored = database.attachmentDao().all().single()
        assertEquals(attachment.id, restored.id)
        assertEquals(attachment.recordId, restored.recordId)
        assertEquals(attachment.uuid, restored.uuid)
        assertTrue(repository.attachmentStore.verifyStored(restored))
    }

    @Test fun caseAmbiguousDuplicateZipEntriesAreRejectedWithoutChangingData() = runBlocking {
        rewriteFixtureWithUppercaseUuids(duplicatePath = true)
        val before = database.attachmentDao().all()
        assertTrue(service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).isFailure)
        assertEquals(before, database.attachmentDao().all())
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
    }

    private suspend fun rewriteFixtureWithUppercaseUuids(duplicatePath: Boolean = false) {
        val plain = File.createTempFile("audit-plain-", ".zip", context.cacheDir)
        try {
            backup.inputStream().use { input -> plain.outputStream().use { BackupCrypto.decrypt(input, it, password()) } }
            val output = ByteArrayOutputStream()
            ZipFile(plain).use { source ->
                ZipOutputStream(output).use { target ->
                    val json = source.getInputStream(source.getEntry("manifest.json")).bufferedReader().use { it.readText() }
                    val installation = JSONObject(json).getString("sourceInstallationId")
                    val identities = listOf(installation, database.familyMemberDao().all().single().uuid,
                        database.clinicalRecordDao().all().single().uuid, attachment.uuid)
                    val upperJson = identities.fold(json) { text, id -> text.replace(id, id.uppercase()) }
                    target.putNextEntry(ZipEntry("manifest.json"))
                    target.write(upperJson.toByteArray(Charsets.UTF_8)); target.closeEntry()
                    val path = "files/${attachment.uuid}"
                    val upperPath = "FILES/${attachment.uuid.uppercase()}"
                    val bytes = source.getInputStream(source.getEntry(path)).use { it.readBytes() }
                    target.putNextEntry(ZipEntry(upperPath)); target.write(bytes); target.closeEntry()
                    if (duplicatePath) {
                        target.putNextEntry(ZipEntry(path)); target.write(bytes); target.closeEntry()
                    }
                }
            }
            ByteArrayInputStream(output.toByteArray()).use { input ->
                backup.outputStream().use { BackupCrypto.encrypt(input, it, password()) }
            }
        } finally { plain.delete() }
    }

    private fun password() = "test-password-only".toCharArray()
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
}
