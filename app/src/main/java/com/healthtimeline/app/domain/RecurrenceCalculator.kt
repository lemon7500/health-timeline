package com.healthtimeline.app.domain

import com.healthtimeline.app.data.FollowUpScheduleEntity
import com.healthtimeline.app.data.RecurrenceType
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.min

object RecurrenceCalculator {
    fun nextAfter(schedule: FollowUpScheduleEntity, currentDueDate: LocalDate): LocalDate? {
        val interval = schedule.interval.coerceAtLeast(1).toLong()
        return when (RecurrenceType.valueOf(schedule.recurrenceType)) {
            RecurrenceType.ONCE -> null
            RecurrenceType.EVERY_N_DAYS -> currentDueDate.plusDays(interval)
            RecurrenceType.EVERY_N_WEEKS -> {
                val target = DayOfWeek.of(schedule.weekday ?: currentDueDate.dayOfWeek.value)
                currentDueDate.plusWeeks(interval).with(target)
            }
            RecurrenceType.EVERY_N_MONTHS -> {
                val targetMonth = currentDueDate.plusMonths(interval)
                targetMonth.withDayOfMonth(
                    min(schedule.anchorDayOfMonth.coerceAtLeast(1), targetMonth.lengthOfMonth())
                )
            }
        }
    }

    fun firstWeeklyOnOrAfter(anchor: LocalDate, weekday: Int): LocalDate {
        val target = DayOfWeek.of(weekday.coerceIn(1, 7))
        val delta = (target.value - anchor.dayOfWeek.value + 7) % 7
        return anchor.plusDays(delta.toLong())
    }
}
