package com.healthtimeline.app.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class NotificationReviewRequestTest {
    @Test fun validRequestOnlyDescribesNavigation() {
        val request = NotificationReviewRequest(1, 2, "2026-10-09T08:05")
        assertEquals(LocalDateTime.of(2026, 10, 9, 8, 5), request.plannedTimeOrNull())
    }

    @Test fun invalidIdsDateAndOversizedTextAreRejected() {
        assertNull(NotificationReviewRequest(0, 2, "2026-10-09T08:05").plannedTimeOrNull())
        assertNull(NotificationReviewRequest(1, -1, "2026-10-09T08:05").plannedTimeOrNull())
        assertNull(NotificationReviewRequest(1, 2, "2026-02-30T08:05").plannedTimeOrNull())
        assertNull(NotificationReviewRequest(1, 2, "x".repeat(41)).plannedTimeOrNull())
    }
}
