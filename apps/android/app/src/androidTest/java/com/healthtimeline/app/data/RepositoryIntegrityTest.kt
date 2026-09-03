package com.healthtimeline.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import java.time.Instant
import android.net.Uri
import android.content.Context
import com.healthtimeline.app.backup.BackupService
import java.io.File

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrityTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: HealthRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = HealthRepository(database, AttachmentStore(context, database.attachmentDao()))
    }

    @After fun tearDown() { database.close() }

    @Test fun duplicateFollowUpDeliveryDoesNotAdvanceUntilCompletion() = runBlocking {
        repository.ensureDefaultMember()
        val scheduleId = repository.saveFollowUp(
            FollowUpScheduleEntity(
                title = "乳腺复查",
                recurrenceType = RecurrenceType.EVERY_N_MONTHS.name,
                interval = 3,
                anchorDate = "2026-07-09",
                anchorDayOfMonth = 9,
                nextDueDate = "2026-07-09",
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        val due = LocalDate.of(2026, 7, 9)

        repository.processDueFollowUp(scheduleId, due)
        repository.processDueFollowUp(scheduleId, due)

        assertEquals(1, database.followUpDao().allOccurrences().size)
        assertEquals("2026-07-09", database.followUpDao().scheduleById(scheduleId)?.nextDueDate)
        val occurrence = database.followUpDao().allOccurrences().single()
        repository.completeOccurrence(occurrence.id)
        assertEquals("2026-10-09", database.followUpDao().scheduleById(scheduleId)?.nextDueDate)
    }

    @Test fun asNeededDoseCannotBeRecordedTwiceForSameSlot() = runBlocking {
        repository.ensureDefaultMember()
        val medicationId = repository.saveMedication(
            MedicationEntity(
                name = "药物",
                doseAmount = "1",
                doseUnit = "片",
                startDate = "2026-09-01",
                mode = MedicationMode.AS_NEEDED.name,
                createdAt = NOW,
                updatedAt = NOW
            ),
            emptyList()
        ).medicationId
        val scheduledAt = "2026-09-01T00:00"
        assertTrue(repository.markDose(medicationId, null, scheduledAt, MedicationLogStatus.TAKEN))
        assertFalse(repository.markDose(medicationId, null, scheduledAt, MedicationLogStatus.TAKEN))
        assertEquals(1, database.medicationDao().allLogs().size)
    }

    @Test fun medicationLogCannotReferenceAnotherMedicationsSchedule() = runBlocking {
        repository.ensureDefaultMember()
        val first = repository.saveMedication(
            MedicationEntity(
                name = "药物甲", doseAmount = "1", doseUnit = "片", startDate = "2026-09-01",
                mode = MedicationMode.SCHEDULED.name, createdAt = NOW, updatedAt = NOW
            ),
            listOf(java.time.LocalTime.of(8, 0))
        ).medicationId
        val second = repository.saveMedication(
            MedicationEntity(
                name = "药物乙", doseAmount = "1", doseUnit = "片", startDate = "2026-09-01",
                mode = MedicationMode.SCHEDULED.name, createdAt = NOW, updatedAt = NOW
            ),
            listOf(java.time.LocalTime.of(9, 0))
        ).medicationId
        val secondSchedule = database.medicationDao().schedulesForMedication(second).single()

        assertFalse(
            repository.markDose(first, secondSchedule.id, "2026-09-01T08:00", MedicationLogStatus.TAKEN)
        )
        assertTrue(database.medicationDao().allLogs().isEmpty())
    }

    @Test fun familyMembersAreIsolatedAndReferencedMemberCannotBeDeleted() = runBlocking {
        val self = repository.ensureDefaultMember()
        val now = Instant.now().toString()
        val motherId = repository.saveMember(
            FamilyMemberEntity(
                name = "张女士", nickname = "妈妈", relationship = "母亲",
                createdAt = now, updatedAt = now
            )
        )
        val selfCondition = repository.saveCondition(ConditionEntity(name = "鼻窦", createdAt = now, memberId = self.id))
        val motherCondition = repository.saveCondition(ConditionEntity(name = "乳腺", createdAt = now, memberId = motherId))

        assertEquals(listOf(selfCondition), repository.conditionsForMember(self.id).first().map { it.id })
        assertEquals(listOf(motherCondition), repository.conditionsForMember(motherId).first().map { it.id })
        assertTrue(runCatching { repository.deleteEmptyMember(motherId) }.isFailure)
        assertTrue(
            runCatching {
                repository.saveRecord(
                    ClinicalRecordEntity(
                        conditionId = selfCondition,
                        memberId = motherId,
                        recordDate = "2026-09-03",
                        title = "错误关联",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }.isFailure
        )
    }

    @Test fun archivingLastActiveMemberIsRejected() = runBlocking {
        val self = repository.ensureDefaultMember()
        assertTrue(runCatching { repository.archiveMember(self.id) }.isFailure)
        assertFalse(database.familyMemberDao().byId(self.id)!!.archived)
    }

    @Test fun permanentMemberUuidCannotBeChangedByEdit() = runBlocking {
        val self = repository.ensureDefaultMember()
        assertTrue(
            runCatching {
                repository.saveMember(
                    self.copy(uuid = "99999999-9999-4999-8999-999999999999", updatedAt = Instant.now().toString())
                )
            }.isFailure
        )
        assertEquals(self.uuid, database.familyMemberDao().byId(self.id)!!.uuid)
    }

    @Test fun legacyBackupCanBeAppendedAsNewWithoutOverwritingLocalData() = runBlocking {
        val self = repository.ensureDefaultMember()
        val originalId = repository.saveCondition(
            ConditionEntity(name = "乳腺", createdAt = NOW, memberId = self.id)
        )
        val source = File(context.cacheDir, "legacy-as-new.htbackup")
        val service = BackupService(context, repository)
        service.export(Uri.fromFile(source), "test-password".toCharArray()).getOrThrow()

        service.importAsNew(Uri.fromFile(source), "test-password".toCharArray(), self.id).getOrThrow()

        val conditions = database.conditionDao().all()
        assertEquals(2, conditions.size)
        assertTrue(conditions.any { it.id == originalId })
        assertEquals(2, conditions.map { it.uuid }.toSet().size)
        assertTrue(conditions.all { it.memberId == self.id })
        source.delete()
    }

    private companion object { const val NOW = "2026-09-01T00:00:00Z" }
}
