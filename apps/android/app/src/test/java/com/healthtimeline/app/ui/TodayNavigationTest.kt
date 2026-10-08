package com.healthtimeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

class TodayNavigationTest {
    @Test fun eachActionHasItsOwnTokenAndPinnedDateAndMember() {
        val date = LocalDate.of(2026, 12, 31)
        val first = TodayNavigation.AddRecord(2, date)
        val second = TodayNavigation.AddRecord(2, date)
        assertNotEquals(first.token, second.token)
        assertEquals(2L, first.memberId)
        assertEquals(date, first.date)
    }

    @Test fun matchingConsumptionIsIdempotent() {
        val request = TodayNavigation.MedicationDay(1, LocalDate.of(2027, 1, 1))
        assertNull(consumeTodayNavigation(request, request.token))
        assertNull(consumeTodayNavigation(null, request.token))
    }

    @Test fun lateConsumptionCannotClearNewerNavigation() {
        val old = TodayNavigation.Safety(1)
        val current = TodayNavigation.AddRecord(2, LocalDate.of(2026, 10, 8))
        assertSame(current, consumeTodayNavigation(current, old.token))
    }
}
