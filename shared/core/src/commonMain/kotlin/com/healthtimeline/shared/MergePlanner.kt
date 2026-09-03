@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.healthtimeline.shared

import kotlin.time.Instant

object MergePlanner {
    fun preview(local: PortableSnapshot, imported: PortableSnapshot): BackupImportPreview {
        PortableSnapshotValidator.validate(local)
        PortableSnapshotValidator.validate(imported)
        var additions = 0
        var updates = 0
        var duplicates = 0
        val conflicts = mutableListOf<MergeConflict>()

        fun <T> inspect(
            type: String,
            localValues: List<T>,
            importedValues: List<T>,
            uuid: (T) -> String,
            updatedAt: (T) -> String
        ) {
            val localById = localValues.associateBy(uuid)
            importedValues.forEach { incoming ->
                val id = uuid(incoming)
                val current = localById[id]
                when {
                    current == null -> additions++
                    current == incoming -> duplicates++
                    else -> {
                        val localTime = updatedAt(current)
                        val importedTime = updatedAt(incoming)
                        val importedIsNewer = Instant.parse(importedTime) > Instant.parse(localTime)
                        if (importedIsNewer) updates++
                        conflicts += MergeConflict(type, id, localTime, importedTime, importedIsNewer)
                    }
                }
            }
        }

        inspect("condition", local.conditions, imported.conditions, { it.uuid }, { it.updatedAt })
        inspect("record", local.records, imported.records, { it.uuid }, { it.updatedAt })
        inspect("attachment", local.attachments, imported.attachments, { it.uuid }, { it.updatedAt })
        inspect("followUp", local.followUps, imported.followUps, { it.uuid }, { it.updatedAt })
        inspect("occurrence", local.occurrences, imported.occurrences, { it.uuid }, { it.updatedAt })
        inspect("medication", local.medications, imported.medications, { it.uuid }, { it.updatedAt })
        inspect("medicationSchedule", local.medicationSchedules, imported.medicationSchedules, { it.uuid }, { it.updatedAt })
        inspect("medicationLog", local.medicationLogs, imported.medicationLogs, { it.uuid }, { it.updatedAt })
        return BackupImportPreview(additions, updates, duplicates, conflicts)
    }

    fun merge(
        local: PortableSnapshot,
        imported: PortableSnapshot,
        decisions: Map<String, MergeChoice> = emptyMap()
    ): PortableSnapshot {
        fun key(type: String, uuid: String) = "$type:$uuid"
        fun <T> mergeList(type: String, localValues: List<T>, incomingValues: List<T>, uuid: (T) -> String): List<T> {
            val incomingById = incomingValues.associateBy(uuid)
            val merged = localValues.map { current ->
                val id = uuid(current)
                val incoming = incomingById[id]
                if (incoming != null && decisions[key(type, id)] == MergeChoice.USE_IMPORTED) incoming else current
            }.toMutableList()
            val localIds = localValues.mapTo(hashSetOf(), uuid)
            merged += incomingValues.filter { uuid(it) !in localIds }
            return merged
        }

        val result = local.copy(
            exportedAt = imported.exportedAt,
            conditions = mergeList("condition", local.conditions, imported.conditions) { it.uuid },
            records = mergeList("record", local.records, imported.records) { it.uuid },
            attachments = mergeList("attachment", local.attachments, imported.attachments) { it.uuid },
            followUps = mergeList("followUp", local.followUps, imported.followUps) { it.uuid },
            occurrences = mergeList("occurrence", local.occurrences, imported.occurrences) { it.uuid },
            medications = mergeList("medication", local.medications, imported.medications) { it.uuid },
            medicationSchedules = mergeList("medicationSchedule", local.medicationSchedules, imported.medicationSchedules) { it.uuid },
            medicationLogs = mergeList("medicationLog", local.medicationLogs, imported.medicationLogs) { it.uuid }
        )
        PortableSnapshotValidator.validate(result)
        return result
    }
}
