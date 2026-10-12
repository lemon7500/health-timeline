package com.healthtimeline.app.backup

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.healthtimeline.app.data.AppDatabase
import com.healthtimeline.app.data.AttachmentStore
import com.healthtimeline.app.data.ClinicalRecordEntity
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.data.SafetyStatusStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Real restore/merge tests, not just cipher round trips. Immutable public synthetic fixtures
 * were encrypted in a separate JVM using BC 1.83; the app under test uses the upgraded BC.
 * Plaintext Room is deliberate here: SQLCipher file compatibility has its own isolated probe.
 */
@RunWith(AndroidJUnit4::class)
class LegacyCryptoBackupCompatibilityTest {
    private lateinit var appContext: Context
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository
    private lateinit var service: PortableBackupService
    private lateinit var golden: Properties
    private lateinit var preferenceName: String
    private var targetMemberId = 0L
    private var sentinelId = 0L

    @Before fun setUp() = runBlocking {
        appContext = ApplicationProvider.getApplicationContext()
        root = File(appContext.cacheDir, "legacy-crypto-compat-${UUID.randomUUID()}")
        check(root.mkdirs())
        val files = File(root, "files").apply { check(mkdirs()) }
        val cache = File(root, "cache").apply { check(mkdirs()) }
        context = object : ContextWrapper(appContext) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
        targetMemberId = repository.ensureDefaultMember().id
        sentinelId = repository.saveRecord(ClinicalRecordEntity(memberId = targetMemberId,
            recordDate = "2026-09-05", title = "不能被旧备份覆盖的虚构本机记录",
            notes = "合成测试资料", createdAt = CREATED_AT, updatedAt = UPDATED_AT))
        preferenceName = "legacy-crypto-compat-${UUID.randomUUID()}"
        service = PortableBackupService(context, repository,
            safetyStatusStore = SafetyStatusStore(context, preferenceName))
        golden = Properties().apply {
            testAssets().open("bc183-golden.properties").use(::load)
        }
        assertEquals("1.83", golden.getProperty("generator.bcVersion"))
        assertEquals("82cf3a2af766c3bc874f6d36b9f20a8b99a8f09762dc776e8a227a45d8daaafb",
            golden.getProperty("generator.bcSha256"))
    }

    @After fun tearDown() {
        if (::database.isInitialized) database.close()
        if (::preferenceName.isInitialized) appContext.deleteSharedPreferences(preferenceName)
        if (::root.isInitialized) {
            check(root.canonicalFile.parentFile == appContext.cacheDir.canonicalFile)
            check(root.name.startsWith("legacy-crypto-compat-"))
            root.deleteRecursively()
        }
    }

    @Test fun bc183V1ImportsAllLegacyRecordsWithoutReplacingExistingData() = runBlocking {
        val source = fixture(1)
        assertTrue(service.previewImport(Uri.fromFile(source), password(), targetMemberId)
            .getOrThrow().legacyReplacementOnly)
        service.importLegacyAsNew(Uri.fromFile(source), password(), targetMemberId).getOrThrow()
        assertImportedContent(1)
        // v1 has no permanent UUIDs: it must not masquerade as an automatically deduplicated merge.
        val before = footprint()
        assertTrue(service.mergeImport(Uri.fromFile(source), password(), emptyMap(), targetMemberId).isFailure)
        assertEquals(before, footprint())
    }

    @Test fun bc183V2RestoresSinglePersonIntoSelectedMemberAndDeduplicates() = runBlocking {
        assertPortableCompatibility(2)
    }

    @Test fun bc183V1ReplacementKeepsOriginalIdsAndCreatesVerifiedSafetyBackup() = runBlocking {
        service.replaceFromBackup(Uri.fromFile(fixture(1)), password(), targetMemberId).getOrThrow()
        assertImportedContent(1, existingKept = false)
        assertEquals(listOf(30L, 31L), database.clinicalRecordDao().all().map { it.id })
        assertEquals(listOf(31L, 32L), database.attachmentDao().all().map { it.id })
        assertEquals(50L, database.medicationDao().allMedications().single().id)
        assertEquals(51L, database.medicationDao().allSchedules().single().id)
        assertEquals(listOf(52L, 53L), database.medicationDao().allLogs().map { it.id })
        val safety = requireNotNull(File(context.filesDir, "safety_backups").listFiles()).single()
        val plain = File(context.cacheDir, "safety-check.zip")
        safety.inputStream().use { input -> plain.outputStream().use { BackupCrypto.decrypt(input, it, password()) } }
        ZipFile(plain).use { archive ->
            val saved = JSONObject(archive.getInputStream(archive.getEntry("manifest.json"))
                .bufferedReader().use { it.readText() })
            assertEquals(5, saved.getInt("schemaVersion"))
            assertEquals(1, saved.getJSONArray("records").length())
            assertEquals("不能被旧备份覆盖的虚构本机记录",
                saved.getJSONArray("records").getJSONObject(0).getString("title"))
        }
    }

    @Test fun bc183V3RestoresFamilyReferencesAndLegacyRelationship() = runBlocking {
        assertPortableCompatibility(3)
    }

    @Test fun bc183V4KeepsOriginalDayOrderAndHistoricalDoseSchedules() = runBlocking {
        assertPortableCompatibility(4)
    }

    @Test fun bc183V5KeepsTrashAttachmentsAndEndedCourseHistory() = runBlocking {
        assertPortableCompatibility(5)
    }

    @Test fun wrongPasswordOrTamperedLegacyContainerCannotChangeExistingDataOrFiles() = runBlocking {
        val initial = fixture(5)
        service.mergeImport(Uri.fromFile(initial), password(), emptyMap(), targetMemberId).getOrThrow()
        initial.delete()
        assertImportedContent(5)
        val before = footprint()
        val cacheBefore = context.cacheDir.list()?.toSet().orEmpty()
        for (version in 1..5) {
            val source = fixture(version)
            val incorrect = "Wrong-Public-Test-Password!".toCharArray()
            assertTrue("v$version must reject an incorrect password",
                importFixture(version, source, incorrect).isFailure)
            assertEquals(before, footprint())
            val tampered = File(context.cacheDir, "tampered-v$version.htbackup")
            val bytes = source.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            tampered.writeBytes(bytes)
            assertTrue("v$version must reject a modified authentication tag",
                importFixture(version, tampered, password()).isFailure)
            assertEquals(before, footprint())
            tampered.delete()
            source.delete()
        }
        assertEquals(cacheBefore, context.cacheDir.list()?.toSet().orEmpty())
    }

    private suspend fun assertPortableCompatibility(version: Int) {
        val source = fixture(version)
        val preview = service.previewImport(Uri.fromFile(source), password(), targetMemberId).getOrThrow()
        assertFalse(preview.legacyReplacementOnly)
        service.mergeImport(Uri.fromFile(source), password(), emptyMap(), targetMemberId).getOrThrow()
        assertImportedContent(version)
        val first = footprint()
        val repeated = service.mergeImport(Uri.fromFile(source), password(), emptyMap(), targetMemberId).getOrThrow()
        assertEquals("Repeated merge must not add records for v$version", 0, repeated.additions)
        assertEquals(first, footprint())
        // Old data must remain representable in a newly encrypted v5 export and merge back unchanged.
        val exported = File(context.cacheDir, "upgraded-v$version.htbackup")
        service.export(Uri.fromFile(exported), password()).getOrThrow()
        service.mergeImport(Uri.fromFile(exported), password(), emptyMap(), targetMemberId).getOrThrow()
        assertEquals(first, footprint())
    }

    private suspend fun assertImportedContent(version: Int, existingKept: Boolean = true) {
        val members = database.familyMemberDao().all()
        assertEquals(if (version >= 3) 2 else 1, members.size)
        val importedMemberId = if (version >= 3) {
            members.single { it.uuid == MEMBER_UUID }.also {
                assertEquals("虚构兼容成员", it.name)
                assertEquals("测试关系", it.relationship)
                assertEquals(it.relationship, it.nickname)
            }.id
        } else targetMemberId
        val condition = database.conditionDao().all().single()
        assertEquals("虚构鼻窦炎", condition.name)
        assertEquals(importedMemberId, condition.memberId)
        val allRecords = database.clinicalRecordDao().all()
        assertEquals(if (existingKept) 3 else 2, allRecords.size)
        if (existingKept) {
            val sentinel = requireNotNull(database.clinicalRecordDao().byId(sentinelId))
            assertEquals("不能被旧备份覆盖的虚构本机记录", sentinel.title)
            assertEquals(targetMemberId, sentinel.memberId)
        } else assertNull(database.clinicalRecordDao().byId(sentinelId))
        val records = allRecords.filter { !existingKept || it.id != sentinelId }.sortedBy { it.dayOrder }
        assertEquals(listOf("虚构鼻窦炎首次复查", "虚构鼻窦炎第二事件"), records.map { it.title })
        assertEquals(if (version >= 4) listOf(7L, 8L) else listOf(0L, 1L), records.map { it.dayOrder })
        records.forEach {
            assertEquals(importedMemberId, it.memberId)
            assertEquals(condition.id, it.conditionId)
            assertEquals("2026-09-06", it.recordDate)
            assertEquals("虚构鼻窦炎", it.diagnosis)
            assertEquals("虚构测试医院", it.hospital)
            assertEquals("虚构测试医生", it.clinician)
            assertTrue(it.notes.startsWith("原文记录：\n"))
            assertEquals(CREATED_AT, it.createdAt)
            assertEquals(UPDATED_AT, it.updatedAt)
        }
        assertEquals(if (version == 5) UPDATED_AT else null, records.last().deletedAt)
        val expectedRecords = JSONObject(testAssets().open("v$version-manifest.json")
            .bufferedReader().use { it.readText() }).getJSONArray("records")
        records.forEachIndexed { index, record ->
            val expected = expectedRecords.getJSONObject(index)
            assertEquals(expected.getString("symptoms"), record.symptoms)
            assertEquals(expected.getString("treatment"), record.treatment)
            assertEquals(expected.getString("medicationNotes"), record.medicationNotes)
            assertEquals(expected.getString("notes"), record.notes)
        }
        if (version >= 2) {
            assertEquals(RECORD_UUIDS, records.map { it.uuid })
            assertEquals(CONDITION_UUID, condition.uuid)
        }
        val expectedFiles = JSONArray(testAssets().open("attachments.json").bufferedReader().use { it.readText() })
        val attachments = database.attachmentDao().all()
        assertEquals(2, attachments.size)
        attachments.forEach { attachment ->
            val index = if (attachment.kind == "PDF") 0 else 1
            val expected = Base64.decode(expectedFiles.getJSONObject(index).getString("contentBase64"), Base64.DEFAULT)
            assertEquals(records[index].id, attachment.recordId)
            assertEquals(expected.size.toLong(), attachment.sizeBytes)
            assertEquals(sha256(expected), attachment.sha256)
            assertTrue(repository.attachmentStore.verifyStored(attachment))
            assertArrayEquals(expected, repository.attachmentStore.file(attachment).readBytes())
            assertEquals(if (version == 5 && index == 1) UPDATED_AT else null, attachment.deletedAt)
            if (version >= 2) assertEquals(expectedFiles.getJSONObject(index).getString("uuid"), attachment.uuid)
        }
        val followUp = database.followUpDao().allSchedules().single()
        assertEquals(importedMemberId, followUp.memberId)
        assertEquals(condition.id, followUp.conditionId)
        assertEquals("EVERY_N_MONTHS", followUp.recurrenceType)
        assertEquals(3, followUp.interval)
        assertEquals(3, followUp.leadDays)
        assertEquals("09:05", followUp.reminderTime)
        assertEquals("2026-10-09", followUp.nextDueDate)
        val occurrence = database.followUpDao().allOccurrences().single()
        assertEquals(followUp.id, occurrence.scheduleId)
        assertEquals("DONE", occurrence.status)
        assertEquals("2026-07-09", occurrence.dueDate)
        assertEquals("2026-07-09T10:00:00Z", occurrence.completedAt)
        if (version >= 2) {
            assertEquals("40000000-0000-4000-8000-000000000001", followUp.uuid)
            assertEquals("41000000-0000-4000-8000-000000000001", occurrence.uuid)
        }
        val medication = database.medicationDao().allMedications().single()
        assertEquals(importedMemberId, medication.memberId)
        assertEquals(condition.id, medication.conditionId)
        assertEquals("虚构兼容药物", medication.name)
        assertEquals("5", medication.doseAmount)
        assertEquals("mg", medication.doseUnit)
        assertEquals("2026-09-01", medication.startDate)
        assertEquals(if (version == 5) "2026-09-14" else "2026-09-30", medication.endDate)
        assertEquals(version == 5, medication.archived)
        assertEquals(if (version == 5) UPDATED_AT else null, medication.endedAt)
        assertEquals(if (version == 5) "2026-09-30" else null, medication.archivedPreviousEndDate)
        if (version >= 2) assertEquals("50000000-0000-4000-8000-000000000001", medication.uuid)
        val schedules = database.medicationDao().allSchedules().sortedBy { it.effectiveFrom }
        assertEquals(if (version >= 4) 2 else 1, schedules.size)
        schedules.forEach {
            assertEquals(medication.id, it.medicationId)
            assertEquals(version != 5, it.enabled)
            assertEquals(version == 5, it.pausedByCourseEnd)
            assertEquals(UPDATED_AT, it.updatedAt)
        }
        if (version >= 2) assertEquals("51000000-0000-4000-8000-000000000001", schedules.first().uuid)
        assertEquals("2026-09-01", schedules.first().effectiveFrom)
        assertEquals(if (version >= 4) "2" else "5", schedules.first().doseAmountSnapshot)
        assertEquals(if (version >= 4) "2026-09-07" else "2026-09-30", schedules.first().effectiveTo)
        if (version >= 4) {
            assertEquals("2026-09-08", schedules.last().effectiveFrom)
            assertEquals("3", schedules.last().doseAmountSnapshot)
            assertEquals("20:10", schedules.last().localTime)
            assertEquals("51000000-0000-4000-8000-000000000002", schedules.last().uuid)
        }
        val logs = database.medicationDao().allLogs().sortedBy { it.scheduledAt }
        assertEquals(2, logs.size)
        assertEquals(listOf("TAKEN", "SKIPPED"), logs.map { it.status })
        assertEquals(listOf("2", "3"), logs.map { it.doseAmountSnapshot })
        assertEquals(listOf("2026-09-06T08:05:00", "2026-09-09T20:10:00"), logs.map { it.scheduledAt })
        assertEquals("2026-09-06T08:10:00", logs.first().actualAt)
        assertNull(logs.last().actualAt)
        logs.forEach {
            assertEquals(medication.id, it.medicationId)
            assertTrue(it.scheduleId in schedules.map { schedule -> schedule.id })
            assertEquals(CREATED_AT, it.createdAt)
        }
        if (version >= 4) assertEquals(UPDATED_AT, logs.first().updatedAt)
        if (version >= 2) assertEquals(listOf("52000000-0000-4000-8000-000000000001",
            "52000000-0000-4000-8000-000000000002"), logs.map { it.uuid })
        database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {
            assertFalse("Legacy restored graph must have no dangling foreign keys", it.moveToFirst())
        }
    }

    private suspend fun importFixture(version: Int, file: File, password: CharArray): Result<*> =
        if (version == 1) service.importLegacyAsNew(Uri.fromFile(file), password, targetMemberId)
        else service.mergeImport(Uri.fromFile(file), password, emptyMap(), targetMemberId)

    private suspend fun footprint(): Pair<BackupSnapshot, Map<String, String>> {
        val entities = BackupSnapshot(database.conditionDao().all(), database.clinicalRecordDao().all(),
            database.attachmentDao().all(), database.followUpDao().allSchedules(), database.followUpDao().allOccurrences(),
            database.medicationDao().allMedications(), database.medicationDao().allSchedules(),
            database.medicationDao().allLogs(), database.familyMemberDao().all())
        val files = context.filesDir.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(context.filesDir).invariantSeparatorsPath to sha256(it.readBytes()) }
        return entities to files
    }

    private fun fixture(version: Int): File = File(context.cacheDir, "old-bc183-v$version.htbackup").apply {
        val bytes = Base64.decode(requireNotNull(golden.getProperty("v$version.containerBase64")), Base64.DEFAULT)
        assertEquals("HTBACKUP", String(bytes, 0, 8, Charsets.US_ASCII))
        writeBytes(bytes)
    }

    private fun password(): CharArray = requireNotNull(golden.getProperty("public.password")).toCharArray()
    private fun testAssets() = InstrumentationRegistry.getInstrumentation().context.assets
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val CREATED_AT = "2026-09-01T00:00:00Z"
        private const val UPDATED_AT = "2026-09-14T10:00:00Z"
        private const val MEMBER_UUID = "10000000-0000-4000-8000-000000000001"
        private const val CONDITION_UUID = "20000000-0000-4000-8000-000000000001"
        private val RECORD_UUIDS = listOf("30000000-0000-4000-8000-000000000001",
            "30000000-0000-4000-8000-000000000002")
    }
}
