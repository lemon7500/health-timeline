package com.healthtimeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class MedicationTimePickerTest {
    @Test fun `minute choices advance in five minute intervals`() {
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55), MEDICATION_MINUTE_OPTIONS)
    }

    @Test fun `initial time rounds to nearest five minutes`() {
        assertEquals(LocalTime.of(8, 0), roundToFiveMinutes(LocalTime.of(8, 2)))
        assertEquals(LocalTime.of(8, 5), roundToFiveMinutes(LocalTime.of(8, 3)))
        assertEquals(LocalTime.MIDNIGHT, roundToFiveMinutes(LocalTime.of(23, 59)))
    }
}
