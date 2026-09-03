package com.healthtimeline.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class VisitStage { BEFORE_VISIT, AFTER_VISIT, CHECKUP, SURGERY, OTHER }
enum class AttachmentKind { IMAGE, PDF }
enum class RecurrenceType { ONCE, EVERY_N_DAYS, EVERY_N_WEEKS, EVERY_N_MONTHS }
enum class OccurrenceStatus { PENDING, DONE, SKIPPED }
enum class MedicationMode { SCHEDULED, AS_NEEDED }
enum class MedicationLogStatus { TAKEN, SKIPPED }

@Entity(tableName = "conditions")
data class ConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF2F6B56,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: String
)

@Entity(
    tableName = "clinical_records",
    foreignKeys = [ForeignKey(
        entity = ConditionEntity::class,
        parentColumns = ["id"],
        childColumns = ["conditionId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("conditionId"), Index("recordDate")]
)
data class ClinicalRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conditionId: Long? = null,
    val recordDate: String,
    val title: String,
    val stage: String = VisitStage.OTHER.name,
    val symptoms: String = "",
    val diagnosis: String = "",
    val treatment: String = "",
    val medicationNotes: String = "",
    val hospital: String = "",
    val clinician: String = "",
    val notes: String = "",
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = ClinicalRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId")]
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val kind: String,
    val displayName: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: String
)

@Entity(
    tableName = "follow_up_schedules",
    foreignKeys = [ForeignKey(
        entity = ConditionEntity::class,
        parentColumns = ["id"],
        childColumns = ["conditionId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("conditionId"), Index("nextDueDate")]
)
data class FollowUpScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conditionId: Long? = null,
    val title: String,
    val recurrenceType: String,
    val interval: Int = 1,
    val anchorDate: String,
    val anchorDayOfMonth: Int,
    val weekday: Int? = null,
    val reminderTime: String = "09:00",
    val leadDays: Int = 0,
    val nextDueDate: String,
    val enabled: Boolean = true,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "follow_up_occurrences",
    foreignKeys = [ForeignKey(
        entity = FollowUpScheduleEntity::class,
        parentColumns = ["id"],
        childColumns = ["scheduleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scheduleId"), Index(value = ["scheduleId", "dueDate"], unique = true)]
)
data class FollowUpOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val dueDate: String,
    val status: String = OccurrenceStatus.PENDING.name,
    val completedAt: String? = null,
    val createdAt: String
)

@Entity(
    tableName = "medications",
    foreignKeys = [ForeignKey(
        entity = ConditionEntity::class,
        parentColumns = ["id"],
        childColumns = ["conditionId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("conditionId"), Index("startDate")]
)
data class MedicationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conditionId: Long? = null,
    val name: String,
    val doseAmount: String,
    val doseUnit: String,
    val instructions: String = "",
    val startDate: String,
    val endDate: String? = null,
    val mode: String = MedicationMode.SCHEDULED.name,
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String
)

@Entity(
    tableName = "medication_schedules",
    foreignKeys = [ForeignKey(
        entity = MedicationEntity::class,
        parentColumns = ["id"],
        childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("medicationId")]
)
data class MedicationScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val localTime: String,
    val enabled: Boolean = true
)

@Entity(
    tableName = "medication_logs",
    foreignKeys = [ForeignKey(
        entity = MedicationEntity::class,
        parentColumns = ["id"],
        childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("medicationId"),
        Index(value = ["medicationId", "scheduledAt"], unique = true)
    ]
)
data class MedicationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val scheduleId: Long? = null,
    val scheduledAt: String,
    val actualAt: String? = null,
    val status: String,
    val doseAmountSnapshot: String,
    val doseUnitSnapshot: String,
    val createdAt: String
)

data class TodayDose(
    val medication: MedicationEntity,
    val schedule: MedicationScheduleEntity?,
    val scheduledAt: String,
    val log: MedicationLogEntity?
)

data class MedicationSaveResult(
    val medicationId: Long,
    val replacedScheduleIds: List<Long>
)
