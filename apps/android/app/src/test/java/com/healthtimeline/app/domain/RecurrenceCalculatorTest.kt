package com.healthtimeline.app.domain

import com.healthtimeline.app.data.FollowUpScheduleEntity
import com.healthtimeline.app.data.RecurrenceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RecurrenceCalculatorTest {
    @Test fun `July 9 every three months becomes October 9`() {
        val schedule = schedule(RecurrenceType.EVERY_N_MONTHS, 3, "2026-07-09", 9)
        assertEquals(LocalDate.of(2026, 10, 9), RecurrenceCalculator.nextAfter(schedule, LocalDate.of(2026, 7, 9)))
    }

    @Test fun `month end fallback preserves original anchor`() {
        val schedule = schedule(RecurrenceType.EVERY_N_MONTHS, 1, "2025-01-31", 31)
        val february = RecurrenceCalculator.nextAfter(schedule, LocalDate.of(2025, 1, 31))!!
        assertEquals(LocalDate.of(2025, 2, 28), february)
        assertEquals(LocalDate.of(2025, 3, 31), RecurrenceCalculator.nextAfter(schedule, february))
    }

    @Test fun `weekly Thursday crosses year boundary`() {
        val schedule = schedule(RecurrenceType.EVERY_N_WEEKS, 1, "2026-12-31", 31, weekday = 4)
        assertEquals(LocalDate.of(2027, 1, 7), RecurrenceCalculator.nextAfter(schedule, LocalDate.of(2026, 12, 31)))
    }

    @Test fun `weekly first due selects Thursday on or after anchor`() {
        assertEquals(LocalDate.of(2026, 9, 3), RecurrenceCalculator.firstWeeklyOnOrAfter(LocalDate.of(2026, 9, 1), 4))
    }

    @Test fun `one time schedule has no next date`() {
        assertNull(RecurrenceCalculator.nextAfter(schedule(RecurrenceType.ONCE, 1, "2026-09-01", 1), LocalDate.of(2026, 9, 1)))
    }

    private fun schedule(type: RecurrenceType, interval: Int, anchor: String, day: Int, weekday: Int? = null) =
        FollowUpScheduleEntity(
            id = 1, title = "test", recurrenceType = type.name, interval = interval,
            anchorDate = anchor, anchorDayOfMonth = day, weekday = weekday,
            nextDueDate = anchor, createdAt = "now", updatedAt = "now"
        )
}
