package com.healthtimeline.app.backup

import com.healthtimeline.app.data.AttachmentEntity
import com.healthtimeline.app.data.AttachmentKind
import com.healthtimeline.app.data.ClinicalRecordEntity
import com.healthtimeline.app.data.VisitStage
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupSnapshotValidatorTest {
    private val empty = BackupSnapshot(
        conditions = emptyList(),
        records = emptyList(),
        attachments = emptyList(),
        followUps = emptyList(),
        occurrences = emptyList(),
        medications = emptyList(),
        medicationSchedules = emptyList(),
        medicationLogs = emptyList()
    )

    @Test fun `empty valid snapshot is accepted`() {
        BackupSnapshotValidator.validate(empty)
    }

    @Test fun `record with missing condition is rejected before restore`() {
        val snapshot = empty.copy(records = listOf(record(conditionId = 99)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotValidator.validate(snapshot)
        }
    }

    @Test fun `unsafe attachment path is rejected before extraction`() {
        val record = record()
        val attachment = AttachmentEntity(
            id = 1,
            recordId = record.id,
            kind = AttachmentKind.PDF.name,
            displayName = "report.pdf",
            mimeType = "application/pdf",
            relativePath = "../outside.pdf",
            sizeBytes = 4,
            sha256 = "a".repeat(64),
            createdAt = NOW
        )
        assertThrows(IllegalArgumentException::class.java) {
            BackupSnapshotValidator.validate(empty.copy(records = listOf(record), attachments = listOf(attachment)))
        }
    }

    @Test fun `malformed record date is rejected before database replacement`() {
        assertThrows(Exception::class.java) {
            BackupSnapshotValidator.validate(empty.copy(records = listOf(record(date = "not-a-date"))))
        }
    }

    private fun record(conditionId: Long? = null, date: String = "2026-09-01") = ClinicalRecordEntity(
        id = 1,
        conditionId = conditionId,
        recordDate = date,
        title = "复查",
        stage = VisitStage.CHECKUP.name,
        createdAt = NOW,
        updatedAt = NOW
    )

    private companion object { const val NOW = "2026-09-01T00:00:00Z" }
}
