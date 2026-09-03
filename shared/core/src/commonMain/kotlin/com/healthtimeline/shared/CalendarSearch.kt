package com.healthtimeline.shared

fun searchPortableRecords(
    records: List<PortableClinicalRecord>,
    conditions: List<PortableCondition>,
    query: String
): List<PortableClinicalRecord> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val conditionNames = conditions.associate { it.uuid to it.name }
    return records.filter { record ->
        matchesCalendarSearch(needle, listOf(
            record.title,
            record.conditionUuid?.let(conditionNames::get).orEmpty(),
            record.symptoms,
            record.diagnosis,
            record.treatment,
            record.medicationNotes,
            record.hospital,
            record.clinician,
            record.notes
        ))
    }.sortedWith(compareByDescending<PortableClinicalRecord> { it.recordDate }.thenByDescending { it.updatedAt })
}

fun matchesCalendarSearch(query: String, fields: Iterable<String>): Boolean {
    val needle = query.trim()
    return needle.isNotEmpty() && fields.any { it.contains(needle, ignoreCase = true) }
}
