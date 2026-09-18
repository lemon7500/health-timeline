package com.healthtimeline.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.UUID
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

enum class VisitStage { BEFORE_VISIT, AFTER_VISIT, CHECKUP, SURGERY, OTHER }
enum class AttachmentKind { IMAGE, PDF }
enum class AttachmentDeleteResult { MOVED_TO_TRASH, DELETED, MISSING_FILE_RECORD_REMOVED, ALREADY_DELETED }
enum class TrashItemType { CLINICAL_RECORD, ATTACHMENT, FOLLOW_UP, MEDICATION }
enum class RecurrenceType { ONCE, EVERY_N_DAYS, EVERY_N_WEEKS, EVERY_N_MONTHS }
enum class OccurrenceStatus { PENDING, DONE, SKIPPED }
enum class MedicationMode { SCHEDULED, AS_NEEDED }
enum class MedicationLogStatus { TAKEN, SKIPPED }
enum class MedicationDayStatus { UNRECORDED, TAKEN, SKIPPED }

@Entity(
    tableName = "family_members",
    indices = [Index(value = ["uuid"], unique = true), Index("archived")]
)
data class FamilyMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nickname: String,
    val relationship: String,
    val archived: Boolean = false,
    val createdAt: String,
    val updatedAt: String,
    val uuid: String = UUID.randomUUID().toString()
)

@Entity(
    tableName = "conditions",
    foreignKeys = [ForeignKey(
        entity = FamilyMemberEntity::class,
        parentColumns = ["id"],
        childColumns = ["memberId"],
        onDelete = ForeignKey.RESTRICT
    )],
    indices = [Index("memberId"), Index(value = ["uuid"], unique = true)]
)
data class ConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long = 0xFF2F6B56,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: String,
    val uuid: String = UUID.randomUUID().toString(),
    val memberId: Long = 1L
)

@Entity(
    tableName = "clinical_records",
    foreignKeys = [
        ForeignKey(
            entity = ConditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["conditionId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = FamilyMemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [
        Index("conditionId"),
        Index("memberId"),
        Index("recordDate"),
        Index("deletedAt"),
        Index(value = ["memberId", "deletedAt"]),
        Index(value = ["memberId", "recordDate", "dayOrder"]),
        Index(value = ["uuid"], unique = true)
    ]
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
    val updatedAt: String,
    val uuid: String = UUID.randomUUID().toString(),
    val memberId: Long = 1L,
    @ColumnInfo(defaultValue = "0") val dayOrder: Long = 0L,
    val deletedAt: String? = null
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(
        entity = ClinicalRecordEntity::class,
        parentColumns = ["id"],
        childColumns = ["recordId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordId"), Index("deletedAt"), Index(value = ["uuid"], unique = true)]
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
    val createdAt: String,
    val uuid: String = UUID.randomUUID().toString(),
    val deletedAt: String? = null,
    @ColumnInfo(defaultValue = "'1970-01-01T00:00:00Z'") val updatedAt: String = createdAt
)

@Entity(
    tableName = "follow_up_schedules",
    foreignKeys = [
        ForeignKey(
            entity = ConditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["conditionId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = FamilyMemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("conditionId"), Index("memberId"), Index("nextDueDate"), Index("deletedAt"), Index(value = ["memberId", "deletedAt"]), Index(value = ["uuid"], unique = true)]
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
    val leadDays: Int = 3,
    val nextDueDate: String,
    val enabled: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
    val uuid: String = UUID.randomUUID().toString(),
    val memberId: Long = 1L,
    val deletedAt: String? = null
)

@Entity(
    tableName = "follow_up_occurrences",
    foreignKeys = [ForeignKey(
        entity = FollowUpScheduleEntity::class,
        parentColumns = ["id"],
        childColumns = ["scheduleId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("scheduleId"), Index(value = ["scheduleId", "dueDate"], unique = true), Index(value = ["uuid"], unique = true)]
)
data class FollowUpOccurrenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val dueDate: String,
    val status: String = OccurrenceStatus.PENDING.name,
    val completedAt: String? = null,
    val createdAt: String,
    val uuid: String = UUID.randomUUID().toString()
)

@Entity(
    tableName = "medications",
    foreignKeys = [
        ForeignKey(
            entity = ConditionEntity::class,
            parentColumns = ["id"],
            childColumns = ["conditionId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = FamilyMemberEntity::class,
            parentColumns = ["id"],
            childColumns = ["memberId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("conditionId"), Index("memberId"), Index("startDate"), Index("deletedAt"), Index(value = ["memberId", "deletedAt"]), Index(value = ["uuid"], unique = true)]
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
    val updatedAt: String,
    val uuid: String = UUID.randomUUID().toString(),
    val memberId: Long = 1L,
    /** Exact instant at which the user ended this course. Null while active. */
    val endedAt: String? = null,
    /** Planned end date before archiving, used to undo an accidental course end. */
    val archivedPreviousEndDate: String? = null,
    val deletedAt: String? = null
)

@Entity(
    tableName = "medication_schedules",
    foreignKeys = [ForeignKey(
        entity = MedicationEntity::class,
        parentColumns = ["id"],
        childColumns = ["medicationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index("medicationId"),
        Index("effectiveFrom"),
        Index("effectiveTo"),
        Index(value = ["uuid"], unique = true)
    ]
)
data class MedicationScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicationId: Long,
    val localTime: String,
    val enabled: Boolean = true,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "'1970-01-01'") val effectiveFrom: String = "1970-01-01",
    val effectiveTo: String? = null,
    @ColumnInfo(defaultValue = "''") val doseAmountSnapshot: String = "",
    @ColumnInfo(defaultValue = "''") val doseUnitSnapshot: String = "",
    @ColumnInfo(defaultValue = "'1970-01-01T00:00:00Z'") val updatedAt: String = "1970-01-01T00:00:00Z",
    /** True only when this schedule was disabled by ending its medication course. */
    @ColumnInfo(defaultValue = "0") val pausedByCourseEnd: Boolean = false
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
        Index("scheduledAt"),
        Index(value = ["medicationId", "scheduledAt"], unique = true),
        Index(value = ["uuid"], unique = true)
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
    val createdAt: String,
    val uuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "'1970-01-01T00:00:00Z'") val updatedAt: String = createdAt
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

data class MedicationDayEntry(
    val medication: MedicationEntity,
    val schedule: MedicationScheduleEntity?,
    val log: MedicationLogEntity?,
    val plannedAt: LocalDateTime?,
    val actualAt: LocalDateTime?,
    val status: MedicationDayStatus
)

data class MedicationMonthData(
    val month: YearMonth,
    val entriesByDate: Map<LocalDate, List<MedicationDayEntry>>
) {
    fun entries(date: LocalDate): List<MedicationDayEntry> = entriesByDate[date].orEmpty()
}

data class TrashItem(
    val type: TrashItemType,
    val id: Long,
    val uuid: String,
    val title: String,
    val detail: String,
    val deletedAt: String
)
