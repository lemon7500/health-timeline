package com.healthtimeline.app.ui

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

data class TodayClockSnapshot(val instant: Instant, val zone: ZoneId) {
    val date get() = instant.atZone(zone).toLocalDate()

    /** Refresh at midnight even when it falls before the next minute tick. */
    fun nextRefreshMillis(): Long {
        val midnight = date.plusDays(1).atStartOfDay(zone).toInstant()
        return Duration.between(instant, midnight).toMillis().coerceIn(1L, 60_000L)
    }
}
