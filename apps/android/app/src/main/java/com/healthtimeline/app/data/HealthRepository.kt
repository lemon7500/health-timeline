package com.healthtimeline.app.data

import android.net.Uri
import androidx.room.withTransaction
import com.healthtimeline.app.domain.ClinicalRecordQuickParser
import com.healthtimeline.app.domain.RecurrenceCalculator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

sealed interface RecordConditionResolution {
    data object Selected : RecordConditionResolution
    data class Create(val name: String) : RecordConditionResolution
    data class Restore(val conditionId: Long) : RecordConditionResolution
}

data class ClinicalRecordSaveRequest(
    val record: ClinicalRecordEntity,
    val conditionResolution: RecordConditionResolution = RecordConditionResolution.Selected
)

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
    fun trashForMember(memberId: Long): Flow<List<TrashItem>> = combine(
        records.observeDeletedForMember(memberId),
        attachments.observeDeletedForMember(memberId),
        followUps.observeDeletedForMember(memberId),
        medications.observeDeletedForMember(memberId)
    ) { deletedRecords, deletedAttachments, deletedFollowUps, deletedMedications ->
        buildList {
            deletedRecords.forEach {
                add(TrashItem(TrashItemType.CLINICAL_RECORD, it.id, it.uuid, it.title, it.recordDate, requireNotNull(it.deletedAt)))
            }
            deletedAttachments.forEach {
                add(TrashItem(TrashItemType.ATTACHMENT, it.id, it.uuid, it.displayName, "所属病历报告", requireNotNull(it.deletedAt)))
            }
            deletedFollowUps.forEach {
                add(TrashItem(TrashItemType.FOLLOW_UP, it.id, it.uuid, it.title, "下次 ${it.nextDueDate}", requireNotNull(it.deletedAt)))
            }
            deletedMedications.forEach {
                add(TrashItem(TrashItemType.MEDICATION, it.id, it.uuid, it.name, "${it.doseAmount} ${it.doseUnit}", requireNotNull(it.deletedAt)))
            }
        }.sortedWith(compareByDescending<TrashItem> { it.deletedAt }.thenByDescending { it.id })
    }

    fun medicationMonthForMember(memberId: Long, month: YearMonth): Flow<MedicationMonthData> {
        val firstDate = month.atDay(1)
        val lastDate = month.atEndOfMonth()
        val startAt = firstDate.atStartOfDay().toString()
        val endAt = month.plusMonths(1).atDay(1).atStartOfDay().toString()
        return combine(
            medicationsForMember(memberId),
            medications.observeSchedulesForMemberBetween(memberId, firstDate.toString(), lastDate.toString()),
            medications.observeLogsForMemberBetween(memberId, startAt, endAt)
        ) { medicines, schedules, logs ->
            projectMedicationMonth(month, medicines, schedules, logs)
        }
    }

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
                        .filter {
                            it.medicationId == medication.id && it.enabled &&
                                !LocalDate.parse(it.effectiveFrom).isAfter(today) &&
                                (it.effectiveTo == null || !LocalDate.parse(it.effectiveTo).isBefore(today))
                        }
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
        require(value.relationship.isNotBlank() && value.relationship.length <= 30) { "请填写 30 字以内的关系" }
        val canonical = value.copy(nickname = value.relationship)
        database.withTransaction {
            if (canonical.id == 0L) members.insert(canonical) else {
                val existing = requireNotNull(members.byId(canonical.id)) { "家庭成员不存在" }
                require(existing.uuid == canonical.uuid) { "不能更改家庭成员永久标识" }
                require(existing.archived == canonical.archived) { "请使用归档或恢复操作更改成员状态" }
                members.update(canonical)
                canonical.id
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

    suspend fun saveRecord(
        value: ClinicalRecordEntity,
        conditionResolution: RecordConditionResolution = RecordConditionResolution.Selected
    ): Long = mutationMutex.withLock {
        database.withTransaction {
            saveRecordInTransaction(value, conditionResolution)
        }
    }

    suspend fun saveRecords(requests: List<ClinicalRecordSaveRequest>): List<Long> = mutationMutex.withLock {
        require(requests.isNotEmpty()) { "没有可保存的病历" }
        require(requests.size <= ClinicalRecordQuickParser.MAX_BATCH_RECORDS) { "一次保存的病历过多" }
        database.withTransaction {
            requests.map { saveRecordInTransaction(it.record, it.conditionResolution) }
        }
    }

    private suspend fun saveRecordInTransaction(
        value: ClinicalRecordEntity,
        conditionResolution: RecordConditionResolution
    ): Long {
        requireActiveMember(value.memberId)
        val resolvedConditionId = when (conditionResolution) {
            RecordConditionResolution.Selected -> value.conditionId
            is RecordConditionResolution.Create -> {
                val name = conditionResolution.name.trim()
                require(name.isNotBlank() && name.length <= 50) { "病情分类须为 1 至 50 个字符" }
                val normalizedName = ClinicalRecordQuickParser.normalizeConditionName(name)
                val existing = conditions.all().firstOrNull {
                    it.memberId == value.memberId &&
                        ClinicalRecordQuickParser.normalizeConditionName(it.name) == normalizedName
                }
                require(existing?.archived != true) { "同名病情分类已归档，请先选择恢复并使用" }
                existing?.id ?: conditions.insert(
                    ConditionEntity(
                        name = name,
                        createdAt = Instant.now().toString(),
                        memberId = value.memberId
                    )
                )
            }
            is RecordConditionResolution.Restore -> {
                val condition = requireNotNull(conditions.byId(conditionResolution.conditionId)) {
                    "要恢复的病情分类不存在"
                }
                require(condition.memberId == value.memberId) { "不能使用其他成员的病情分类" }
                if (condition.archived) conditions.update(condition.copy(archived = false))
                condition.id
            }
        }
        requireConditionForMember(resolvedConditionId, value.memberId)
        val resolvedValue = if (value.id == 0L) {
            value.copy(
                conditionId = resolvedConditionId,
                dayOrder = records.maxDayOrder(value.memberId, value.recordDate) + 1L
            )
        } else {
            val existing = requireNotNull(records.byId(value.id)) { "病历不存在" }
            require(existing.deletedAt == null) { "病历已在回收站，请先恢复" }
            value.copy(
                conditionId = resolvedConditionId,
                dayOrder = if (existing.recordDate == value.recordDate) {
                    existing.dayOrder
                } else {
                    records.maxDayOrder(value.memberId, value.recordDate) + 1L
                }
            )
        }
        return if (resolvedValue.id == 0L) records.insert(resolvedValue) else {
            val existing = requireNotNull(records.byId(value.id)) { "病历不存在" }
            require(existing.deletedAt == null) { "病历已在回收站，请先恢复" }
            require(existing.memberId == resolvedValue.memberId) { "不能更改病历所属成员" }
            require(existing.uuid == resolvedValue.uuid) { "不能更改病历永久标识" }
            records.update(resolvedValue)
            resolvedValue.id
        }
    }

    suspend fun deleteRecord(value: ClinicalRecordEntity) = mutationMutex.withLock {
        database.withTransaction {
            val current = requireNotNull(records.byId(value.id)) { "病历不存在" }
            require(current.uuid == value.uuid) { "病历已经发生变化，请刷新后重试" }
            if (current.deletedAt != null) return@withTransaction
            val now = Instant.now().toString()
            check(records.moveToTrash(current.id, now) == 1) { "病历移入回收站失败" }
            attachments.moveActiveForRecordToTrash(current.id, now)
        }
    }

    suspend fun importAttachment(recordId: Long, uri: Uri) = mutationMutex.withLock {
        attachmentStore.import(recordId, uri)
    }
    suspend fun deleteAttachment(value: AttachmentEntity): AttachmentDeleteResult = mutationMutex.withLock {
        val current = attachments.byId(value.id) ?: return@withLock AttachmentDeleteResult.ALREADY_DELETED
        if (current.uuid != value.uuid) return@withLock AttachmentDeleteResult.ALREADY_DELETED
        if (current.deletedAt != null) return@withLock AttachmentDeleteResult.ALREADY_DELETED
        val parent = requireNotNull(records.byId(current.recordId)) { "所属病历不存在" }
        require(parent.deletedAt == null) { "所属病历已在回收站" }
        check(attachments.moveToTrash(current.id, Instant.now().toString()) == 1) { "检查报告移入回收站失败" }
        AttachmentDeleteResult.MOVED_TO_TRASH
    }

    suspend fun saveFollowUp(value: FollowUpScheduleEntity): Long = mutationMutex.withLock {
        database.withTransaction {
            requireActiveMember(value.memberId)
            requireConditionForMember(value.conditionId, value.memberId)
            if (value.id == 0L) followUps.insertSchedule(value) else {
                val existing = requireNotNull(followUps.scheduleById(value.id)) { "复查计划不存在" }
                require(existing.deletedAt == null) { "复查计划已在回收站，请先恢复" }
                require(existing.memberId == value.memberId) { "不能更改复查计划所属成员" }
                require(existing.uuid == value.uuid) { "不能更改复查计划永久标识" }
                followUps.updateSchedule(value); value.id
            }
        }
    }

    suspend fun deleteFollowUp(value: FollowUpScheduleEntity) = mutationMutex.withLock {
        val current = requireNotNull(followUps.scheduleById(value.id)) { "复查计划不存在" }
        require(current.uuid == value.uuid) { "复查计划已经发生变化，请刷新后重试" }
        if (current.deletedAt == null) check(followUps.moveToTrash(current.id, Instant.now().toString()) == 1)
    }

    suspend fun processDueFollowUp(scheduleId: Long, dueDate: LocalDate): FollowUpScheduleEntity? =
        mutationMutex.withLock {
            database.withTransaction {
                val schedule = followUps.scheduleById(scheduleId) ?: return@withTransaction null
                if (!schedule.enabled || schedule.deletedAt != null || schedule.nextDueDate != dueDate.toString()) return@withTransaction null
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
                if (!schedule.enabled || schedule.deletedAt != null || schedule.nextDueDate != occurrence.dueDate) {
                    return@withTransaction null
                }
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

    suspend fun saveMedication(
        value: MedicationEntity,
        times: List<LocalTime>,
        effectiveDate: LocalDate? = null
    ): MedicationSaveResult = mutationMutex.withLock {
        database.withTransaction {
            requireActiveMember(value.memberId)
            requireConditionForMember(value.conditionId, value.memberId)
            require(value.name.isNotBlank() && value.name.length <= 100) { "请填写 100 字以内的药名" }
            require(value.doseAmount.isNotBlank() && value.doseAmount.length <= 30) { "请填写 30 字以内的每次剂量" }
            require(value.doseUnit.isNotBlank() && value.doseUnit.length <= 20) { "请填写 20 字以内的剂量单位" }
            require(value.instructions.length <= 1_000) { "服用说明不能超过 1000 字" }
            require(value.mode in MedicationMode.entries.map { it.name }) { "用药记录方式异常" }
            require(value.mode != MedicationMode.SCHEDULED.name || times.isNotEmpty()) { "定时用药至少需要一个提醒时间" }
            require(times.all { it.minute % 5 == 0 && it.second == 0 && it.nano == 0 }) { "提醒时间须按 5 分钟选择" }
            val today = LocalDate.now()
            val startsOn = LocalDate.parse(value.startDate)
            val endsOn = value.endDate?.let(LocalDate::parse)
            require(endsOn == null || !endsOn.isBefore(startsOn)) { "用药结束日期不能早于开始日期" }
            if (value.id != 0L) {
                require(endsOn == null || !endsOn.isBefore(today)) { "不能把已有疗程追溯结束到今天以前" }
            }
            val changeDate = if (value.id == 0L) startsOn else (effectiveDate ?: today.plusDays(1))
            if (value.id != 0L) {
                require(!changeDate.isBefore(today)) { "不能追溯修改过去的用药计划" }
                if (changeDate == today) {
                    require(
                        medications.logCountBetween(
                            value.id,
                            today.atStartOfDay().toString(),
                            today.plusDays(1).atStartOfDay().toString()
                        ) == 0
                    ) { "今天已经有用药记录，新计划请从明天生效" }
                }
            }
            val id = if (value.id == 0L) medications.insertMedication(value) else {
                val existing = requireNotNull(medications.medicationById(value.id)) { "药物不存在" }
                require(existing.deletedAt == null) { "药物已在回收站，请先恢复" }
                require(existing.memberId == value.memberId) { "不能更改药物所属成员" }
                require(existing.uuid == value.uuid) { "不能更改药物永久标识" }
                medications.updateMedication(value); value.id
            }
            val now = Instant.now().toString()
            val replacedIds = mutableListOf<Long>()
            if (value.id != 0L) {
                medications.schedulesForMedication(id)
                    .filter { it.enabled && (it.effectiveTo == null || !LocalDate.parse(it.effectiveTo).isBefore(changeDate)) }
                    .forEach { schedule ->
                        replacedIds += schedule.id
                        val scheduleStart = LocalDate.parse(schedule.effectiveFrom)
                        if (!scheduleStart.isBefore(changeDate)) {
                            check(medications.deleteScheduleById(schedule.id) == 1) { "无法替换尚未生效的用药计划" }
                        } else {
                            val replacementEnd = listOfNotNull(changeDate.minusDays(1), endsOn).minOrNull()
                            medications.updateSchedule(
                                schedule.copy(
                                    enabled = false,
                                    effectiveTo = replacementEnd.toString(),
                                    updatedAt = now
                                )
                            )
                        }
                    }
            }
            if (
                value.mode == MedicationMode.SCHEDULED.name &&
                (endsOn == null || !endsOn.isBefore(changeDate))
            ) {
                times.distinct().sorted().forEach { time ->
                    medications.insertSchedule(
                        MedicationScheduleEntity(
                            medicationId = id,
                            localTime = time.toString(),
                            effectiveFrom = changeDate.toString(),
                            effectiveTo = endsOn?.toString(),
                            doseAmountSnapshot = value.doseAmount,
                            doseUnitSnapshot = value.doseUnit,
                            updatedAt = now
                        )
                    )
                }
            }
            MedicationSaveResult(id, replacedIds)
        }
    }

    suspend fun archiveMedication(id: Long) = mutationMutex.withLock {
        database.withTransaction {
            val today = LocalDate.now()
            val now = Instant.now().toString()
            val medication = requireNotNull(medications.medicationById(id)) { "药物不存在" }
            require(medication.deletedAt == null) { "药物已在回收站，请先恢复" }
            if (medication.archived) return@withTransaction
            medications.schedulesForMedication(id).filter { it.enabled }.forEach { schedule ->
                medications.updateSchedule(
                    schedule.copy(enabled = false, updatedAt = now, pausedByCourseEnd = true)
                )
            }
            check(medications.archiveMedication(id, today.toString(), now) == 1) { "结束疗程失败，请重试" }
        }
    }

    suspend fun restoreMedication(id: Long) = mutationMutex.withLock {
        database.withTransaction {
            val today = LocalDate.now()
            val now = Instant.now().toString()
            val medication = requireNotNull(medications.medicationById(id)) { "药物不存在" }
            require(medication.deletedAt == null) { "药物已在回收站，请先恢复" }
            if (!medication.archived) return@withTransaction
            val restoredEnd = medication.archivedPreviousEndDate?.let(LocalDate::parse)
            val canContinue = restoredEnd == null || !restoredEnd.isBefore(today)
            val schedules = medications.schedulesForMedication(id)
            var restoredSchedule = false
            schedules.filter { it.pausedByCourseEnd }.forEach { schedule ->
                val startsBeforeCourseEnds = restoredEnd == null || !LocalDate.parse(schedule.effectiveFrom).isAfter(restoredEnd)
                val scheduleStillRelevant = schedule.effectiveTo?.let(LocalDate::parse)?.isBefore(today) != true
                val shouldEnable = canContinue && startsBeforeCourseEnds && scheduleStillRelevant
                medications.updateSchedule(
                    schedule.copy(enabled = shouldEnable, pausedByCourseEnd = false, updatedAt = now)
                )
                restoredSchedule = restoredSchedule || shouldEnable
            }
            // Very old ended courses may not have a recoverable paused marker. Recreate only
            // the latest schedule as a new version; historical rows and dose logs remain intact.
            if (canContinue && medication.mode == MedicationMode.SCHEDULED.name && !restoredSchedule) {
                val latestFrom = schedules.maxOfOrNull { it.effectiveFrom }
                schedules.filter { it.effectiveFrom == latestFrom }.distinctBy { it.localTime }.forEach { latest ->
                    medications.insertSchedule(
                        MedicationScheduleEntity(
                            medicationId = id,
                            localTime = latest.localTime,
                            effectiveFrom = today.toString(),
                            effectiveTo = restoredEnd?.toString(),
                            doseAmountSnapshot = medication.doseAmount,
                            doseUnitSnapshot = medication.doseUnit,
                            updatedAt = now
                        )
                    )
                }
            }
            check(medications.restoreMedication(id, now) == 1) { "恢复疗程失败，请重试" }
        }
    }

    suspend fun deleteMedication(id: Long) = mutationMutex.withLock {
        val medication = requireNotNull(medications.medicationById(id)) { "药物不存在" }
        if (medication.deletedAt == null) {
            check(medications.moveToTrash(id, Instant.now().toString()) == 1) { "药物移入回收站失败" }
        }
    }

    suspend fun recordDose(
        medicationId: Long,
        scheduleId: Long?,
        scheduledAt: LocalDateTime,
        actualAt: LocalDateTime?,
        status: MedicationLogStatus
    ): Boolean = mutationMutex.withLock {
        val medication = medications.medicationById(medicationId) ?: return@withLock false
        if (medication.deletedAt != null) return@withLock false
        if (members.byId(medication.memberId)?.archived != false) return@withLock false
        require(!scheduledAt.toLocalDate().isAfter(LocalDate.now())) { "未来日期不能提前记录用药" }
        require(status != MedicationLogStatus.TAKEN || actualAt != null) { "已服记录必须填写实际服药时间" }
        require(actualAt == null || actualAt.toLocalDate() == scheduledAt.toLocalDate()) { "实际服药时间须与所选日期一致" }
        val schedule = scheduleId?.let { medications.scheduleById(it) }
        if (scheduleId != null && schedule?.medicationId != medicationId) return@withLock false
        if (schedule != null) {
            val date = scheduledAt.toLocalDate()
            require(!date.isBefore(LocalDate.parse(schedule.effectiveFrom))) { "该计划在所选日期尚未生效" }
            require(schedule.effectiveTo == null || !date.isAfter(LocalDate.parse(schedule.effectiveTo))) {
                "该计划在所选日期已经结束"
            }
        }
        val now = Instant.now().toString()
        medications.insertLog(
            MedicationLogEntity(
                medicationId = medicationId,
                scheduleId = scheduleId,
                scheduledAt = scheduledAt.toString(),
                actualAt = if (status == MedicationLogStatus.TAKEN) requireNotNull(actualAt).toString() else null,
                status = status.name,
                doseAmountSnapshot = schedule?.doseAmountSnapshot?.ifBlank { medication.doseAmount } ?: medication.doseAmount,
                doseUnitSnapshot = schedule?.doseUnitSnapshot?.ifBlank { medication.doseUnit } ?: medication.doseUnit,
                createdAt = now,
                updatedAt = now
            )
        ) > 0
    }

    suspend fun markDose(
        medicationId: Long,
        scheduleId: Long?,
        scheduledAt: String,
        status: MedicationLogStatus
    ): Boolean {
        val planned = LocalDateTime.parse(scheduledAt)
        return recordDose(
            medicationId,
            scheduleId,
            planned,
            if (status == MedicationLogStatus.TAKEN) LocalDateTime.now() else null,
            status
        )
    }

    suspend fun correctDose(
        logId: Long,
        status: MedicationLogStatus,
        actualAt: LocalDateTime?
    ): Boolean = mutationMutex.withLock {
        database.withTransaction {
            val existing = medications.logById(logId) ?: return@withTransaction false
            val medication = medications.medicationById(existing.medicationId) ?: return@withTransaction false
            if (medication.deletedAt != null) return@withTransaction false
            if (members.byId(medication.memberId)?.archived != false) return@withTransaction false
            val recordDate = LocalDateTime.parse(existing.scheduledAt).toLocalDate()
            require(!recordDate.isAfter(LocalDate.now())) { "未来日期不能修改用药记录" }
            require(status != MedicationLogStatus.TAKEN || actualAt != null) { "已服记录必须填写实际服药时间" }
            require(actualAt == null || actualAt.toLocalDate() == recordDate) { "实际服药时间须与记录日期一致" }
            medications.updateLog(
                existing.copy(
                    status = status.name,
                    actualAt = if (status == MedicationLogStatus.TAKEN) requireNotNull(actualAt).toString() else null,
                    updatedAt = Instant.now().toString()
                )
            ) == 1
        }
    }

    suspend fun restoreTrash(item: TrashItem) = mutationMutex.withLock {
        val now = Instant.now().toString()
        when (item.type) {
            TrashItemType.CLINICAL_RECORD -> {
                val record = requireNotNull(records.byId(item.id)) { "回收站中的病历不存在" }
                require(record.uuid == item.uuid && record.deletedAt != null) { "病历状态已经改变，请刷新后重试" }
                requireActiveMember(record.memberId)
                val deletedAt = requireNotNull(record.deletedAt)
                val related = attachments.forRecord(record.id).filter { it.deletedAt == deletedAt }
                related.forEach { attachment ->
                    require(attachmentStore.verifyStored(attachment)) {
                        "病历附件缺失或校验失败，无法恢复；请从安全备份恢复"
                    }
                }
                database.withTransaction {
                    val current = requireNotNull(records.byId(item.id)) { "回收站中的病历不存在" }
                    require(current.uuid == item.uuid && current.deletedAt == deletedAt) { "病历状态已经改变，请刷新后重试" }
                    check(records.restore(record.id, now) == 1) { "恢复病历失败" }
                    attachments.restoreWithRecord(record.id, deletedAt, now)
                }
            }
            TrashItemType.ATTACHMENT -> {
                val attachment = requireNotNull(attachments.byId(item.id)) { "回收站中的检查报告不存在" }
                require(attachment.uuid == item.uuid && attachment.deletedAt != null) { "检查报告状态已经改变，请刷新后重试" }
                val record = requireNotNull(records.byId(attachment.recordId)) { "所属病历不存在" }
                require(record.deletedAt == null) { "请先恢复所属病历" }
                requireActiveMember(record.memberId)
                require(attachmentStore.verifyStored(attachment)) { "附件文件缺失或校验失败，无法恢复；请从安全备份恢复" }
                check(attachments.restore(attachment.id, now) == 1) { "恢复检查报告失败" }
            }
            TrashItemType.FOLLOW_UP -> database.withTransaction {
                val schedule = requireNotNull(followUps.scheduleById(item.id)) { "回收站中的复查计划不存在" }
                require(schedule.uuid == item.uuid && schedule.deletedAt != null) { "复查计划状态已经改变，请刷新后重试" }
                requireActiveMember(schedule.memberId)
                check(followUps.restore(schedule.id, now) == 1) { "恢复复查计划失败" }
            }
            TrashItemType.MEDICATION -> database.withTransaction {
                val medication = requireNotNull(medications.medicationById(item.id)) { "回收站中的药物不存在" }
                require(medication.uuid == item.uuid && medication.deletedAt != null) { "药物状态已经改变，请刷新后重试" }
                requireActiveMember(medication.memberId)
                check(medications.restoreDeleted(medication.id, now) == 1) { "恢复药物失败" }
            }
        }
    }

    suspend fun permanentlyDeleteTrash(item: TrashItem): AttachmentDeleteResult? = mutationMutex.withLock {
        when (item.type) {
            TrashItemType.CLINICAL_RECORD -> {
                val record = requireNotNull(records.byId(item.id)) { "回收站中的病历不存在" }
                require(record.uuid == item.uuid && record.deletedAt != null) { "病历状态已经改变，请刷新后重试" }
                permanentlyDeleteRecordLocked(record)
                null
            }
            TrashItemType.ATTACHMENT -> {
                val attachment = attachments.byId(item.id) ?: return@withLock AttachmentDeleteResult.ALREADY_DELETED
                require(attachment.uuid == item.uuid && attachment.deletedAt != null) { "检查报告状态已经改变，请刷新后重试" }
                permanentlyDeleteAttachmentLocked(attachment)
            }
            TrashItemType.FOLLOW_UP -> {
                val schedule = requireNotNull(followUps.scheduleById(item.id)) { "回收站中的复查计划不存在" }
                require(schedule.uuid == item.uuid && schedule.deletedAt != null) { "复查计划状态已经改变，请刷新后重试" }
                check(followUps.hardDelete(schedule.id) == 1) { "永久删除复查计划失败" }
                null
            }
            TrashItemType.MEDICATION -> {
                val medication = requireNotNull(medications.medicationById(item.id)) { "回收站中的药物不存在" }
                require(medication.uuid == item.uuid && medication.deletedAt != null) { "药物状态已经改变，请刷新后重试" }
                check(medications.hardDelete(medication.id) == 1) { "永久删除药物失败" }
                null
            }
        }
    }

    suspend fun purgeExpiredTrash(now: Instant = Instant.now()): Int = mutationMutex.withLock {
        val cutoff = now.minus(Duration.ofDays(TRASH_RETENTION_DAYS)).toString()
        var purged = 0
        records.deletedBefore(cutoff).forEach { record ->
            if (runCatching { permanentlyDeleteRecordLocked(record) }.isSuccess) purged++
        }
        attachments.individuallyDeletedBefore(cutoff).forEach { attachment ->
            if (runCatching { permanentlyDeleteAttachmentLocked(attachment) }.isSuccess) purged++
        }
        followUps.deletedBefore(cutoff).forEach { schedule ->
            if (runCatching { check(followUps.hardDelete(schedule.id) == 1) }.isSuccess) purged++
        }
        medications.deletedBefore(cutoff).forEach { medication ->
            if (runCatching { check(medications.hardDelete(medication.id) == 1) }.isSuccess) purged++
        }
        purged
    }

    private suspend fun permanentlyDeleteRecordLocked(record: ClinicalRecordEntity) {
        val related = attachments.forRecord(record.id)
        val staged = mutableListOf<AttachmentStore.StagedDeletion>()
        try {
            related.forEach { staged += attachmentStore.stageDeletion(it) }
            check(database.withTransaction { records.hardDelete(record.id) } == 1) { "永久删除病历失败" }
            staged.forEach { attachmentStore.commitDeletion(it) }
        } catch (error: Throwable) {
            staged.asReversed().forEach { runCatching { attachmentStore.rollbackDeletion(it) } }
            throw error
        }
    }

    private suspend fun permanentlyDeleteAttachmentLocked(attachment: AttachmentEntity): AttachmentDeleteResult {
        val staged = attachmentStore.stageDeletion(attachment)
        val fileWasMissing = staged.original == null
        try {
            val deleted = database.withTransaction { attachments.deleteById(attachment.id) }
            if (deleted == 0) {
                attachmentStore.rollbackDeletion(staged)
                return AttachmentDeleteResult.ALREADY_DELETED
            }
            attachmentStore.commitDeletion(staged)
            return if (fileWasMissing) AttachmentDeleteResult.MISSING_FILE_RECORD_REMOVED else AttachmentDeleteResult.DELETED
        } catch (error: Throwable) {
            attachmentStore.rollbackDeletion(staged)
            throw error
        }
    }

    suspend fun cleanupOrphanedAttachmentSets() = mutationMutex.withLock {
        attachmentStore.cleanupDeletionTrash()
        val referenced = attachments.all().map { File(attachmentStore.file(it).absolutePath).canonicalPath }
        val restoredRoot = File(attachmentStore.storageRoot, "restored_attachments")
        restoredRoot.listFiles()?.filter { candidate ->
            referenced.none { it.startsWith(candidate.canonicalPath + File.separator) }
        }?.forEach { it.deleteRecursively() }
    }

    suspend fun enabledFollowUps() = followUps.enabledSchedules()
    suspend fun allFollowUpsForAlarmMaintenance() = followUps.allSchedules()
    suspend fun enabledMedicationSchedules() = medications.enabledSchedules(LocalDate.now().toString())
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

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
    }
}

internal fun projectMedicationMonth(
    month: YearMonth,
    medicines: List<MedicationEntity>,
    schedules: List<MedicationScheduleEntity>,
    logs: List<MedicationLogEntity>
): MedicationMonthData {
    val firstDate = month.atDay(1)
    val lastDate = month.atEndOfMonth()
    val medicinesById = medicines.associateBy { it.id }
    val schedulesById = schedules.associateBy { it.id }
    val logsByKey = logs.associateBy { it.medicationId to it.scheduledAt }
    val consumedLogIds = hashSetOf<Long>()
    val entries = linkedMapOf<LocalDate, MutableList<MedicationDayEntry>>()

    schedules.forEach { schedule ->
        val medication = medicinesById[schedule.medicationId] ?: return@forEach
        var date = maxOf(firstDate, LocalDate.parse(schedule.effectiveFrom), LocalDate.parse(medication.startDate))
        val scheduleEnd = schedule.effectiveTo?.let(LocalDate::parse)
        val medicationEnd = medication.endDate?.let(LocalDate::parse)
        val end = listOfNotNull(lastDate, scheduleEnd, medicationEnd).minOrNull() ?: lastDate
        val time = LocalTime.parse(schedule.localTime)
        while (!date.isAfter(end)) {
            val plannedAt = date.atTime(time)
            val log = logsByKey[medication.id to plannedAt.toString()]
            if (log != null) consumedLogIds += log.id
            entries.getOrPut(date) { mutableListOf() } += MedicationDayEntry(
                medication = medication,
                schedule = schedule,
                log = log,
                plannedAt = plannedAt,
                actualAt = log?.actualAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() },
                status = when (log?.status) {
                    MedicationLogStatus.TAKEN.name -> MedicationDayStatus.TAKEN
                    MedicationLogStatus.SKIPPED.name -> MedicationDayStatus.SKIPPED
                    else -> MedicationDayStatus.UNRECORDED
                }
            )
            date = date.plusDays(1)
        }
    }

    logs.filterNot { it.id in consumedLogIds }.forEach { log ->
        val medication = medicinesById[log.medicationId] ?: return@forEach
        val scheduledAt = runCatching { LocalDateTime.parse(log.scheduledAt) }.getOrNull() ?: return@forEach
        if (YearMonth.from(scheduledAt) != month) return@forEach
        entries.getOrPut(scheduledAt.toLocalDate()) { mutableListOf() } += MedicationDayEntry(
            medication = medication,
            schedule = log.scheduleId?.let(schedulesById::get),
            log = log,
            plannedAt = if (log.scheduleId == null) null else scheduledAt,
            actualAt = log.actualAt?.let { value -> runCatching { LocalDateTime.parse(value) }.getOrNull() },
            status = if (log.status == MedicationLogStatus.TAKEN.name) MedicationDayStatus.TAKEN else MedicationDayStatus.SKIPPED
        )
    }

    val sorted = entries.mapValues { (_, values) ->
        values.sortedWith(
            compareBy<MedicationDayEntry> { it.plannedAt ?: it.actualAt }
                .thenBy { it.actualAt }
                .thenBy { it.medication.name }
        )
    }
    return MedicationMonthData(month, sorted)
}
