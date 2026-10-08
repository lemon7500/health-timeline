package com.healthtimeline.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class TodayOverviewProjectorTest {
    private val date = LocalDate.of(2026, 12, 29)

    @Test fun `today medication rows equal calendar rows including ending day and snapshots`() {
        val medication = medicine(1).copy(archived = true, endedAt = NOW, endDate = date.toString())
        val schedule = MedicationScheduleEntity(
            id = 10, medicationId = medication.id, localTime = "08:05", enabled = false,
            effectiveFrom = date.minusDays(3).toString(), doseAmountSnapshot = "1", doseUnitSnapshot = "片"
        )
        val log = doseLog(20, medication.id, schedule.id, date.toString())
        val today = project(date, listOf(medication), listOf(schedule), listOf(log))
        val calendar = projectMedicationMonth(YearMonth.from(date), listOf(medication), listOf(schedule), listOf(log))

        assertEquals(calendar.entries(date), today.medicationEntries)
        assertEquals("1", today.medicationEntries.single().schedule?.doseAmountSnapshot)
        assertEquals(MedicationDayStatus.TAKEN, today.medicationEntries.single().status)
        assertTrue(project(date.plusDays(1), listOf(medication), listOf(schedule), listOf(log)).medicationEntries.isEmpty())
    }

    @Test fun `single day projection excludes adjacent days and keeps orphan historical logs`() {
        val medication = medicine(1)
        val logs = listOf(
            doseLog(20, 1, null, date.minusDays(1).toString()),
            doseLog(21, 1, null, date.toString()),
            doseLog(22, 1, null, date.plusDays(1).toString())
        )
        val rows = projectMedicationDay(date, listOf(medication), emptyList(), logs)
        assertEquals(listOf(21L), rows.map { it.log?.id })
        assertNull(rows.single().plannedAt)
    }

    @Test fun `as needed medications have no invented plan and ended courses are excluded`() {
        val active = medicine(1).copy(mode = MedicationMode.AS_NEEDED.name)
        val ended = medicine(2).copy(mode = MedicationMode.AS_NEEDED.name, archived = true, endedAt = NOW)
        val future = medicine(3).copy(mode = MedicationMode.AS_NEEDED.name, startDate = date.plusDays(1).toString())
        val result = project(date, listOf(active, ended, future))
        assertEquals(listOf(1L), result.asNeededMedications.map { it.id })
        assertTrue(result.medicationEntries.isEmpty())
    }

    @Test fun `tomorrow as needed change cannot enable today's as needed entry`() {
        val medication = medicine(1).copy(mode = MedicationMode.AS_NEEDED.name, doseAmount = "3")
        val schedule = MedicationScheduleEntity(
            id = 10, medicationId = medication.id, localTime = "08:05", enabled = false,
            effectiveFrom = date.minusDays(3).toString(), effectiveTo = date.toString(),
            doseAmountSnapshot = "1", doseUnitSnapshot = "片"
        )
        val today = project(date, listOf(medication), listOf(schedule))
        assertTrue(today.asNeededMedications.isEmpty())
        assertEquals("1", today.medicationEntries.single().schedule?.doseAmountSnapshot)
        val tomorrow = project(date.plusDays(1), listOf(medication), listOf(schedule))
        assertEquals(listOf(medication.id), tomorrow.asNeededMedications.map { it.id })
        assertTrue(tomorrow.medicationEntries.isEmpty())
    }

    @Test fun `followups appear without delivered notifications and are deduplicated by date`() {
        val schedule = followUp(1, date)
        val occurrence = occurrence(10, 1, date)
        assertNull(project(date, schedules = listOf(schedule)).upcomingFollowUps.single().occurrence)
        val result = project(date, schedules = listOf(schedule), occurrences = listOf(occurrence))
        assertEquals(1, result.upcomingFollowUps.size)
        assertEquals(10L, result.upcomingFollowUps.single().occurrence?.id)
    }

    @Test fun `seven day window crosses year and excludes eighth day`() {
        val result = project(date, schedules = listOf(
            followUp(1, date), followUp(2, date.plusDays(6)), followUp(3, date.plusDays(7))
        ))
        assertEquals(listOf(date, LocalDate.of(2027, 1, 4)), result.upcomingFollowUps.map { it.dueDate })
        assertTrue(result.overdueFollowUps.isEmpty())
    }

    @Test fun `pending history survives rule changes and completed history does not reappear`() {
        val schedule = followUp(1, date.plusDays(2))
        val pending = occurrence(10, 1, date.minusDays(2))
        val completed = occurrence(11, 1, date.minusDays(3)).copy(status = OccurrenceStatus.DONE.name)
        val result = project(date, schedules = listOf(schedule), occurrences = listOf(pending, completed))
        assertEquals(listOf(date.minusDays(2)), result.overdueFollowUps.map { it.dueDate })
        assertEquals(listOf(date.plusDays(2)), result.upcomingFollowUps.map { it.dueDate })
        val inconsistent = project(date, schedules = listOf(followUp(1, date)),
            occurrences = listOf(occurrence(12, 1, date).copy(status = OccurrenceStatus.SKIPPED.name)))
        assertTrue(inconsistent.upcomingFollowUps.isEmpty())
    }

    @Test fun `different members and deleted items cannot enter the overview`() {
        val condition = ConditionEntity(id = 10, name = "乳腺", createdAt = NOW)
        val own = followUp(1, date).copy(conditionId = condition.id)
        val foreign = followUp(2, date).copy(memberId = 2)
        val deleted = followUp(3, date).copy(deletedAt = NOW)
        val result = project(date,
            medicines = listOf(medicine(1).copy(memberId = 2), medicine(2).copy(deletedAt = NOW)),
            schedules = listOf(own, foreign, deleted),
            conditions = listOf(condition))
        assertEquals(listOf(1L), result.upcomingFollowUps.map { it.schedule.id })
        assertEquals("乳腺", result.upcomingFollowUps.single().conditionName)
        assertFalse(result.medicationEntries.isNotEmpty())
    }

    private fun project(
        on: LocalDate,
        medicines: List<MedicationEntity> = emptyList(),
        medicationSchedules: List<MedicationScheduleEntity> = emptyList(),
        logs: List<MedicationLogEntity> = emptyList(),
        schedules: List<FollowUpScheduleEntity> = emptyList(),
        occurrences: List<FollowUpOccurrenceEntity> = emptyList(),
        conditions: List<ConditionEntity> = emptyList()
    ) = projectTodayOverview(1, on, medicines, medicationSchedules, logs, schedules, occurrences, conditions)

    private fun medicine(id: Long) = MedicationEntity(
        id = id, name = "药物$id", doseAmount = "2", doseUnit = "片",
        startDate = "2026-12-01", createdAt = NOW, updatedAt = NOW
    )

    private fun doseLog(id: Long, medicationId: Long, scheduleId: Long?, day: String) = MedicationLogEntity(
        id = id, medicationId = medicationId, scheduleId = scheduleId,
        scheduledAt = "${day}T08:05", actualAt = "${day}T08:10",
        status = MedicationLogStatus.TAKEN.name, doseAmountSnapshot = "1", doseUnitSnapshot = "片", createdAt = NOW
    )

    private fun followUp(id: Long, due: LocalDate) = FollowUpScheduleEntity(
        id = id, title = "复查$id", recurrenceType = RecurrenceType.EVERY_N_DAYS.name,
        anchorDate = "2026-12-01", anchorDayOfMonth = 1, nextDueDate = due.toString(),
        createdAt = NOW, updatedAt = NOW
    )

    private fun occurrence(id: Long, scheduleId: Long, due: LocalDate) = FollowUpOccurrenceEntity(
        id = id, scheduleId = scheduleId, dueDate = due.toString(), createdAt = NOW
    )

    private companion object { const val NOW = "2026-12-29T00:00:00Z" }
}
