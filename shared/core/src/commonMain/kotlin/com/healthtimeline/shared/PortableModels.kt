package com.healthtimeline.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val BACKUP_SCHEMA_VERSION = 3
const val LEGACY_PORTABLE_BACKUP_SCHEMA_VERSION = 2

@Serializable
data class PortableSnapshot(
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val exportedAt: String,
    val sourcePlatform: String,
    val sourceInstallationId: String,
    val members: List<PortableFamilyMember> = emptyList(),
    val conditions: List<PortableCondition> = emptyList(),
    val records: List<PortableClinicalRecord> = emptyList(),
    val attachments: List<PortableAttachment> = emptyList(),
    val followUps: List<PortableFollowUpSchedule> = emptyList(),
    val occurrences: List<PortableFollowUpOccurrence> = emptyList(),
    val medications: List<PortableMedication> = emptyList(),
    val medicationSchedules: List<PortableMedicationSchedule> = emptyList(),
    val medicationLogs: List<PortableMedicationLog> = emptyList()
)

@Serializable
data class PortableFamilyMember(
    val uuid: String,
    val name: String,
    val nickname: String,
    val relationship: String,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@Serializable
data class PortableCondition(
    val uuid: String,
    val name: String,
    val color: Long,
    val notes: String,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String = createdAt,
    val memberUuid: String? = null
)

@Serializable
data class PortableClinicalRecord(
    val uuid: String,
    val conditionUuid: String? = null,
    val recordDate: String,
    val title: String,
    val stage: String,
    val symptoms: String,
    val diagnosis: String,
    val treatment: String,
    val medicationNotes: String,
    val hospital: String,
    val clinician: String,
    val notes: String,
    val createdAt: String,
    val updatedAt: String,
    val memberUuid: String? = null
)

@Serializable
data class PortableAttachment(
    val uuid: String,
    val recordUuid: String,
    val kind: String,
    val displayName: String,
    val mimeType: String,
    val archivePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: String,
    val updatedAt: String = createdAt
)

@Serializable
data class PortableFollowUpSchedule(
    val uuid: String,
    val conditionUuid: String? = null,
    val title: String,
    val recurrenceType: String,
    val interval: Int,
    val anchorDate: String,
    val anchorDayOfMonth: Int,
    val weekday: Int? = null,
    val reminderTime: String,
    val leadDays: Int,
    val nextDueDate: String,
    val enabled: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val memberUuid: String? = null
)

@Serializable
data class PortableFollowUpOccurrence(
    val uuid: String,
    val scheduleUuid: String,
    val dueDate: String,
    val status: String,
    val completedAt: String? = null,
    val createdAt: String,
    val updatedAt: String = completedAt ?: createdAt
)

@Serializable
data class PortableMedication(
    val uuid: String,
    val conditionUuid: String? = null,
    val name: String,
    val doseAmount: String,
    val doseUnit: String,
    val instructions: String,
    val startDate: String,
    val endDate: String? = null,
    val mode: String,
    val archived: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val memberUuid: String? = null
)

@Serializable
data class PortableMedicationSchedule(
    val uuid: String,
    val medicationUuid: String,
    val localTime: String,
    val enabled: Boolean,
    val updatedAt: String
)

@Serializable
data class PortableMedicationLog(
    val uuid: String,
    val medicationUuid: String,
    val scheduleUuid: String? = null,
    val scheduledAt: String,
    val actualAt: String? = null,
    val status: String,
    val doseAmountSnapshot: String,
    val doseUnitSnapshot: String,
    val createdAt: String,
    val updatedAt: String = actualAt ?: createdAt
)

object PortableJson {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        prettyPrint = false
    }

    fun encode(snapshot: PortableSnapshot): String {
        PortableSnapshotValidator.validate(snapshot)
        return json.encodeToString(PortableSnapshot.serializer(), snapshot)
    }

    fun decode(value: String): PortableSnapshot =
        json.decodeFromString(PortableSnapshot.serializer(), value).also(PortableSnapshotValidator::validate)
}
