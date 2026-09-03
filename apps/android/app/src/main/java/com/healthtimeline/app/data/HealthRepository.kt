package com.healthtimeline.app.data

import android.net.Uri
import androidx.room.withTransaction
import com.healthtimeline.app.domain.RecurrenceCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HealthRepository(
    val database: AppDatabase,
    val attachmentStore: AttachmentStore
) {
    internal val mutationMutex = Mutex()
    private val conditions = database.conditionDao()
    private val records = database.clinicalRecordDao()
    private val attachments = database.attachmentDao()
    private val followUps = database.followUpDao()
    private val medications = database.medicationDao()
    private val members = database.familyMemberDao()

    val memberFlow = members.observeAll()

    fun conditionsForMember(memberId: Long) = conditions.observeForMember(memberId)
    fun recordsForMember(memberId: Long) = records.observeForMember(memberId)
    fun attachmentsForMember(memberId: Long) = attachments.observeForMember(memberId)
    fun followUpsForMember(memberId: Long) = followUps.observeSchedulesForMember(memberId)
    fun occurrencesForMember(memberId: Long) = followUps.observeOccurrencesForMember(memberId)
    fun medicationsForMember(memberId: Long) = medications.observeMedicationsForMember(memberId)
    fun medicationSchedulesForMember(memberId: Long) = medications.observeSchedulesForMember(memberId)
    fun medicationLogsForMember(memberId: Long) = medications.observeLogsForMember(memberId)

    fun todayDosesForMember(memberId: Long): Flow<List<TodayDose>> = combine(
        medicationsForMember(memberId),
        medicationSchedulesForMember(memberId),
        medicationLogsForMember(memberId)
    ) { medicines, schedules, logs ->
        val today = LocalDate.now()
        medicines.asSequence()
            .filter { !it.archived && !LocalDate.parse(it.startDate).isAfter(today) }
            .filter { it.endDate == null || !LocalDate.parse(it.endDate).isBefore(today) }
            .flatMap { medication ->
                if (medication.mode == MedicationMode.AS_NEEDED.name) {
                    sequenceOf(TodayDose(medication, null, today.atStartOfDay().toString(), null))
                } else {
                    schedules.asSequence()
                        .filter { it.medicationId == medication.id && it.enabled }
                        .map { schedule ->
                            val scheduled = LocalDateTime.of(today, LocalTime.parse(schedule.localTime)).toString()
                            TodayDose(
                                medication,
                                schedule,
                                scheduled,
                                logs.firstOrNull {
                                    it.medicationId == medication.id &&
                                        it.scheduleId == schedule.id && it.scheduledAt == scheduled
                                }
                            )
                        }
                }
            }
            .sortedBy { it.scheduledAt }
            .toList()
    }

    suspend fun ensureDefaultMember(): FamilyMemberEntity = mutationMutex.withLock {
        database.withTransaction {
            members.all().firstOrNull() ?: run {
                val now = Instant.now().toString()
                val id = members.insert(
                    FamilyMemberEntity(
                        name = "本人",
                        nickname = "本人",
                        relationship = "本人",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                requireNotNull(members.byId(id))
            }
        }
    }

    suspend fun memberById(id: Long) = members.byId(id)

    suspend fun saveMember(value: FamilyMemberEntity): Long = mutationMutex.withLock {
        require(value.name.isNotBlank() && value.name.length <= 50) { "请填写 50 字以内的姓名" }
        require(value.nickname.isNotBlank() && value.nickname.length <= 30) { "请填写 30 字以内的称呼" }
        require(value.relationship.isNotBlank() && value.relationship.length <= 30) { "请填写 30 字以内的关系" }
        database.withTransaction {
            if (value.id == 0L) members.insert(value) else {
                val existing = requireNotNull(members.byId(value.id)) { "家庭成员不存在" }
                require(existing.uuid == value.uuid) { "不能更改家庭成员永久标识" }
                require(existing.archived == value.archived) { "请使用归档或恢复操作更改成员状态" }
                members.update(value)
                value.id
            }
        }
    }

    suspend fun archiveMember(id: Long) = mutationMutex.withLock {
        database.withTransaction {
            val member = requireNotNull(members.byId(id)) { "家庭成员不存在" }
            require(!member.archived) { "该成员已经归档" }
            require(members.activeCount() > 1) { "至少需要保留一个未归档成员" }
            members.update(member.copy(archived = true, updatedAt = Instant.now().toString()))
        }
    }

    suspend fun restoreMember(id: Long) = mutationMutex.withLock {
        database.withTransaction {
            val member = requireNotNull(members.byId(id)) { "家庭成员不存在" }
            members.update(member.copy(archived = false, updatedAt = Instant.now().toString()))
        }
    }

    suspend fun deleteEmptyMember(id: Long) = mutationMutex.withLock {
        database.withTransaction {
            val member = requireNotNull(members.byId(id)) { "家庭成员不存在" }
            require(members.referenceCount(id) == 0) { "该成员已有历史资料，只能归档" }
            if (!member.archived) require(members.activeCount() > 1) { "至少需要保留一个未归档成员" }
            members.deleteById(id)
        }
    }

    suspend fun saveCondition(value: ConditionEntity): Long = mutationMutex.withLock {
        database.withTransaction {
            requireActiveMember(value.memberId)
            if (value.id == 0L) conditions.insert(value) else {
                val existing = requireNotNull(conditions.byId(value.id)) { "病情分类不存在" }
                require(existing.memberId == value.memberId) { "不能更改病情分类所属成员" }
                require(existing.uuid == value.uuid) { "不能更改病情分类永久标识" }
                conditions.update(value); value.id
            }
        }
    }

    suspend fun archiveCondition(id: Long) = mutationMutex.withLock { conditions.archive(id) }

    suspend fun saveRecord(value: ClinicalRecordEntity): Long = mutationMutex.withLock {
        database.withTransaction {
            requireActiveMember(value.memberId)
            requireConditionForMember(value.conditionId, value.memberId)
            if (value.id == 0L) records.insert(value) else {
                val existing = requireNotNull(records.byId(value.id)) { "病历不存在" }
                require(existing.memberId == value.memberId) { "不能更改病历所属成员" }
                require(existing.uuid == value.uuid) { "不能更改病历永久标识" }
                records.update(value); value.id
            }
        }
    }

    suspend fun deleteRecord(value: ClinicalRecordEntity) = mutationMutex.withLock {
        val files = attachments.forRecord(value.id)
        database.withTransaction { records.delete(value) }
        files.forEach { attachmentStore.file(it).delete() }
        attachmentStore.deleteFilesForRecord(value.id)
    }

    suspend fun importAttachment(recordId: Long, uri: Uri) = mutationMutex.withLock {
        attachmentStore.import(recordId, uri)
    }
    suspend fun deleteAttachment(value: AttachmentEntity) = mutationMutex.withLock { attachmentStore.delete(value) }

    suspend fun saveFollowUp(value: FollowUpScheduleEntity): Long = mutationMutex.withLock {
        database.withTransaction {
            requireActiveMember(value.memberId)
            requireConditionForMember(value.conditionId, value.memberId)
            if (value.id == 0L) followUps.insertSchedule(value) else {
                val existing = requireNotNull(followUps.scheduleById(value.id)) { "复查计划不存在" }
                require(existing.memberId == value.memberId) { "不能更改复查计划所属成员" }
                require(existing.uuid == value.uuid) { "不能更改复查计划永久标识" }
                followUps.updateSchedule(value); value.id
            }
        }
    }

    suspend fun deleteFollowUp(value: FollowUpScheduleEntity) = mutationMutex.withLock { followUps.deleteSchedule(value) }

    suspend fun processDueFollowUp(scheduleId: Long, dueDate: LocalDate): FollowUpScheduleEntity? =
        mutationMutex.withLock {
            database.withTransaction {
                val schedule = followUps.scheduleById(scheduleId) ?: return@withTransaction null
                if (!schedule.enabled || schedule.nextDueDate != dueDate.toString()) return@withTransaction null
                if (members.byId(schedule.memberId)?.archived != false) return@withTransaction null
                followUps.insertOccurrence(
                    FollowUpOccurrenceEntity(
                        scheduleId = scheduleId,
                        dueDate = dueDate.toString(),
                        createdAt = Instant.now().toString()
                    )
                )
                schedule
            }
        }

    suspend fun completeOccurrence(id: Long, skipped: Boolean = false): FollowUpScheduleEntity? =
        mutationMutex.withLock {
            database.withTransaction {
                val occurrence = followUps.occurrenceById(id) ?: return@withTransaction null
                if (occurrence.status != OccurrenceStatus.PENDING.name) return@withTransaction null
                val changed = followUps.markOccurrence(
                    id,
                    if (skipped) OccurrenceStatus.SKIPPED.name else OccurrenceStatus.DONE.name,
                    Instant.now().toString()
                )
                if (changed != 1) return@withTransaction null
                val schedule = followUps.scheduleById(occurrence.scheduleId) ?: return@withTransaction null
                if (!schedule.enabled || schedule.nextDueDate != occurrence.dueDate) return@withTransaction null
                val next = RecurrenceCalculator.nextAfter(schedule, LocalDate.parse(occurrence.dueDate))
                val updated = if (next == null) {
                    schedule.copy(enabled = false, updatedAt = Instant.now().toString())
                } else {
                    schedule.copy(nextDueDate = next.toString(), updatedAt = Instant.now().toString())
                }
                followUps.updateSchedule(updated)
                updated
            }
        }

    suspend fun saveMedication(value: MedicationEntity, times: List<LocalTime>): MedicationSaveResult = mutationMutex.withLock {
        database.withTransaction {
            requireActiveMember(value.memberId)
            requireConditionForMember(value.conditionId, value.memberId)
            val id = if (value.id == 0L) medications.insertMedication(value) else {
                val existing = requireNotNull(medications.medicationById(value.id)) { "药物不存在" }
                require(existing.memberId == value.memberId) { "不能更改药物所属成员" }
                require(existing.uuid == value.uuid) { "不能更改药物永久标识" }
                medications.updateMedication(value); value.id
            }
            val replacedIds = medications.schedulesForMedication(id).map { it.id }
            medications.deleteSchedulesForMedication(id)
            if (value.mode == MedicationMode.SCHEDULED.name) {
                times.distinct().sorted().forEach { time ->
                    medications.insertSchedule(MedicationScheduleEntity(medicationId = id, localTime = time.toString()))
                }
            }
            MedicationSaveResult(id, replacedIds)
        }
    }

    suspend fun archiveMedication(id: Long) = mutationMutex.withLock {
        medications.archiveMedication(id, Instant.now().toString())
    }

    suspend fun markDose(
        medicationId: Long,
        scheduleId: Long?,
        scheduledAt: String,
        status: MedicationLogStatus
    ): Boolean = mutationMutex.withLock {
        val medication = medications.medicationById(medicationId) ?: return@withLock false
        if (members.byId(medication.memberId)?.archived != false) return@withLock false
        if (scheduleId != null && medications.scheduleById(scheduleId)?.medicationId != medicationId) {
            return@withLock false
        }
        medications.insertLog(
            MedicationLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledAt = scheduledAt,
                actualAt = if (status == MedicationLogStatus.TAKEN) LocalDateTime.now().toString() else null,
                status = status.name,
                doseAmountSnapshot = medication.doseAmount,
                doseUnitSnapshot = medication.doseUnit,
                createdAt = Instant.now().toString()
            )
        ) > 0
    }

    suspend fun cleanupOrphanedAttachmentSets() = mutationMutex.withLock {
        val referenced = attachments.all().map { File(attachmentStore.file(it).absolutePath).canonicalPath }
        val restoredRoot = File(attachmentStore.storageRoot, "restored_attachments")
        restoredRoot.listFiles()?.filter { candidate ->
            referenced.none { it.startsWith(candidate.canonicalPath + File.separator) }
        }?.forEach { it.deleteRecursively() }
    }

    suspend fun enabledFollowUps() = followUps.enabledSchedules()
    suspend fun allFollowUpsForAlarmMaintenance() = followUps.allSchedules()
    suspend fun enabledMedicationSchedules() = medications.enabledSchedules()
    suspend fun allMedicationSchedulesForAlarmMaintenance() = medications.allSchedules()
    suspend fun medicationById(id: Long) = medications.medicationById(id)
    suspend fun memberForFollowUp(id: Long): FamilyMemberEntity? =
        followUps.scheduleById(id)?.let { members.byId(it.memberId) }
    suspend fun memberForMedication(id: Long): FamilyMemberEntity? =
        medications.medicationById(id)?.let { members.byId(it.memberId) }

    private suspend fun requireActiveMember(memberId: Long) {
        require(members.byId(memberId)?.archived == false) { "所选家庭成员不存在或已归档" }
    }

    private suspend fun requireConditionForMember(conditionId: Long?, memberId: Long) {
        if (conditionId == null) return
        require(conditions.byId(conditionId)?.memberId == memberId) { "病情分类不属于当前家庭成员" }
    }
}
