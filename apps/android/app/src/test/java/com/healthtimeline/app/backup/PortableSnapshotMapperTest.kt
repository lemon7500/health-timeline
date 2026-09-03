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
            createdAt = now, updatedAt = now, uuid = "22222222-2222-4222-8222-222222222222", memberId = member.id
        )
        val source = BackupSnapshot(
            conditions = listOf(condition), records = listOf(record), attachments = emptyList(),
            followUps = emptyList(), occurrences = emptyList(), medications = emptyList(),
            medicationSchedules = emptyList(), medicationLogs = emptyList(), members = listOf(member)
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
        assertNull(restored.records.single().notes.takeIf { it.isNotEmpty() })
    }
}
