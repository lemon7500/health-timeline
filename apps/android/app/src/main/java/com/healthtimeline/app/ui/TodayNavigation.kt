package com.healthtimeline.app.ui

import com.healthtimeline.app.data.TodayFollowUpItem
import java.time.LocalDate
import java.util.UUID

/** In-memory, one-shot destinations. Never persisted with medical data. */
sealed class TodayNavigation(val memberId: Long, val token: String = UUID.randomUUID().toString()) {
    class AddRecord(memberId: Long, val date: LocalDate) : TodayNavigation(memberId)
    class MedicationDay(memberId: Long, val date: LocalDate) : TodayNavigation(memberId)
    class FollowUpDetail(memberId: Long, val item: TodayFollowUpItem) : TodayNavigation(memberId)
    class Safety(memberId: Long) : TodayNavigation(memberId)
}

/** Late callbacks must not consume a newer request. */
internal fun consumeTodayNavigation(request: TodayNavigation?, consumedToken: String): TodayNavigation? =
    request?.takeUnless { it.token == consumedToken }
