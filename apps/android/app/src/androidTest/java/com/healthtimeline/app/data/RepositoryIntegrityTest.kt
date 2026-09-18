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
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import java.time.Instant
import android.net.Uri
import android.content.Context
import com.healthtimeline.app.backup.BackupService
import java.io.File
import java.security.MessageDigest
import java.util.UUID

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
        assertEquals(3, database.followUpDao().scheduleById(scheduleId)?.leadDays)
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

    @Test fun medicationEditCreatesNewScheduleVersionAndKeepsHistoricalDose() = runBlocking {
        val member = repository.ensureDefaultMember()
        val today = LocalDate.now()
        val medicationId = repository.saveMedication(
            MedicationEntity(
                name = "药物甲",
                doseAmount = "1",
                doseUnit = "片",
                startDate = today.minusDays(10).toString(),
                mode = MedicationMode.SCHEDULED.name,
                createdAt = NOW,
                updatedAt = NOW,
                memberId = member.id
            ),
            listOf(LocalTime.of(8, 0))
        ).medicationId
        val oldSchedule = database.medicationDao().schedulesForMedication(medicationId).single()
        val historicalDate = today.minusDays(1)
        assertTrue(
            repository.recordDose(
                medicationId,
                oldSchedule.id,
                historicalDate.atTime(8, 0),
                historicalDate.atTime(8, 5),
                MedicationLogStatus.TAKEN
            )
        )
        val current = requireNotNull(database.medicationDao().medicationById(medicationId))

        repository.saveMedication(
            current.copy(doseAmount = "2", updatedAt = Instant.now().toString()),
            listOf(LocalTime.of(9, 5)),
            today.plusDays(1)
        )

        val schedules = database.medicationDao().schedulesForMedication(medicationId)
        val closed = schedules.first { it.id == oldSchedule.id }
        val next = schedules.first { it.id != oldSchedule.id }
        assertFalse(closed.enabled)
        assertEquals(today.toString(), closed.effectiveTo)
        assertEquals("1", closed.doseAmountSnapshot)
        assertEquals(today.plusDays(1).toString(), next.effectiveFrom)
        assertEquals("09:05", next.localTime)
        assertEquals("2", next.doseAmountSnapshot)
        assertEquals("1", database.medicationDao().allLogs().single().doseAmountSnapshot)
    }

    @Test fun endingMedicationRecordsExactTimeAndKeepsFinalDayInCalendar() = runBlocking {
        val member = repository.ensureDefaultMember()
        val today = LocalDate.now()
        val plannedEnd = today.plusDays(30)
        val medicationId = repository.saveMedication(
            MedicationEntity(
                name = "疗程药物", doseAmount = "1", doseUnit = "片",
                startDate = today.minusDays(2).toString(), endDate = plannedEnd.toString(),
                mode = MedicationMode.SCHEDULED.name,
                createdAt = NOW, updatedAt = NOW, memberId = member.id
            ),
            listOf(LocalTime.of(8, 0))
        ).medicationId
        val originalSchedule = database.medicationDao().schedulesForMedication(medicationId).single()
        assertTrue(
            repository.recordDose(
                medicationId, originalSchedule.id, today.atTime(8, 0), today.atTime(8, 5), MedicationLogStatus.TAKEN
            )
        )

        repository.archiveMedication(medicationId)

        val ended = requireNotNull(database.medicationDao().medicationById(medicationId))
        assertTrue(ended.archived)
        assertEquals(today.toString(), ended.endDate)
        assertEquals(plannedEnd.toString(), ended.archivedPreviousEndDate)
        assertTrue(ended.endedAt?.let { runCatching { Instant.parse(it) }.isSuccess } == true)
        val paused = database.medicationDao().schedulesForMedication(medicationId).single()
        assertFalse(paused.enabled)
        assertTrue(paused.pausedByCourseEnd)
        assertEquals(plannedEnd.toString(), paused.effectiveTo)
        val projected = projectMedicationMonth(
            java.time.YearMonth.from(today),
            listOf(ended),
            listOf(paused),
            database.medicationDao().allLogs()
        )
        assertEquals(1, projected.entries(today).size)
        assertEquals(MedicationDayStatus.TAKEN, projected.entries(today).single().status)
        assertTrue(projected.entries(today.plusDays(1)).isEmpty())

        repository.restoreMedication(medicationId)

        val restored = requireNotNull(database.medicationDao().medicationById(medicationId))
        assertFalse(restored.archived)
        assertEquals(plannedEnd.toString(), restored.endDate)
        assertEquals(null, restored.endedAt)
        assertEquals(null, restored.archivedPreviousEndDate)
        val resumed = database.medicationDao().schedulesForMedication(medicationId).single()
        assertTrue(resumed.enabled)
        assertFalse(resumed.pausedByCourseEnd)
        assertEquals(originalSchedule.uuid, resumed.uuid)
        assertEquals(1, database.medicationDao().allLogs().size)
        val restoredProjection = projectMedicationMonth(
            java.time.YearMonth.from(today.plusDays(1)), listOf(restored), listOf(resumed), database.medicationDao().allLogs()
        )
        assertEquals(1, restoredProjection.entries(today.plusDays(1)).size)
    }

    @Test fun correctingDoseKeepsUuidCreationTimeAndDoseSnapshot() = runBlocking {
        val member = repository.ensureDefaultMember()
        val today = LocalDate.now()
        val medicationId = repository.saveMedication(
            MedicationEntity(
                name = "药物乙", doseAmount = "1", doseUnit = "片", startDate = today.toString(),
                mode = MedicationMode.SCHEDULED.name, createdAt = NOW, updatedAt = NOW, memberId = member.id
            ),
            listOf(LocalTime.of(8, 0))
        ).medicationId
        val schedule = database.medicationDao().schedulesForMedication(medicationId).single()
        assertTrue(repository.recordDose(medicationId, schedule.id, today.atTime(8, 0), null, MedicationLogStatus.SKIPPED))
        val before = database.medicationDao().allLogs().single()

        assertTrue(repository.correctDose(before.id, MedicationLogStatus.TAKEN, today.atTime(8, 15)))

        val after = requireNotNull(database.medicationDao().logById(before.id))
        assertEquals(before.uuid, after.uuid)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.doseAmountSnapshot, after.doseAmountSnapshot)
        assertEquals(MedicationLogStatus.TAKEN.name, after.status)
        assertEquals(today.atTime(8, 15).toString(), after.actualAt)
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

        assertEquals("母亲", database.familyMemberDao().byId(motherId)?.nickname)
        assertEquals("母亲", database.familyMemberDao().byId(motherId)?.relationship)
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

    @Test fun quickEntryCreatesConditionAndRecordInOneSave() = runBlocking {
        val self = repository.ensureDefaultMember()
        val recordId = repository.saveRecord(
            ClinicalRecordEntity(
                memberId = self.id,
                recordDate = "2026-09-06",
                title = "上颌窦炎术后复查",
                createdAt = NOW,
                updatedAt = NOW
            ),
            RecordConditionResolution.Create("上颌窦炎")
        )

        val condition = database.conditionDao().all().single()
        assertEquals("上颌窦炎", condition.name)
        assertEquals(condition.id, database.clinicalRecordDao().byId(recordId)?.conditionId)
        assertEquals(self.id, condition.memberId)
    }

    @Test fun quickEntryReusesNormalizedActiveCondition() = runBlocking {
        val self = repository.ensureDefaultMember()
        val conditionId = repository.saveCondition(
            ConditionEntity(name = " 上颌 窦炎 ", createdAt = NOW, memberId = self.id)
        )
        val recordId = repository.saveRecord(
            ClinicalRecordEntity(
                memberId = self.id,
                recordDate = "2026-09-06",
                title = "复查",
                createdAt = NOW,
                updatedAt = NOW
            ),
            RecordConditionResolution.Create("上颌窦炎")
        )

        assertEquals(1, database.conditionDao().all().size)
        assertEquals(conditionId, database.clinicalRecordDao().byId(recordId)?.conditionId)
    }

    @Test fun quickEntryRestoresArchivedConditionWithRecord() = runBlocking {
        val self = repository.ensureDefaultMember()
        val conditionId = repository.saveCondition(
            ConditionEntity(name = "乳腺复查", createdAt = NOW, memberId = self.id)
        )
        repository.archiveCondition(conditionId)

        val recordId = repository.saveRecord(
            ClinicalRecordEntity(
                memberId = self.id,
                recordDate = "2026-09-06",
                title = "定期复查",
                createdAt = NOW,
                updatedAt = NOW
            ),
            RecordConditionResolution.Restore(conditionId)
        )

        assertFalse(database.conditionDao().byId(conditionId)!!.archived)
        assertEquals(conditionId, database.clinicalRecordDao().byId(recordId)?.conditionId)
    }

    @Test fun failedQuickEntryRecordSaveRollsBackNewCondition() = runBlocking {
        val self = repository.ensureDefaultMember()
        val duplicateUuid = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        repository.saveRecord(
            ClinicalRecordEntity(
                memberId = self.id,
                recordDate = "2026-09-05",
                title = "已有记录",
                createdAt = NOW,
                updatedAt = NOW,
                uuid = duplicateUuid
            )
        )

        val result = runCatching {
            repository.saveRecord(
                ClinicalRecordEntity(
                    memberId = self.id,
                    recordDate = "2026-09-06",
                    title = "重复 UUID",
                    createdAt = NOW,
                    updatedAt = NOW,
                    uuid = duplicateUuid
                ),
                RecordConditionResolution.Create("不应留下的分类")
            )
        }

        assertTrue(result.isFailure)
        assertTrue(database.conditionDao().all().isEmpty())
        assertEquals(1, database.clinicalRecordDao().all().size)
    }

    @Test fun failedBatchQuickEntryRollsBackEveryRecordAndCondition() = runBlocking {
        val self = repository.ensureDefaultMember()
        val duplicateUuid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        repository.saveRecord(
            ClinicalRecordEntity(
                memberId = self.id,
                recordDate = "2026-09-01",
                title = "原记录",
                createdAt = NOW,
                updatedAt = NOW,
                uuid = duplicateUuid
            )
        )
        val requests = listOf(
            ClinicalRecordSaveRequest(
                ClinicalRecordEntity(
                    memberId = self.id,
                    recordDate = "2026-09-06",
                    title = "第一条",
                    createdAt = NOW,
                    updatedAt = NOW
                ),
                RecordConditionResolution.Create("批量分类")
            ),
            ClinicalRecordSaveRequest(
                ClinicalRecordEntity(
                    memberId = self.id,
                    recordDate = "2026-09-07",
                    title = "第二条失败",
                    createdAt = NOW,
                    updatedAt = NOW,
                    uuid = duplicateUuid
                )
            )
        )

        assertTrue(runCatching { repository.saveRecords(requests) }.isFailure)
        assertTrue(database.conditionDao().all().isEmpty())
        assertEquals(listOf("原记录"), database.clinicalRecordDao().all().map { it.title })
    }

    @Test fun interruptedAttachmentDeletionIsRecoveredWhenDatabaseRowStillExists() = runBlocking {
        val attachment = createStoredAttachment("recover-me.pdf")
        val original = repository.attachmentStore.file(attachment)

        repository.attachmentStore.stageDeletion(attachment)
        assertFalse(original.exists())

        repository.attachmentStore.cleanupDeletionTrash()

        assertTrue(original.exists())
        assertEquals("test-report", original.readText())
        assertEquals(attachment.uuid, database.attachmentDao().byUuid(attachment.uuid)?.uuid)
        original.parentFile?.deleteRecursively()
    }

    @Test fun attachmentMovesToTrashThenPermanentDeletionIsSafe() = runBlocking {
        val attachment = createStoredAttachment("delete-me.pdf")
        val original = repository.attachmentStore.file(attachment)

        assertEquals(AttachmentDeleteResult.MOVED_TO_TRASH, repository.deleteAttachment(attachment))
        assertEquals(AttachmentDeleteResult.ALREADY_DELETED, repository.deleteAttachment(attachment))
        assertTrue(original.exists())
        val deleted = requireNotNull(database.attachmentDao().byId(attachment.id))
        val trashItem = TrashItem(
            TrashItemType.ATTACHMENT, deleted.id, deleted.uuid, deleted.displayName,
            "检查报告", requireNotNull(deleted.deletedAt)
        )
        assertEquals(AttachmentDeleteResult.DELETED, repository.permanentlyDeleteTrash(trashItem))
        assertFalse(original.exists())
        assertTrue(database.attachmentDao().byUuid(attachment.uuid) == null)
    }

    @Test fun staleAttachmentObjectCannotDeleteAReplacedDatabaseRow() = runBlocking {
        val attachment = createStoredAttachment("stale.pdf")
        val stale = attachment.copy(uuid = "90000000-0000-4000-8000-000000000009")

        assertEquals(AttachmentDeleteResult.ALREADY_DELETED, repository.deleteAttachment(stale))
        assertTrue(repository.attachmentStore.file(attachment).exists())
        assertEquals(attachment.uuid, database.attachmentDao().byId(attachment.id)?.uuid)
        repository.attachmentStore.file(attachment).parentFile?.deleteRecursively()
    }

    @Test fun missingAttachmentFileCanRemoveOnlyItsInvalidDatabaseRow() = runBlocking {
        val attachment = createStoredAttachment("already-missing.pdf")
        assertTrue(repository.attachmentStore.file(attachment).delete())

        assertEquals(AttachmentDeleteResult.MOVED_TO_TRASH, repository.deleteAttachment(attachment))
        val deleted = requireNotNull(database.attachmentDao().byId(attachment.id))
        val item = TrashItem(TrashItemType.ATTACHMENT, deleted.id, deleted.uuid, deleted.displayName, "检查报告", requireNotNull(deleted.deletedAt))
        assertEquals(AttachmentDeleteResult.MISSING_FILE_RECORD_REMOVED, repository.permanentlyDeleteTrash(item))
        assertTrue(database.attachmentDao().byId(attachment.id) == null)
    }

    @Test fun recordTrashAndRestorePreserveAttachmentFileAndPermanentIds() = runBlocking {
        val attachment = createStoredAttachment("restore-from-trash.pdf")
        val record = requireNotNull(database.clinicalRecordDao().byId(attachment.recordId))
        val original = repository.attachmentStore.file(attachment)

        repository.deleteRecord(record)
        val deletedRecord = requireNotNull(database.clinicalRecordDao().byId(record.id))
        val deletedAttachment = requireNotNull(database.attachmentDao().byId(attachment.id))
        assertTrue(deletedRecord.deletedAt != null)
        assertEquals(deletedRecord.deletedAt, deletedAttachment.deletedAt)
        assertTrue(repository.recordsForMember(record.memberId).first().isEmpty())
        assertTrue(repository.attachmentsForMember(record.memberId).first().isEmpty())
        assertTrue(original.exists())

        repository.restoreTrash(
            TrashItem(
                TrashItemType.CLINICAL_RECORD, record.id, record.uuid, record.title,
                "病历", requireNotNull(deletedRecord.deletedAt)
            )
        )

        val restoredRecord = requireNotNull(database.clinicalRecordDao().byId(record.id))
        val restoredAttachment = requireNotNull(database.attachmentDao().byId(attachment.id))
        assertEquals(record.uuid, restoredRecord.uuid)
        assertEquals(attachment.uuid, restoredAttachment.uuid)
        assertTrue(restoredRecord.deletedAt == null)
        assertTrue(restoredAttachment.deletedAt == null)
        assertTrue(original.exists())
        original.parentFile?.deleteRecursively()
    }

    @Test fun recordRestoreRefusesMissingAttachmentAndKeepsTrashState() = runBlocking {
        val attachment = createStoredAttachment("missing-on-restore.pdf")
        val record = requireNotNull(database.clinicalRecordDao().byId(attachment.recordId))
        repository.deleteRecord(record)
        val deletedRecord = requireNotNull(database.clinicalRecordDao().byId(record.id))
        assertTrue(repository.attachmentStore.file(attachment).delete())
        val item = TrashItem(
            TrashItemType.CLINICAL_RECORD,
            record.id,
            record.uuid,
            record.title,
            "病历",
            requireNotNull(deletedRecord.deletedAt)
        )

        assertTrue(runCatching { repository.restoreTrash(item) }.isFailure)

        assertTrue(database.clinicalRecordDao().byId(record.id)?.deletedAt != null)
        assertTrue(database.attachmentDao().byId(attachment.id)?.deletedAt != null)
    }

    @Test fun followUpAndMedicationTrashStopUseAndCanBeRestored() = runBlocking {
        val member = repository.ensureDefaultMember()
        val today = LocalDate.now()
        val followUpId = repository.saveFollowUp(
            FollowUpScheduleEntity(
                title = "复查测试",
                recurrenceType = RecurrenceType.ONCE.name,
                anchorDate = today.toString(),
                anchorDayOfMonth = today.dayOfMonth,
                nextDueDate = today.toString(),
                createdAt = NOW,
                updatedAt = NOW,
                memberId = member.id
            )
        )
        val medicationId = repository.saveMedication(
            MedicationEntity(
                name = "回收站药物",
                doseAmount = "1",
                doseUnit = "片",
                startDate = today.toString(),
                mode = MedicationMode.SCHEDULED.name,
                createdAt = NOW,
                updatedAt = NOW,
                memberId = member.id
            ),
            listOf(LocalTime.of(8, 0))
        ).medicationId
        val followUp = requireNotNull(database.followUpDao().scheduleById(followUpId))
        val schedule = database.medicationDao().schedulesForMedication(medicationId).single()

        repository.deleteFollowUp(followUp)
        repository.deleteMedication(medicationId)

        assertTrue(repository.followUpsForMember(member.id).first().isEmpty())
        assertTrue(repository.medicationsForMember(member.id).first().isEmpty())
        assertEquals(null, repository.processDueFollowUp(followUpId, today))
        assertFalse(repository.recordDose(medicationId, schedule.id, today.atTime(8, 0), today.atTime(8, 5), MedicationLogStatus.TAKEN))
        val trash = repository.trashForMember(member.id).first()
        assertEquals(setOf(TrashItemType.FOLLOW_UP, TrashItemType.MEDICATION), trash.map { it.type }.toSet())

        trash.forEach { repository.restoreTrash(it) }

        assertEquals(followUp.uuid, repository.followUpsForMember(member.id).first().single().uuid)
        assertEquals(
            requireNotNull(database.medicationDao().medicationById(medicationId)).uuid,
            repository.medicationsForMember(member.id).first().single().uuid
        )
    }

    @Test fun purgeRemovesOnlyTrashOlderThanThirtyDays() = runBlocking {
        val member = repository.ensureDefaultMember()
        val today = LocalDate.now()
        fun medication(name: String) = MedicationEntity(
            name = name,
            doseAmount = "1",
            doseUnit = "片",
            startDate = today.toString(),
            mode = MedicationMode.AS_NEEDED.name,
            createdAt = NOW,
            updatedAt = NOW,
            memberId = member.id
        )
        val expiredId = repository.saveMedication(medication("已过期"), emptyList()).medicationId
        val recentId = repository.saveMedication(medication("未过期"), emptyList()).medicationId
        repository.deleteMedication(expiredId)
        repository.deleteMedication(recentId)
        val now = Instant.parse("2026-09-14T12:00:00Z")
        val expiredAt = now.minusSeconds(31L * 24L * 60L * 60L).toString()
        val recentAt = now.minusSeconds(29L * 24L * 60L * 60L).toString()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE medications SET deletedAt = ? WHERE id = ?",
            arrayOf<Any>(expiredAt, expiredId)
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE medications SET deletedAt = ? WHERE id = ?",
            arrayOf<Any>(recentAt, recentId)
        )

        assertEquals(1, repository.purgeExpiredTrash(now))

        assertEquals(null, database.medicationDao().medicationById(expiredId))
        assertTrue(database.medicationDao().medicationById(recentId)?.deletedAt != null)
    }

    @Test fun safetyCenterDetectsAttachmentChecksumMismatchWithoutChangingData() = runBlocking {
        val attachment = createStoredAttachment("integrity-check.pdf")
        val file = repository.attachmentStore.file(attachment)
        file.writeText("tampered-report")
        val preferencesName = "safety-center-test-${UUID.randomUUID()}"
        val statusStore = SafetyStatusStore(context, preferencesName)
        val service = SafetyCenterService(context, database, repository, statusStore)

        val failed = service.runIntegrityCheck()
        assertFalse(failed.healthy)
        assertEquals(1, failed.checksumMismatches)
        assertEquals("tampered-report", file.readText())
        assertEquals(attachment.uuid, database.attachmentDao().byId(attachment.id)?.uuid)

        val correctHash = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE attachments SET sha256 = ? WHERE id = ?",
            arrayOf<Any>(correctHash, attachment.id)
        )
        val passed = service.runIntegrityCheck()
        assertTrue(passed.healthy)
        assertEquals(1, passed.attachmentCount)
        assertEquals(passed, statusStore.lastIntegrityResult.value)

        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        file.parentFile?.deleteRecursively()
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
        Unit
    }

    private suspend fun createStoredAttachment(fileName: String): AttachmentEntity {
        val member = repository.ensureDefaultMember()
        val recordId = repository.saveRecord(
            ClinicalRecordEntity(
                memberId = member.id,
                recordDate = "2026-09-07",
                title = "附件测试",
                createdAt = NOW,
                updatedAt = NOW
            )
        )
        val relativePath = "attachments/$recordId/$fileName"
        val file = File(context.filesDir, relativePath)
        check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
        file.writeText("test-report")
        val value = AttachmentEntity(
            recordId = recordId,
            kind = AttachmentKind.PDF.name,
            displayName = fileName,
            mimeType = "application/pdf",
            relativePath = relativePath,
            sizeBytes = file.length(),
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) },
            createdAt = NOW
        )
        val id = database.attachmentDao().insert(value)
        return value.copy(id = id)
    }

    private companion object { const val NOW = "2026-09-01T00:00:00Z" }
}
