package com.healthtimeline.app.ui

import com.healthtimeline.app.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MemberScopedDataTest {
    @Test fun previousMemberCacheIsImmediatelyHidden() {
        val previous = listOf(record(1, 1))
        assertTrue(memberScopedEntities(2, previous) { it.memberId }.isEmpty())
    }

    @Test fun currentMemberKeepsItsOriginalOrderOnly() {
        val values = listOf(record(1, 1), record(2, 2), record(3, 2))
        assertEquals(listOf(2L, 3L), memberScopedEntities(2, values) { it.memberId }.map { it.id })
        assertTrue(memberScopedEntities(null, values) { it.memberId }.isEmpty())
    }

    @Test fun childCachesMustBelongToVisibleParentIds() {
        val children = listOf(1L to "old report", 2L to "current report")
        assertEquals(listOf(2L to "current report"), childrenOfMember(setOf(2), children) { it.first })
        assertTrue(childrenOfMember(emptySet(), children) { it.first }.isEmpty())
    }

    @Test fun calendarCacheCannotExposeAnotherMembersEntries() {
        val first = entry(1, 1)
        val second = entry(2, 2)
        val data = MedicationMonthData(YearMonth.from(DATE), mapOf(DATE to listOf(first, second)))
        assertEquals(listOf(second), memberScopedMedicationMonth(2, data).entries(DATE))
        assertTrue(memberScopedMedicationMonth(null, data).entriesByDate.isEmpty())
    }

    @Test fun medicationChildrenWithWrongParentAreNotShown() {
        val valid = entry(1, 2)
        val inconsistent = valid.copy(schedule = requireNotNull(valid.schedule).copy(medicationId = 9))
        val data = MedicationMonthData(YearMonth.from(DATE), mapOf(DATE to listOf(inconsistent)))
        assertTrue(memberScopedMedicationMonth(2, data).entries(DATE).isEmpty())
    }

    private fun record(id: Long, memberId: Long) = ClinicalRecordEntity(
        id = id, memberId = memberId, recordDate = DATE.toString(), title = "测试病历$id", createdAt = NOW, updatedAt = NOW
    )

    private fun entry(id: Long, memberId: Long): MedicationDayEntry {
        val medication = MedicationEntity(id = id, memberId = memberId, name = "测试药物$id", doseAmount = "1", doseUnit = "片",
            startDate = DATE.toString(), createdAt = NOW, updatedAt = NOW)
        val schedule = MedicationScheduleEntity(id = id, medicationId = id, localTime = "08:00", doseAmountSnapshot = "1", doseUnitSnapshot = "片")
        return MedicationDayEntry(medication, schedule, null, DATE.atTime(8, 0), null, MedicationDayStatus.UNRECORDED)
    }

    companion object {
        private val DATE = LocalDate.of(2026, 10, 8)
        private const val NOW = "2026-10-08T08:00:00Z"
    }
}
