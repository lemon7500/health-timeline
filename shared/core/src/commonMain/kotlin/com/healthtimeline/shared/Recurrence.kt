package com.healthtimeline.shared

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

enum class RecurrenceKind { ONCE, EVERY_N_DAYS, EVERY_N_WEEKS, EVERY_N_MONTHS }

data class RecurrenceRule(
    val type: RecurrenceKind,
    val interval: Int,
    val anchorDate: String,
    val anchorDayOfMonth: Int,
    val weekday: Int? = null
)

object SharedRecurrenceCalculator {
    fun nextAfter(rule: RecurrenceRule, after: LocalDate): LocalDate? {
        require(rule.interval > 0)
        return when (rule.type) {
            RecurrenceKind.ONCE -> null
            RecurrenceKind.EVERY_N_DAYS -> after.plus(DatePeriod(days = rule.interval))
            RecurrenceKind.EVERY_N_WEEKS -> {
                val targetWeek = after.plus(DatePeriod(days = 7 * rule.interval))
                val targetWeekday = rule.weekday ?: (after.dayOfWeek.ordinal + 1)
                require(targetWeekday in 1..7)
                targetWeek.plus(DatePeriod(days = targetWeekday - (targetWeek.dayOfWeek.ordinal + 1)))
            }
            RecurrenceKind.EVERY_N_MONTHS -> {
                val monthIndex = after.year * 12 + after.month.ordinal + rule.interval
                val year = monthIndex.floorDiv(12)
                val month = monthIndex.mod(12) + 1
                LocalDate(year, month, minOf(rule.anchorDayOfMonth, daysInMonth(year, month)))
            }
        }
    }

    fun firstWeeklyOnOrAfter(anchor: LocalDate, weekday: Int): LocalDate {
        require(weekday in 1..7)
        val delta = (weekday - (anchor.dayOfWeek.ordinal + 1) + 7) % 7
        return anchor.plus(DatePeriod(days = delta))
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        4, 6, 9, 11 -> 30
        2 -> if (year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)) 29 else 28
        else -> 31
    }
}
