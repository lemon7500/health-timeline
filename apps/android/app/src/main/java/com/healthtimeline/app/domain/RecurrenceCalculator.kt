package com.healthtimeline.app.domain

import com.healthtimeline.app.data.FollowUpScheduleEntity
import com.healthtimeline.app.data.RecurrenceType
import java.time.LocalDate
import com.healthtimeline.shared.RecurrenceKind
import com.healthtimeline.shared.RecurrenceRule
import com.healthtimeline.shared.SharedRecurrenceCalculator

object RecurrenceCalculator {
    fun nextAfter(schedule: FollowUpScheduleEntity, currentDueDate: LocalDate): LocalDate? {
        val rule = RecurrenceRule(
            type = RecurrenceKind.valueOf(RecurrenceType.valueOf(schedule.recurrenceType).name),
            interval = schedule.interval.coerceAtLeast(1),
            anchorDate = schedule.anchorDate,
            anchorDayOfMonth = schedule.anchorDayOfMonth.coerceIn(1, 31),
            weekday = schedule.weekday
        )
        return SharedRecurrenceCalculator.nextAfter(
            rule,
            kotlinx.datetime.LocalDate.parse(currentDueDate.toString())
        )?.let { LocalDate.parse(it.toString()) }
    }

    fun firstWeeklyOnOrAfter(anchor: LocalDate, weekday: Int): LocalDate {
        return SharedRecurrenceCalculator.firstWeeklyOnOrAfter(
            kotlinx.datetime.LocalDate.parse(anchor.toString()),
            weekday.coerceIn(1, 7)
        ).let { LocalDate.parse(it.toString()) }
    }
}
