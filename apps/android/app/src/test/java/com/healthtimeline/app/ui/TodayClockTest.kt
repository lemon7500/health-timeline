package com.healthtimeline.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TodayClockTest {
    @Test fun midnightRefreshIsNotDelayedUntilNextMinute() {
        val clock = TodayClockSnapshot(Instant.parse("2026-12-31T15:59:50Z"), ZoneId.of("Asia/Shanghai"))
        assertEquals(LocalDate.of(2026, 12, 31), clock.date)
        assertEquals(10_000L, clock.nextRefreshMillis())
        assertEquals(LocalDate.of(2027, 1, 1), clock.copy(instant = clock.instant.plusSeconds(10)).date)
    }

    @Test fun foregroundRefreshUsesNewZone() {
        val instant = Instant.parse("2026-10-08T18:00:00Z")
        assertEquals(LocalDate.of(2026, 10, 9), TodayClockSnapshot(instant, ZoneId.of("Asia/Shanghai")).date)
        assertEquals(LocalDate.of(2026, 10, 8), TodayClockSnapshot(instant, ZoneId.of("America/New_York")).date)
    }

    @Test fun refreshIsBoundedAndHandlesDaylightSaving() {
        val clock = TodayClockSnapshot(Instant.parse("2026-03-08T06:30:00Z"), ZoneId.of("America/New_York"))
        assertEquals(60_000L, clock.nextRefreshMillis())
    }
}
