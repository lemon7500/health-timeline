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

    @Test
    fun legacyV2PortableBackupStillDecodesBeforeMemberMapping() {
        val current = snapshot(recordTitle = "旧版记录")
        val legacy = current.copy(
            schemaVersion = LEGACY_PORTABLE_BACKUP_SCHEMA_VERSION,
            members = emptyList(),
            conditions = current.conditions.map { it.copy(memberUuid = null) },
            records = current.records.map { it.copy(memberUuid = null) }
        )
        assertEquals(LEGACY_PORTABLE_BACKUP_SCHEMA_VERSION, PortableJson.decode(PortableJson.encode(legacy)).schemaVersion)
    }

    @Test
    fun crossMemberConditionReferenceIsRejected() {
        val current = snapshot(recordTitle = "复查")
        val other = PortableFamilyMember(
            "44444444-4444-4444-8444-444444444444", "李先生", "爸爸", "父亲", false,
            "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z"
        )
        val invalid = current.copy(
            members = current.members + other,
            records = current.records.map { it.copy(memberUuid = other.uuid) }
        )
        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    @Test
    fun backupWithOnlyArchivedMembersIsRejected() {
        val invalid = snapshot(recordTitle = "复查").let { value ->
            value.copy(members = value.members.map { it.copy(archived = true) })
        }
        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    @Test
    fun duplicateMedicationSlotsAreRejectedBeforeDatabaseWrite() {
        val current = snapshot(recordTitle = "复查")
        val memberId = current.members.single().uuid
        val medicationId = "55555555-5555-4555-8555-555555555555"
        val medication = PortableMedication(
            uuid = medicationId,
            name = "药物",
            doseAmount = "1",
            doseUnit = "片",
            instructions = "",
            startDate = "2026-09-01",
            mode = "SCHEDULED",
            archived = false,
            createdAt = "2026-09-01T00:00:00Z",
            updatedAt = "2026-09-01T00:00:00Z",
            memberUuid = memberId
        )
        fun log(uuid: String) = PortableMedicationLog(
            uuid = uuid,
            medicationUuid = medicationId,
            scheduledAt = "2026-09-03T08:00",
            status = "TAKEN",
            doseAmountSnapshot = "1",
            doseUnitSnapshot = "片",
            createdAt = "2026-09-03T00:00:00Z"
        )
        val invalid = current.copy(
            medications = listOf(medication),
            medicationLogs = listOf(
                log("66666666-6666-4666-8666-666666666666"),
                log("77777777-7777-4777-8777-777777777777")
            )
        )
        assertFails { PortableSnapshotValidator.validate(invalid) }
    }

    private fun snapshot(recordTitle: String, exportedAt: String = "2026-09-03T00:00:00Z"): PortableSnapshot {
        val conditionId = "11111111-1111-4111-8111-111111111111"
        val memberId = "33333333-3333-4333-8333-333333333333"
        return PortableSnapshot(
            exportedAt = exportedAt,
            sourcePlatform = "test",
            sourceInstallationId = "00000000-0000-4000-8000-000000000001",
            members = listOf(
                PortableFamilyMember(memberId, "张女士", "妈妈", "母亲", false, "2026-09-01T00:00:00Z", "2026-09-01T00:00:00Z")
            ),
            conditions = listOf(
                PortableCondition(conditionId, "乳腺", 0, "", false, "2026-09-01T00:00:00Z", memberUuid = memberId)
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
                    updatedAt = "2026-09-03T00:00:00Z",
                    memberUuid = memberId
                )
            )
        )
    }
}
