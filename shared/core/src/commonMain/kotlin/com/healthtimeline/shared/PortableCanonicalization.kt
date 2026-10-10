package com.healthtimeline.shared

/** UUID identity is case-insensitive; canonical wire identifiers and paths are lowercase. */
fun PortableSnapshot.canonicalized(): PortableSnapshot = copy(
    sourceInstallationId = sourceInstallationId.lowercase(),
    members = members.map { it.copy(uuid = it.uuid.lowercase()) },
    conditions = conditions.map { it.copy(uuid = it.uuid.lowercase(), memberUuid = it.memberUuid?.lowercase()) },
    records = records.map {
        it.copy(uuid = it.uuid.lowercase(), memberUuid = it.memberUuid?.lowercase(), conditionUuid = it.conditionUuid?.lowercase())
    },
    attachments = attachments.map {
        it.copy(uuid = it.uuid.lowercase(), recordUuid = it.recordUuid.lowercase(), archivePath = it.archivePath.lowercase())
    },
    followUps = followUps.map {
        it.copy(uuid = it.uuid.lowercase(), memberUuid = it.memberUuid?.lowercase(), conditionUuid = it.conditionUuid?.lowercase())
    },
    occurrences = occurrences.map { it.copy(uuid = it.uuid.lowercase(), scheduleUuid = it.scheduleUuid.lowercase()) },
    medications = medications.map {
        it.copy(uuid = it.uuid.lowercase(), memberUuid = it.memberUuid?.lowercase(), conditionUuid = it.conditionUuid?.lowercase())
    },
    medicationSchedules = medicationSchedules.map { it.copy(uuid = it.uuid.lowercase(), medicationUuid = it.medicationUuid.lowercase()) },
    medicationLogs = medicationLogs.map {
        it.copy(uuid = it.uuid.lowercase(), medicationUuid = it.medicationUuid.lowercase(), scheduleUuid = it.scheduleUuid?.lowercase())
    }
)
