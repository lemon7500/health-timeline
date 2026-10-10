@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.healthtimeline.shared

import kotlin.time.Instant

object MergePlanner {
    fun preview(localSource: PortableSnapshot, importedSource: PortableSnapshot): BackupImportPreview {
        val local = localSource.canonicalized()
        val imported = importedSource.canonicalized()
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
                    current == incoming || (current is PortableClinicalRecord && incoming is PortableClinicalRecord &&
                        current.copy(dayOrder = incoming.dayOrder) == incoming) -> duplicates++
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

        inspect("member", local.members, imported.members, { it.uuid }, { it.updatedAt })
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
        localSource: PortableSnapshot,
        importedSource: PortableSnapshot,
        decisions: Map<String, MergeChoice> = emptyMap()
    ): PortableSnapshot {
        val local = localSource.canonicalized()
        val imported = importedSource.canonicalized()
        PortableSnapshotValidator.validate(local)
        PortableSnapshotValidator.validate(imported)
        val canonicalDecisions = decisions.mapKeys { (key, _) ->
            val separator = key.indexOf(':')
            if (separator < 0) key else key.substring(0, separator + 1) + key.substring(separator + 1).lowercase()
        }
        fun key(type: String, uuid: String) = "$type:$uuid"
        fun <T> mergeList(type: String, localValues: List<T>, incomingValues: List<T>, uuid: (T) -> String): List<T> {
            val incomingById = incomingValues.associateBy(uuid)
            val merged = localValues.map { current ->
                val id = uuid(current)
                val incoming = incomingById[id]
                if (incoming != null && canonicalDecisions[key(type, id)] == MergeChoice.USE_IMPORTED) incoming else current
            }.toMutableList()
            val localIds = localValues.mapTo(hashSetOf(), uuid)
            merged += incomingValues.filter { uuid(it) !in localIds }
            return merged
        }

        val result = local.copy(
            exportedAt = imported.exportedAt,
            members = mergeList("member", local.members, imported.members) { it.uuid },
            conditions = mergeList("condition", local.conditions, imported.conditions) { it.uuid },
            records = mergeRecords(local.records, imported.records, canonicalDecisions),
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

    private fun mergeRecords(
        local: List<PortableClinicalRecord>,
        imported: List<PortableClinicalRecord>,
        decisions: Map<String, MergeChoice>
    ): List<PortableClinicalRecord> {
        val importedById = imported.associateBy { it.uuid }
        val localIds = local.mapTo(hashSetOf()) { it.uuid }
        val retained = local.withIndex().sortedWith(compareBy({ it.value.dayOrder }, { it.index })).map { (_, current) ->
            importedById[current.uuid]?.takeIf { decisions["record:${current.uuid}"] == MergeChoice.USE_IMPORTED }
                ?.copy(dayOrder = current.dayOrder) ?: current
        }
        val additions = imported.withIndex().filter { it.value.uuid !in localIds }
            .sortedWith(compareBy({ it.value.dayOrder }, { it.index })).map { it.value }
        val combined = retained + additions
        val denseDays = combined.filter { it.dayOrder == Long.MAX_VALUE }.mapTo(hashSetOf()) { it.memberUuid to it.recordDate }
        val nextOrder = mutableMapOf<Pair<String?, String>, Long>()
        return combined.map { record ->
            val day = record.memberUuid to record.recordDate
            val next = nextOrder[day] ?: 0L
            val order = if (day in denseDays) next else maxOf(record.dayOrder, next)
            nextOrder[day] = order + 1L
            record.copy(dayOrder = order)
        }
    }
}
