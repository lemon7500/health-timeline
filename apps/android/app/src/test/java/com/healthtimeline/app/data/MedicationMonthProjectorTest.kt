package com.healthtimeline.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class MedicationMonthProjectorTest {
    private val medication = MedicationEntity(
        id = 1,
        name = "测试药物",
        doseAmount = "2",
        doseUnit = "片",
        startDate = "2026-08-01",
        createdAt = NOW,
        updatedAt = NOW,
        memberId = 1
    )

    @Test fun `historical schedule versions retain their time and dose`() {
        val old = schedule(
            id = 10,
            time = "08:00",
            from = "2026-08-01",
            to = "2026-09-09",
            amount = "1"
        )
        val current = schedule(
            id = 11,
            time = "08:05",
            from = "2026-09-10",
            to = null,
            amount = "2"
        )
        val log = MedicationLogEntity(
            id = 20,
            medicationId = medication.id,
            scheduleId = old.id,
            scheduledAt = "2026-09-09T08:00",
            actualAt = "2026-09-09T08:10",
            status = MedicationLogStatus.TAKEN.name,
            doseAmountSnapshot = "1",
            doseUnitSnapshot = "片",
            createdAt = NOW
        )

        val result = projectMedicationMonth(
            YearMonth.of(2026, 9),
            listOf(medication),
            listOf(old, current),
            listOf(log)
        )

        val september9 = result.entries(LocalDate.of(2026, 9, 9)).single()
        assertEquals(LocalDateTime.of(2026, 9, 9, 8, 0), september9.plannedAt)
        assertEquals("1", september9.schedule?.doseAmountSnapshot)
        assertEquals(MedicationDayStatus.TAKEN, september9.status)
        assertEquals(LocalDateTime.of(2026, 9, 9, 8, 10), september9.actualAt)

        val september10 = result.entries(LocalDate.of(2026, 9, 10)).single()
        assertEquals(LocalDateTime.of(2026, 9, 10, 8, 5), september10.plannedAt)
        assertEquals("2", september10.schedule?.doseAmountSnapshot)
        assertEquals(MedicationDayStatus.UNRECORDED, september10.status)
    }

    @Test fun `as needed logs are listed by actual day without a planned time`() {
        val log = MedicationLogEntity(
            id = 30,
            medicationId = medication.id,
            scheduleId = null,
            scheduledAt = "2026-09-12T13:25",
            actualAt = "2026-09-12T13:25",
            status = MedicationLogStatus.TAKEN.name,
            doseAmountSnapshot = "2",
            doseUnitSnapshot = "片",
            createdAt = NOW
        )

        val entry = projectMedicationMonth(
            YearMonth.of(2026, 9),
            listOf(medication),
            emptyList(),
            listOf(log)
        ).entries(LocalDate.of(2026, 9, 12)).single()

        assertNull(entry.schedule)
        assertNull(entry.plannedAt)
        assertEquals(LocalDateTime.of(2026, 9, 12, 13, 25), entry.actualAt)
        assertEquals(MedicationDayStatus.TAKEN, entry.status)
    }

    @Test fun `entries are ordered by planned then actual time`() {
        val morning = schedule(10, "08:00", "2026-09-01", null, "1")
        val evening = schedule(11, "20:00", "2026-09-01", null, "1")

        val entries = projectMedicationMonth(
            YearMonth.of(2026, 9),
            listOf(medication),
            listOf(evening, morning),
            emptyList()
        ).entries(LocalDate.of(2026, 9, 7))

        assertEquals(listOf(8, 20), entries.map { it.plannedAt?.hour })
    }

    @Test fun `schedule is defensively bounded by medication course dates`() {
        val ended = medication.copy(endDate = "2026-09-05")
        val unboundedSchedule = schedule(10, "08:00", "2026-09-01", null, "1")

        val result = projectMedicationMonth(
            YearMonth.of(2026, 9),
            listOf(ended),
            listOf(unboundedSchedule),
            emptyList()
        )

        assertEquals(1, result.entries(LocalDate.of(2026, 9, 5)).size)
        assertEquals(0, result.entries(LocalDate.of(2026, 9, 6)).size)
    }

    @Test fun `archived medication keeps entries through its ending day`() {
        val ended = medication.copy(
            archived = true,
            endDate = "2026-09-05",
            endedAt = "2026-09-05T11:26:00Z"
        )
        val closedSchedule = schedule(10, "08:00", "2026-09-01", "2026-09-05", "1")
            .copy(enabled = false)

        val result = projectMedicationMonth(
            YearMonth.of(2026, 9), listOf(ended), listOf(closedSchedule), emptyList()
        )

        assertEquals(1, result.entries(LocalDate.of(2026, 9, 5)).size)
        assertEquals(0, result.entries(LocalDate.of(2026, 9, 6)).size)
    }

    private fun schedule(
        id: Long,
        time: String,
        from: String,
        to: String?,
        amount: String
    ) = MedicationScheduleEntity(
        id = id,
        medicationId = medication.id,
        localTime = time,
        effectiveFrom = from,
        effectiveTo = to,
        doseAmountSnapshot = amount,
        doseUnitSnapshot = "片",
        updatedAt = NOW
    )

    private companion object {
        const val NOW = "2026-09-01T00:00:00Z"
    }
}
