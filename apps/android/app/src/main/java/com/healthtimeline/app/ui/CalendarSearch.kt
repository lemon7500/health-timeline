package com.healthtimeline.app.ui

import com.healthtimeline.app.data.ClinicalRecordEntity
import com.healthtimeline.app.data.ConditionEntity
import com.healthtimeline.shared.matchesCalendarSearch

internal fun searchCalendarRecords(
    records: List<ClinicalRecordEntity>,
    conditions: List<ConditionEntity>,
    query: String,
    limit: Int = 50
): List<ClinicalRecordEntity> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return emptyList()
    val conditionNames = conditions.associate { it.id to it.name }
    return records.asSequence()
        .filter { record ->
            matchesCalendarSearch(keyword, sequenceOf(
                record.recordDate,
                record.title,
                conditionNames[record.conditionId].orEmpty(),
                visitStageLabel(record.stage),
                record.symptoms,
                record.diagnosis,
                record.treatment,
                record.medicationNotes,
                record.hospital,
                record.clinician,
                record.notes
            ).asIterable())
        }
        .sortedWith(compareByDescending<ClinicalRecordEntity> { it.recordDate }.thenByDescending { it.updatedAt })
        .take(limit.coerceAtLeast(1))
        .toList()
}
