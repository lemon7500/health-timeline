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

    val conditionFlow = conditions.observeAll()
    val recordFlow = records.observeAll()
    val attachmentFlow = attachments.observeAll()
    val followUpFlow = followUps.observeSchedules()
    val occurrenceFlow = followUps.observeOccurrences()
    val medicationFlow = medications.observeMedications()
    val medicationScheduleFlow = medications.observeSchedules()
    val medicationLogFlow = medications.observeLogs()

    val todayDoses: Flow<List<TodayDose>> = combine(
        medicationFlow,
        medicationScheduleFlow,
        medicationLogFlow
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

    suspend fun saveCondition(value: ConditionEntity): Long = mutationMutex.withLock {
        if (value.id == 0L) conditions.insert(value) else {
            conditions.update(value); value.id
        }
    }

    suspend fun archiveCondition(id: Long) = mutationMutex.withLock { conditions.archive(id) }

    suspend fun saveRecord(value: ClinicalRecordEntity): Long = mutationMutex.withLock {
        if (value.id == 0L) records.insert(value) else {
            records.update(value); value.id
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
        if (value.id == 0L) followUps.insertSchedule(value) else {
            followUps.updateSchedule(value); value.id
        }
    }

    suspend fun deleteFollowUp(value: FollowUpScheduleEntity) = mutationMutex.withLock { followUps.deleteSchedule(value) }

    suspend fun processDueFollowUp(scheduleId: Long, dueDate: LocalDate): FollowUpScheduleEntity? =
        mutationMutex.withLock {
            database.withTransaction {
                val schedule = followUps.scheduleById(scheduleId) ?: return@withTransaction null
                if (!schedule.enabled || schedule.nextDueDate != dueDate.toString()) return@withTransaction null
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
            val id = if (value.id == 0L) medications.insertMedication(value) else {
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
}
