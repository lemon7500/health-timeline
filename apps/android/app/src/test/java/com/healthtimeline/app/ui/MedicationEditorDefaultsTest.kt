package com.healthtimeline.app.ui

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MedicationEditorDefaultsTest {
    private val today = LocalDate.of(2026, 10, 9)

    @Test fun runningCourseDefaultsToTomorrow() {
        assertEquals(today.plusDays(1), defaultMedicationEffectiveDate(today, today.minusDays(10)))
    }

    @Test fun futureCourseDefaultsNoEarlierThanItsStart() {
        assertEquals(today.plusDays(7), defaultMedicationEffectiveDate(today, today.plusDays(7)))
    }

    @Test fun lateScheduleWithFewerTimesInvalidatesPickerWithoutIndexCrash() {
        val morning = LocalTime.of(8, 0)
        val evening = LocalTime.of(20, 0)
        assertEquals(evening, medicationTimeForEditing(listOf(morning, evening), 1))
        assertNull(medicationTimeForEditing(listOf(morning), 1))
        assertNull(medicationTimeForEditing(emptyList(), 0))
        assertNull(medicationTimeForEditing(listOf(morning), -1))
        assertNull(medicationTimeForEditing(listOf(morning), null))
    }
}
