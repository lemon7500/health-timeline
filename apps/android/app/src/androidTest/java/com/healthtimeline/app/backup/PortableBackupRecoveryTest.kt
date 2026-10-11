package com.healthtimeline.app.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.healthtimeline.app.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test fun manifestCentralDirectoryCrcMismatchIsRejectedWithoutChangingData() = runBlocking {
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        val beforeStaging = stagingDirectories()
        val plain = File.createTempFile("audit-central-crc-", ".zip", context.cacheDir)
        try {
            backup.inputStream().use { input -> plain.outputStream().use { BackupCrypto.decrypt(input, it, password()) } }
            val bytes = plain.readBytes()
            val central = (0..bytes.size - 46).first { position ->
                bytes[position] == 0x50.toByte() && bytes[position + 1] == 0x4b.toByte() &&
                    bytes[position + 2] == 0x01.toByte() && bytes[position + 3] == 0x02.toByte()
            }
            val nameLength = (bytes[central + 28].toInt() and 0xff) or
                ((bytes[central + 29].toInt() and 0xff) shl 8)
            assertEquals("manifest.json", String(bytes, central + 46, nameLength, Charsets.UTF_8))
            bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
            plain.writeBytes(bytes)
            // Old ZipFile permits reading this malformed CRC: this is the regression contrast,
            // not an assertion that an instrumented device has run this test locally.
            ZipFile(plain).use { oldReader ->
                val manifest = oldReader.getInputStream(oldReader.getEntry("manifest.json")).use { it.readBytes() }
                assertTrue(JSONObject(String(manifest, Charsets.UTF_8)).getInt("schemaVersion") >= 2)
            }
            ByteArrayInputStream(bytes).use { input -> backup.outputStream().use { BackupCrypto.encrypt(input, it, password()) } }
            assertTrue(service.previewImport(Uri.fromFile(backup), password(), memberId).isFailure)
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
            assertEquals(beforeStaging, stagingDirectories())
        } finally { plain.delete() }
    }

    @Test fun reusedNumericMemberIdCannotReceiveFrozenPortableImportOrReplacement() = runBlocking {
        val old = requireNotNull(database.familyMemberDao().byId(memberId))
        database.familyMemberDao().update(old.copy(uuid = UUID.randomUUID().toString(), name = "另一个虚构成员"))
        val beforeMembers = database.familyMemberDao().all()
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        assertTrue(service.previewImport(Uri.fromFile(backup), password(), memberId, old.uuid).isFailure)
        assertTrue(service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId, old.uuid).isFailure)
        assertTrue(service.replaceFromBackup(Uri.fromFile(backup), password(), memberId, old.uuid).isFailure)
        assertEquals(beforeMembers, database.familyMemberDao().all())
        assertEquals(beforeRecords, database.clinicalRecordDao().all())
        assertEquals(beforeAttachments, database.attachmentDao().all())
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
    }

    @Test fun reusedNumericMemberIdCannotReceiveFrozenLegacyImportOrRestore() = runBlocking {
        val legacyFile = File.createTempFile("audit-legacy-member-", ".htbackup", context.cacheDir)
        try {
            val legacy = BackupService(context, repository)
            legacy.export(Uri.fromFile(legacyFile), password()).getOrThrow()
            val old = requireNotNull(database.familyMemberDao().byId(memberId))
            database.familyMemberDao().update(old.copy(uuid = UUID.randomUUID().toString(), name = "另一个虚构成员"))
            val beforeMembers = database.familyMemberDao().all()
            val beforeRecords = database.clinicalRecordDao().all()
            val beforeAttachments = database.attachmentDao().all()
            assertTrue(legacy.importAsNew(Uri.fromFile(legacyFile), password(), memberId, old.uuid).isFailure)
            assertTrue(legacy.restore(Uri.fromFile(legacyFile), password(), memberId, old.uuid).isFailure)
            assertTrue(service.importLegacyAsNew(Uri.fromFile(legacyFile), password(), memberId, old.uuid).isFailure)
            assertTrue(service.replaceFromBackup(Uri.fromFile(legacyFile), password(), memberId, old.uuid).isFailure)
            assertEquals(beforeMembers, database.familyMemberDao().all())
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
        } finally { legacyFile.delete() }
    }

    @Test fun archivedFrozenTargetCannotPassPreviewEvenWhenUuidStillMatches() = runBlocking {
        val old = requireNotNull(database.familyMemberDao().byId(memberId))
        database.familyMemberDao().update(old.copy(archived = true))
        assertTrue(service.previewImport(Uri.fromFile(backup), password(), memberId, old.uuid).isFailure)
        assertEquals(old.copy(archived = true), database.familyMemberDao().byId(memberId))
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
    }

    @Test fun aliasedLegacyAttachmentDestinationsFailBeforeReplacingAnyData() = runBlocking {
        val legacyFile = File.createTempFile("audit-legacy-alias-", ".htbackup", context.cacheDir)
        val plain = File.createTempFile("audit-legacy-alias-", ".zip", context.cacheDir)
        try {
            val legacy = BackupService(context, repository)
            legacy.export(Uri.fromFile(legacyFile), password()).getOrThrow()
            legacyFile.inputStream().use { input -> plain.outputStream().use { BackupCrypto.decrypt(input, it, password()) } }
            val output = ByteArrayOutputStream()
            val secondBytes = "another synthetic report".toByteArray()
            ZipFile(plain).use { source ->
                val json = JSONObject(source.getInputStream(source.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                val files = json.getJSONArray("attachments")
                val first = files.getJSONObject(0)
                val firstId = first.getLong("id")
                val secondId = firstId + 1
                val firstPath = "attachments/${first.getLong("recordId")}/report.pdf"
                first.put("relativePath", firstPath)
                val second = JSONObject(first.toString()).apply {
                    if (has("uuid")) put("uuid", UUID.randomUUID().toString())
                    put("id", secondId); put("relativePath", firstPath.replace("/report.pdf", "/./report.pdf"))
                    put("sizeBytes", secondBytes.size); put("sha256", hash(secondBytes))
                }
                files.put(second)
                // Raw paths are distinct; their filesystem destinations must still be distinct.
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(json.toString().toByteArray()); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("files/$firstId")); zip.write(original); zip.closeEntry()
                    zip.putNextEntry(ZipEntry("files/$secondId")); zip.write(secondBytes); zip.closeEntry()
                }
            }
            ByteArrayInputStream(output.toByteArray()).use { input -> legacyFile.outputStream().use { BackupCrypto.encrypt(input, it, password()) } }
            val beforeMembers = database.familyMemberDao().all()
            val beforeRecords = database.clinicalRecordDao().all()
            val beforeAttachments = database.attachmentDao().all()
            val beforeTemporary = legacyTemporaryFiles()
            val result = legacy.restore(Uri.fromFile(legacyFile), password(), memberId)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("路径重复"))
            assertEquals(beforeMembers, database.familyMemberDao().all())
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
            assertEquals(beforeTemporary, legacyTemporaryFiles())
        } finally { legacyFile.delete(); plain.delete() }
    }

    @Test fun queuedMutationCannotCommitBetweenLegacySafetyBackupAndReplacement() = runBlocking {
        val legacyFile = File.createTempFile("audit-legacy-queue-", ".htbackup", context.cacheDir)
        val safetyRoot = File(context.cacheDir, "audit-safety-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val safetyContext = object : ContextWrapper(context) { override fun getFilesDir(): File = safetyRoot }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gated = AtomicBoolean(false)
        val restoredRoot = File(context.filesDir, "restored_attachments")
        val beforeRestored = restoredRoot.list()?.toSet().orEmpty()
        try {
            val legacy = BackupService(context, repository)
            legacy.export(Uri.fromFile(legacyFile), password()).getOrThrow()
            val guarded = PortableBackupService(safetyContext, repository, legacy = legacy, availableStorageBytes = {
                if (repository.mutationMutex.isLocked && gated.compareAndSet(false, true)) {
                    entered.countDown()
                    check(release.await(15, TimeUnit.SECONDS)) { "Synthetic safety-backup barrier timed out" }
                }
                minOf(context.cacheDir.usableSpace, context.filesDir.usableSpace)
            })
            withTimeout(30_000) {
                val replacing = async(Dispatchers.IO) {
                    guarded.replaceFromBackup(Uri.fromFile(legacyFile), password(), memberId).getOrThrow()
                }
                withContext(Dispatchers.IO) { check(entered.await(15, TimeUnit.SECONDS)) }
                val now = Instant.now().toString()
                // UNDISPATCHED reaches the mutex immediately, then suspends while safety/replace owns it.
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    repository.saveRecord(ClinicalRecordEntity(memberId = memberId, recordDate = "2026-09-04",
                        title = "替换期间排队的虚构病历", createdAt = now, updatedAt = now))
                }
                assertFalse(queued.isCompleted)
                release.countDown()
                replacing.await(); queued.await()
            }
            assertTrue(database.clinicalRecordDao().all().any { it.title == "替换期间排队的虚构病历" })
            val safety = requireNotNull(File(safetyRoot, "safety_backups").listFiles()).single { it.extension == "htbackup" }
            val plain = File.createTempFile("audit-safety-check-", ".zip", context.cacheDir)
            try {
                safety.inputStream().use { input -> plain.outputStream().use { BackupCrypto.decrypt(input, it, password()) } }
                ZipFile(plain).use { zip ->
                    val saved = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().use { it.readText() })
                    assertEquals("虚构报告", saved.getJSONArray("records").getJSONObject(0).getString("title"))
                    assertEquals(1, saved.getJSONArray("records").length())
                }
            } finally { plain.delete() }
        } finally {
            release.countDown(); legacyFile.delete(); safetyRoot.deleteRecursively()
            restoredRoot.listFiles()?.filter { it.name !in beforeRestored }?.forEach { it.deleteRecursively() }
        }
    }

    @Test fun storageBudgetFailureDoesNotChangeDatabaseOrLeaveStagingFiles() = runBlocking {
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        val beforeStaging = stagingDirectories()
        val limited = PortableBackupService(context, repository, availableStorageBytes = {
            BackupResourceBudget.STORAGE_RESERVE_BYTES + 64
        })
        assertTrue(limited.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).isFailure)
        assertEquals(beforeRecords, database.clinicalRecordDao().all())
        assertEquals(beforeAttachments, database.attachmentDao().all())
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
        assertEquals(beforeStaging, stagingDirectories())
    }

    @Test fun cancellationBeforeValidationPropagatesAndCleansStagingWithoutChangingData() = runBlocking {
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        val beforeStaging = stagingDirectories()
        val cancellation = CancellationException("synthetic backup cancellation")
        val cancelled = PortableBackupService(context, repository, availableStorageBytes = { throw cancellation })
        try {
            cancelled.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId)
            fail("Cancellation must not become a password error or a successful result")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(beforeRecords, database.clinicalRecordDao().all())
        assertEquals(beforeAttachments, database.attachmentDao().all())
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
        assertEquals(beforeStaging, stagingDirectories())
    }

    @Test fun tamperedAuthenticationTagNeverChangesDataAndRemovesUnauthenticatedStaging() = runBlocking {
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        val beforeStaging = stagingDirectories()
        val bytes = backup.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        backup.writeBytes(bytes)
        assertTrue(service.mergeImport(Uri.fromFile(backup), password(), emptyMap(), memberId).isFailure)
        assertEquals(beforeRecords, database.clinicalRecordDao().all())
        assertEquals(beforeAttachments, database.attachmentDao().all())
        assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
        assertEquals(beforeStaging, stagingDirectories())
    }

    @Test fun legacyRestoreStorageFailureKeepsAllExistingDataAndCleansTemporaryFiles() = runBlocking {
        val legacyFile = File.createTempFile("audit-legacy-backup-", ".htbackup", context.cacheDir)
        try {
            BackupService(context, repository).export(Uri.fromFile(legacyFile), password()).getOrThrow()
            val beforeRecords = database.clinicalRecordDao().all()
            val beforeAttachments = database.attachmentDao().all()
            val beforeTemporary = legacyTemporaryFiles()
            val limited = BackupService(context, repository, availableStorageBytes = {
                BackupResourceBudget.STORAGE_RESERVE_BYTES + 64
            })
            assertTrue(limited.restore(Uri.fromFile(legacyFile), password(), memberId).isFailure)
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
            assertEquals(beforeTemporary, legacyTemporaryFiles())
        } finally { legacyFile.delete() }
    }

    @Test fun legacyImportCancellationKeepsAllExistingDataAndCleansTemporaryFiles() = runBlocking {
        val legacyFile = File.createTempFile("audit-legacy-backup-", ".htbackup", context.cacheDir)
        try {
            BackupService(context, repository).export(Uri.fromFile(legacyFile), password()).getOrThrow()
            val beforeRecords = database.clinicalRecordDao().all()
            val beforeAttachments = database.attachmentDao().all()
            val beforeTemporary = legacyTemporaryFiles()
            val cancellation = CancellationException("synthetic legacy backup cancellation")
            val cancelled = BackupService(context, repository, availableStorageBytes = { throw cancellation })
            try {
                cancelled.importAsNew(Uri.fromFile(legacyFile), password(), memberId)
                fail("Legacy import cancellation must propagate")
            } catch (error: CancellationException) { assertSame(cancellation, error) }
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(original, repository.attachmentStore.file(attachment).readBytes())
            assertEquals(beforeTemporary, legacyTemporaryFiles())
        } finally { legacyFile.delete() }
    }

    @Test fun portableDestinationSpaceFailureClearsPartialExportAndDoesNotMarkSuccess() = runBlocking {
        assertDestinationSpaceFailure(legacyExport = false)
    }

    @Test fun legacyDestinationSpaceFailureClearsPartialExportAndKeepsSourceData() = runBlocking {
        assertDestinationSpaceFailure(legacyExport = true)
    }

    private suspend fun assertDestinationSpaceFailure(legacyExport: Boolean) {
        // Incompressible synthetic data forces several final-document writes, not just setup failure.
        val bytes = ByteArray(192 * 1024).also(java.util.Random(73)::nextBytes)
        repository.attachmentStore.file(attachment).writeBytes(bytes)
        attachment = attachment.copy(sizeBytes = bytes.size.toLong(), sha256 = hash(bytes))
        database.attachmentDao().insertAll(listOf(attachment))
        val beforeRecords = database.clinicalRecordDao().all()
        val beforeAttachments = database.attachmentDao().all()
        val destination = File.createTempFile("audit-destination-", ".htbackup", context.cacheDir)
        val preferenceName = "audit-export-status-${UUID.randomUUID()}"
        val status = SafetyStatusStore(context, preferenceName)
        var targetChecks = 0
        val targetSpace: (java.io.FileDescriptor) -> Long? = {
            targetChecks++
            if (targetChecks <= 3) BackupResourceBudget.STORAGE_RESERVE_BYTES + 256 * 1024L else 0L
        }
        try {
            val result = if (legacyExport) {
                BackupService(context, repository, availableDestinationStorageBytes = targetSpace)
                    .export(Uri.fromFile(destination), password())
            } else {
                PortableBackupService(context, repository, safetyStatusStore = status,
                    availableDestinationStorageBytes = targetSpace)
                    .export(Uri.fromFile(destination), password())
            }
            assertTrue(result.isFailure)
            assertTrue("Expected failure after the first final-document block", targetChecks >= 4)
            assertEquals(0L, destination.length())
            assertNull(status.lastBackupAt.value)
            assertEquals(beforeRecords, database.clinicalRecordDao().all())
            assertEquals(beforeAttachments, database.attachmentDao().all())
            assertArrayEquals(bytes, repository.attachmentStore.file(attachment).readBytes())
        } finally {
            destination.delete()
            context.deleteSharedPreferences(preferenceName)
        }
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
    private fun stagingDirectories(): Set<String> = context.cacheDir.listFiles()
        ?.filter { it.name.startsWith("portable-restore-") }?.mapTo(hashSetOf()) { it.name }.orEmpty()
    private fun legacyTemporaryFiles(): Set<String> = context.cacheDir.listFiles()
        ?.filter { it.name.startsWith("restore-") || it.name.startsWith("import-new-") ||
            it.name.startsWith("health-restore-") || it.name.startsWith("health-import-new-") }
        ?.mapTo(hashSetOf()) { it.name }.orEmpty()
    private fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
}
