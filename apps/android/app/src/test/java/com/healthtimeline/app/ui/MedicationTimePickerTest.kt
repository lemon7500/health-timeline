package com.healthtimeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MedicationTimePickerTest {
    @Test fun `minute choices advance in five minute intervals`() {
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55), MEDICATION_MINUTE_OPTIONS)
    }

    @Test fun `daily reminder clock rounds to nearest five minutes`() {
        assertEquals(LocalTime.of(8, 0), roundToFiveMinutes(LocalTime.of(8, 2)))
        assertEquals(LocalTime.of(8, 5), roundToFiveMinutes(LocalTime.of(8, 3)))
        assertEquals(LocalTime.MIDNIGHT, roundToFiveMinutes(LocalTime.of(23, 59)))
    }

    @Test fun `today actual dose default at midnight boundary stays on the selected day`() {
        val date = LocalDate.of(2026, 10, 11)
        for (minute in listOf(58, 59)) {
            val now = date.atTime(23, minute, 59)
            val actual = date.atTime(defaultMedicationActualTime(date, now = now))
            assertEquals(date.atTime(23, 55), actual)
            assertFalse(actual.isAfter(now))
        }
    }

    @Test fun `actual dose defaults always use a past or current five minute slot`() {
        val date = LocalDate.of(2026, 10, 11)
        for (minute in 0 until 24 * 60) {
            val now = date.atStartOfDay().plusMinutes(minute.toLong()).plusSeconds(59)
            val time = defaultMedicationActualTime(date, now = now)
            assertEquals(0, time.minute % 5)
            assertEquals(0, time.second)
            assertEquals(0, time.nano)
            assertFalse(date.atTime(time).isAfter(now))
        }
    }

    @Test fun `past day actual dose default uses planned time without changing the day`() {
        val date = LocalDate.of(2026, 9, 30)
        val now = LocalDateTime.of(2026, 10, 1, 0, 2)
        assertEquals(date.atTime(8, 0), date.atTime(defaultMedicationActualTime(date, date.atTime(8, 3), now)))
        assertEquals(LocalTime.NOON, defaultMedicationActualTime(date, now = now))
    }
}
