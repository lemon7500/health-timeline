package com.healthtimeline.app.backup

import com.healthtimeline.app.data.*
import com.healthtimeline.shared.*

internal object PortableSnapshotMapper {
    fun toPortable(snapshot: BackupSnapshot, installationId: String, exportedAt: String): PortableSnapshot {
        require(snapshot.members.isNotEmpty()) { "缺少家庭成员，无法导出备份" }
        val members = snapshot.members.associateBy { it.id }
        val conditions = snapshot.conditions.associateBy { it.id }
        val records = snapshot.records.associateBy { it.id }
        val followUps = snapshot.followUps.associateBy { it.id }
        val medications = snapshot.medications.associateBy { it.id }
        val medicationSchedules = snapshot.medicationSchedules.associateBy { it.id }
        return PortableSnapshot(
            exportedAt = exportedAt,
            sourcePlatform = "android",
            sourceInstallationId = installationId,
            members = snapshot.members.map {
                PortableFamilyMember(
                    it.uuid, it.name, it.nickname, it.relationship, it.archived,
                    it.createdAt, it.updatedAt
                )
            },
            conditions = snapshot.conditions.map {
                PortableCondition(
                    it.uuid, it.name, it.color, it.notes, it.archived, it.createdAt,
                    memberUuid = requireNotNull(members[it.memberId]).uuid
                )
            },
            records = snapshot.records.map {
                PortableClinicalRecord(
                    it.uuid, it.conditionId?.let(conditions::get)?.uuid, it.recordDate, it.title, it.stage,
                    it.symptoms, it.diagnosis, it.treatment, it.medicationNotes, it.hospital, it.clinician,
                    it.notes, it.createdAt, it.updatedAt,
                    memberUuid = requireNotNull(members[it.memberId]).uuid
                )
            },
            attachments = snapshot.attachments.map {
                PortableAttachment(
                    it.uuid,
                    requireNotNull(records[it.recordId]).uuid,
                    it.kind,
                    it.displayName,
                    it.mimeType,
                    "files/${it.uuid}",
                    it.sizeBytes,
                    it.sha256,
                    it.createdAt
                )
            },
            followUps = snapshot.followUps.map {
                PortableFollowUpSchedule(
                    it.uuid, it.conditionId?.let(conditions::get)?.uuid, it.title, it.recurrenceType,
                    it.interval, it.anchorDate, it.anchorDayOfMonth, it.weekday, it.reminderTime,
                    it.leadDays, it.nextDueDate, it.enabled, it.createdAt, it.updatedAt,
                    memberUuid = requireNotNull(members[it.memberId]).uuid
                )
            },
            occurrences = snapshot.occurrences.map {
                PortableFollowUpOccurrence(
                    it.uuid, requireNotNull(followUps[it.scheduleId]).uuid, it.dueDate, it.status,
                    it.completedAt, it.createdAt
                )
            },
            medications = snapshot.medications.map {
                PortableMedication(
                    it.uuid, it.conditionId?.let(conditions::get)?.uuid, it.name, it.doseAmount,
                    it.doseUnit, it.instructions, it.startDate, it.endDate, it.mode, it.archived,
                    it.createdAt, it.updatedAt,
                    memberUuid = requireNotNull(members[it.memberId]).uuid
                )
            },
            medicationSchedules = snapshot.medicationSchedules.map {
                val medication = requireNotNull(medications[it.medicationId])
                PortableMedicationSchedule(it.uuid, medication.uuid, it.localTime, it.enabled, medication.updatedAt)
            },
            medicationLogs = snapshot.medicationLogs.map {
                PortableMedicationLog(
                    it.uuid,
                    requireNotNull(medications[it.medicationId]).uuid,
                    it.scheduleId?.let(medicationSchedules::get)?.uuid,
                    it.scheduledAt,
                    it.actualAt,
                    it.status,
                    it.doseAmountSnapshot,
                    it.doseUnitSnapshot,
                    it.createdAt
                )
            }
        ).also(PortableSnapshotValidator::validate)
    }

    fun toEntities(
        portable: PortableSnapshot,
        current: BackupSnapshot,
        attachmentPath: (PortableAttachment, AttachmentEntity?) -> String
    ): BackupSnapshot {
        PortableSnapshotValidator.validate(portable)
        fun ids(uuids: List<String>, existing: Map<String, Long>): Map<String, Long> {
            var next = existing.values.maxOrNull() ?: 0L
            return uuids.associateWith { existing[it] ?: ++next }
        }

        require(portable.schemaVersion == BACKUP_SCHEMA_VERSION) { "备份尚未转换为家庭档案格式" }
        val memberIds = ids(portable.members.map { it.uuid }, current.members.associate { it.uuid to it.id })
        val conditionIds = ids(portable.conditions.map { it.uuid }, current.conditions.associate { it.uuid to it.id })
        val recordIds = ids(portable.records.map { it.uuid }, current.records.associate { it.uuid to it.id })
        val attachmentIds = ids(portable.attachments.map { it.uuid }, current.attachments.associate { it.uuid to it.id })
        val followUpIds = ids(portable.followUps.map { it.uuid }, current.followUps.associate { it.uuid to it.id })
        val occurrenceIds = ids(portable.occurrences.map { it.uuid }, current.occurrences.associate { it.uuid to it.id })
        val medicationIds = ids(portable.medications.map { it.uuid }, current.medications.associate { it.uuid to it.id })
        val medicationScheduleIds = ids(portable.medicationSchedules.map { it.uuid }, current.medicationSchedules.associate { it.uuid to it.id })
        val medicationLogIds = ids(portable.medicationLogs.map { it.uuid }, current.medicationLogs.associate { it.uuid to it.id })
        val currentAttachments = current.attachments.associateBy { it.uuid }

        return BackupSnapshot(
            members = portable.members.map {
                FamilyMemberEntity(
                    memberIds.getValue(it.uuid), it.name, it.nickname, it.relationship, it.archived,
                    it.createdAt, it.updatedAt, it.uuid
                )
            },
            conditions = portable.conditions.map {
                ConditionEntity(
                    conditionIds.getValue(it.uuid), it.name, it.color, it.notes, it.archived,
                    it.createdAt, it.uuid, memberIds.getValue(requireNotNull(it.memberUuid))
                )
            },
            records = portable.records.map {
                ClinicalRecordEntity(
                    recordIds.getValue(it.uuid), it.conditionUuid?.let(conditionIds::get), it.recordDate,
                    it.title, it.stage, it.symptoms, it.diagnosis, it.treatment, it.medicationNotes,
                    it.hospital, it.clinician, it.notes, it.createdAt, it.updatedAt, it.uuid,
                    memberIds.getValue(requireNotNull(it.memberUuid))
                )
            },
            attachments = portable.attachments.map {
                AttachmentEntity(
                    attachmentIds.getValue(it.uuid), recordIds.getValue(it.recordUuid), it.kind,
                    it.displayName, it.mimeType, attachmentPath(it, currentAttachments[it.uuid]),
                    it.sizeBytes, it.sha256, it.createdAt, it.uuid
                )
            },
            followUps = portable.followUps.map {
                FollowUpScheduleEntity(
                    followUpIds.getValue(it.uuid), it.conditionUuid?.let(conditionIds::get), it.title,
                    it.recurrenceType, it.interval, it.anchorDate, it.anchorDayOfMonth, it.weekday,
                    it.reminderTime, it.leadDays, it.nextDueDate, it.enabled, it.createdAt, it.updatedAt, it.uuid,
                    memberIds.getValue(requireNotNull(it.memberUuid))
                )
            },
            occurrences = portable.occurrences.map {
                FollowUpOccurrenceEntity(
                    occurrenceIds.getValue(it.uuid), followUpIds.getValue(it.scheduleUuid), it.dueDate,
                    it.status, it.completedAt, it.createdAt, it.uuid
                )
            },
            medications = portable.medications.map {
                MedicationEntity(
                    medicationIds.getValue(it.uuid), it.conditionUuid?.let(conditionIds::get), it.name,
                    it.doseAmount, it.doseUnit, it.instructions, it.startDate, it.endDate, it.mode,
                    it.archived, it.createdAt, it.updatedAt, it.uuid,
                    memberIds.getValue(requireNotNull(it.memberUuid))
                )
            },
            medicationSchedules = portable.medicationSchedules.map {
                MedicationScheduleEntity(
                    medicationScheduleIds.getValue(it.uuid), medicationIds.getValue(it.medicationUuid),
                    it.localTime, it.enabled, it.uuid
                )
            },
            medicationLogs = portable.medicationLogs.map {
                MedicationLogEntity(
                    medicationLogIds.getValue(it.uuid), medicationIds.getValue(it.medicationUuid),
                    it.scheduleUuid?.let(medicationScheduleIds::get), it.scheduledAt, it.actualAt,
                    it.status, it.doseAmountSnapshot, it.doseUnitSnapshot, it.createdAt, it.uuid
                )
            }
        )
    }
}
