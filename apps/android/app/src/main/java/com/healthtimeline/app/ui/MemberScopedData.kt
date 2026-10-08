package com.healthtimeline.app.ui

import com.healthtimeline.app.data.MedicationMonthData

/** Hide a previous subscription's cached snapshot as soon as selection changes. */
internal fun <T> memberScopedEntities(memberId: Long?, values: List<T>, owner: (T) -> Long): List<T> =
    if (memberId == null) emptyList() else values.filter { owner(it) == memberId }

internal fun <T> childrenOfMember(parentIds: Set<Long>, values: List<T>, parent: (T) -> Long): List<T> =
    values.filter { parent(it) in parentIds }

internal fun memberScopedMedicationMonth(memberId: Long?, value: MedicationMonthData): MedicationMonthData =
    value.copy(entriesByDate = value.entriesByDate.mapValues { (_, entries) ->
        memberScopedEntities(memberId, entries) { it.medication.memberId }.filter { entry ->
            (entry.schedule == null || entry.schedule.medicationId == entry.medication.id) &&
                (entry.log == null || entry.log.medicationId == entry.medication.id)
        }
    }.filterValues { it.isNotEmpty() })
