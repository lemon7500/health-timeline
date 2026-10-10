package com.healthtimeline.app.reminders

import java.time.LocalDateTime

/** A navigation request only. It never authorizes a medication write. */
data class NotificationReviewRequest(
    val medicationId: Long,
    val scheduleId: Long,
    val scheduledAt: String
) {
    fun plannedTimeOrNull(): LocalDateTime? =
        if (medicationId <= 0 || scheduleId <= 0 || scheduledAt.length > 40) null
        else runCatching { LocalDateTime.parse(scheduledAt) }.getOrNull()
}
