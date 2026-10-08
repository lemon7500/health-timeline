package com.healthtimeline.app.data

import java.time.LocalDate

data class TodayFollowUpItem(
    val schedule: FollowUpScheduleEntity,
    val occurrence: FollowUpOccurrenceEntity?,
    val dueDate: LocalDate,
    val conditionName: String?
)

data class TodayOverviewData(
    val memberId: Long,
    val date: LocalDate,
    val medicationEntries: List<MedicationDayEntry> = emptyList(),
    val asNeededMedications: List<MedicationEntity> = emptyList(),
    val overdueFollowUps: List<TodayFollowUpItem> = emptyList(),
    val upcomingFollowUps: List<TodayFollowUpItem> = emptyList()
)

internal fun projectTodayOverview(
    memberId: Long,
    date: LocalDate,
    medicines: List<MedicationEntity>,
    medicationSchedules: List<MedicationScheduleEntity>,
    medicationLogs: List<MedicationLogEntity>,
    schedules: List<FollowUpScheduleEntity>,
    occurrences: List<FollowUpOccurrenceEntity>,
    conditions: List<ConditionEntity>
): TodayOverviewData {
    val visibleMedicines = medicines.filter { it.memberId == memberId && it.deletedAt == null }
    // The medication's current mode is changed immediately by an edit. Its old
    // timed schedule can still be the authoritative plan until tomorrow.
    val timedMedicationIds = medicationSchedules.filter {
        !LocalDate.parse(it.effectiveFrom).isAfter(date) &&
            (it.effectiveTo == null || !LocalDate.parse(it.effectiveTo).isBefore(date))
    }.mapTo(hashSetOf()) { it.medicationId }
    val visibleSchedules = schedules.filter { it.memberId == memberId && it.deletedAt == null }
    val conditionsById = conditions.filter { it.memberId == memberId }.associateBy { it.id }
    val occurrencesByKey = occurrences.associateBy { it.scheduleId to it.dueDate }
    val pendingBySchedule = occurrences.filter { it.status == OccurrenceStatus.PENDING.name }.groupBy { it.scheduleId }
    val followUps = linkedMapOf<Pair<Long, String>, TodayFollowUpItem>()
    val throughDate = date.plusDays(6)

    visibleSchedules.forEach { schedule ->
        pendingBySchedule[schedule.id].orEmpty()
            .forEach { occurrence ->
                val due = LocalDate.parse(occurrence.dueDate)
                if (!due.isAfter(throughDate)) {
                    followUps[schedule.id to occurrence.dueDate] = TodayFollowUpItem(
                        schedule, occurrence, due, schedule.conditionId?.let { conditionsById[it]?.name }
                    )
                }
            }
        if (schedule.enabled) {
            val due = LocalDate.parse(schedule.nextDueDate)
            val key = schedule.id to schedule.nextDueDate
            val occurrence = occurrencesByKey[key]
            if (!due.isAfter(throughDate) && (occurrence == null || occurrence.status == OccurrenceStatus.PENDING.name)) {
                followUps[key] = TodayFollowUpItem(
                    schedule, occurrence, due, schedule.conditionId?.let { conditionsById[it]?.name }
                )
            }
        }
    }
    val ordered = followUps.values.sortedWith(
        compareBy<TodayFollowUpItem> { it.dueDate }.thenBy { it.schedule.id }
    )
    return TodayOverviewData(
        memberId = memberId,
        date = date,
        medicationEntries = projectMedicationDay(date, visibleMedicines, medicationSchedules, medicationLogs),
        asNeededMedications = visibleMedicines.filter {
            it.mode == MedicationMode.AS_NEEDED.name && !it.archived && it.endedAt == null &&
                it.id !in timedMedicationIds &&
                !LocalDate.parse(it.startDate).isAfter(date) &&
                (it.endDate == null || !LocalDate.parse(it.endDate).isBefore(date))
        }.sortedWith(compareBy<MedicationEntity> { it.name }.thenBy { it.id }),
        overdueFollowUps = ordered.filter { it.dueDate.isBefore(date) },
        upcomingFollowUps = ordered.filter { !it.dueDate.isBefore(date) }
    )
}
