package com.healthtimeline.shared

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SharedCoreTest {
    @Test
    fun everyThreeMonthsPreservesAnchor() {
        val rule = RecurrenceRule(RecurrenceKind.EVERY_N_MONTHS, 3, "2026-07-09", 9)
        assertEquals(LocalDate(2026, 10, 9), SharedRecurrenceCalculator.nextAfter(rule, LocalDate(2026, 7, 9)))
    }

    @Test
    fun monthEndAnchorReturnsAfterShortMonth() {
        val rule = RecurrenceRule(RecurrenceKind.EVERY_N_MONTHS, 1, "2025-01-31", 31)
        val february = SharedRecurrenceCalculator.nextAfter(rule, LocalDate(2025, 1, 31))!!
        assertEquals(LocalDate(2025, 2, 28), february)
        assertEquals(LocalDate(2025, 3, 31), SharedRecurrenceCalculator.nextAfter(rule, february))
    }

    @Test
    fun weeklyRuleHonorsConfiguredWeekday() {
        val rule = RecurrenceRule(RecurrenceKind.EVERY_N_WEEKS, 1, "2026-09-07", 7, weekday = 4)
        assertEquals(LocalDate(2026, 9, 17), SharedRecurrenceCalculator.nextAfter(rule, LocalDate(2026, 9, 7)))
    }

    @Test
    fun jsonRoundTripAndValidation() {
        val snapshot = snapshot(recordTitle = "乳腺彩超复查")
        assertEquals(snapshot, PortableJson.decode(PortableJson.encode(snapshot)))
        assertFails { PortableSnapshotValidator.validate(snapshot.copy(sourceInstallationId = "bad")) }
        assertFails { PortableSnapshotValidator.validate(snapshot.copy(sourcePlatform = "unknown")) }
    }

    @Test
    fun searchUsesConditionAndClinicalFields() {
        val snapshot = snapshot(recordTitle = "复查")
        assertEquals(1, searchPortableRecords(snapshot.records, snapshot.conditions, "乳腺").size)
        assertEquals(1, searchPortableRecords(snapshot.records, snapshot.conditions, "彩超").size)
        assertTrue(searchPortableRecords(snapshot.records, snapshot.conditions, "不存在").isEmpty())
    }

    @Test
    fun mergeIsIdempotentAndDefaultsToLocalOnConflict() {
        val local = snapshot(recordTitle = "本机标题")
        val imported = snapshot(recordTitle = "导入标题", exportedAt = "2026-09-04T00:00:00Z")
        val preview = MergePlanner.preview(local, imported)
        assertEquals(1, preview.conflicts.size)
        assertEquals("本机标题", MergePlanner.merge(local, imported).records.single().title)
        val decision = mapOf("record:${local.records.single().uuid}" to MergeChoice.USE_IMPORTED)
        val merged = MergePlanner.merge(local, imported, decision)
        assertEquals("导入标题", merged.records.single().title)
        assertEquals(0, MergePlanner.preview(merged, imported).additions)
    }

    private fun snapshot(recordTitle: String, exportedAt: String = "2026-09-03T00:00:00Z"): PortableSnapshot {
        val conditionId = "11111111-1111-4111-8111-111111111111"
        return PortableSnapshot(
            exportedAt = exportedAt,
            sourcePlatform = "test",
            sourceInstallationId = "00000000-0000-4000-8000-000000000001",
            conditions = listOf(
                PortableCondition(conditionId, "乳腺", 0, "", false, "2026-09-01T00:00:00Z")
            ),
            records = listOf(
                PortableClinicalRecord(
                    uuid = "22222222-2222-4222-8222-222222222222",
                    conditionUuid = conditionId,
                    recordDate = "2026-09-03",
                    title = recordTitle,
                    stage = "CHECKUP",
                    symptoms = "",
                    diagnosis = "彩超结果稳定",
                    treatment = "",
                    medicationNotes = "",
                    hospital = "",
                    clinician = "",
                    notes = "",
                    createdAt = "2026-09-03T00:00:00Z",
                    updatedAt = "2026-09-03T00:00:00Z"
                )
            )
        )
    }
}
