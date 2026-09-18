package com.healthtimeline.app.backup

import com.healthtimeline.app.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortableSnapshotMapperTest {
    @Test fun roundTripPreservesStableIdsAndReferences() {
        val now = "2026-09-03T00:00:00Z"
        val member = FamilyMemberEntity(
            id = 3, name = "张女士", nickname = "妈妈", relationship = "母亲",
            createdAt = now, updatedAt = now, uuid = "33333333-3333-4333-8333-333333333333"
        )
        val condition = ConditionEntity(7, "乳腺", createdAt = now, uuid = "11111111-1111-4111-8111-111111111111", memberId = member.id)
        val record = ClinicalRecordEntity(
            id = 9, conditionId = 7, recordDate = "2026-09-03", title = "彩超",
            createdAt = now, updatedAt = now, uuid = "22222222-2222-4222-8222-222222222222", memberId = member.id,
            dayOrder = 8
        )
        val medication = MedicationEntity(
            id = 11, name = "测试药物", doseAmount = "1", doseUnit = "片",
            startDate = "2026-09-01", endDate = "2026-09-03", archived = true,
            createdAt = now, updatedAt = now, uuid = "44444444-4444-4444-8444-444444444444",
            memberId = member.id, endedAt = "2026-09-03T05:06:07Z",
            archivedPreviousEndDate = "2026-09-30"
        )
        val schedule = MedicationScheduleEntity(
            id = 12, medicationId = medication.id, localTime = "08:00", enabled = false,
            uuid = "55555555-5555-4555-8555-555555555555", effectiveFrom = "2026-09-01",
            effectiveTo = "2026-09-30", doseAmountSnapshot = "1", doseUnitSnapshot = "片",
            updatedAt = now, pausedByCourseEnd = true
        )
        val source = BackupSnapshot(
            conditions = listOf(condition), records = listOf(record), attachments = emptyList(),
            followUps = emptyList(), occurrences = emptyList(), medications = listOf(medication),
            medicationSchedules = listOf(schedule), medicationLogs = emptyList(), members = listOf(member)
        )
        val portable = PortableSnapshotMapper.toPortable(
            source, "00000000-0000-4000-8000-000000000001", now
        )
        assertEquals(condition.uuid, portable.records.single().conditionUuid)

        val restored = PortableSnapshotMapper.toEntities(portable, source) { _, current ->
            current?.relativePath ?: error("unexpected attachment")
        }
        assertEquals(7L, restored.conditions.single().id)
        assertEquals(9L, restored.records.single().id)
        assertEquals(7L, restored.records.single().conditionId)
        assertEquals(record.uuid, restored.records.single().uuid)
        assertEquals(member.id, restored.records.single().memberId)
        assertEquals(8L, portable.records.single().dayOrder)
        assertEquals(8L, restored.records.single().dayOrder)
        assertEquals("母亲", portable.members.single().nickname)
        assertEquals("母亲", portable.members.single().relationship)
        assertEquals("2026-09-03T05:06:07Z", portable.medications.single().endedAt)
        assertEquals("2026-09-03T05:06:07Z", restored.medications.single().endedAt)
        assertEquals("2026-09-30", restored.medications.single().archivedPreviousEndDate)
        assertEquals(true, portable.medicationSchedules.single().pausedByCourseEnd)
        assertEquals(true, restored.medicationSchedules.single().pausedByCourseEnd)
        assertNull(restored.records.single().notes.takeIf { it.isNotEmpty() })
    }
}
