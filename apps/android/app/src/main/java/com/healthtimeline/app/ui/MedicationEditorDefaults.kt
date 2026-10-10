package com.healthtimeline.app.ui

import java.time.LocalDate
import java.time.LocalTime

internal fun defaultMedicationEffectiveDate(today: LocalDate, startDate: LocalDate): LocalDate =
    maxOf(today.plusDays(1), startDate)

internal fun medicationTimeForEditing(times: List<LocalTime>, target: Int?): LocalTime? =
    target?.let { times.getOrNull(it) }
