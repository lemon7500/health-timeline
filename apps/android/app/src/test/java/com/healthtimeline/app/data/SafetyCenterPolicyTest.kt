package com.healthtimeline.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SafetyCenterPolicyTest {
    private val now = Instant.parse("2026-09-14T08:00:00Z")

    @Test fun backupFreshnessHandlesNeverRecentAndThirtyDayBoundary() {
        assertEquals(BackupFreshness.NEVER, SafetyCenterPolicy.backupFreshness(null, now))
        assertEquals(BackupFreshness.NEVER, SafetyCenterPolicy.backupFreshness("not-a-time", now))
        assertEquals(
            BackupFreshness.CURRENT,
            SafetyCenterPolicy.backupFreshness("2026-08-16T08:00:00Z", now)
        )
        assertEquals(
            BackupFreshness.OVERDUE,
            SafetyCenterPolicy.backupFreshness("2026-08-15T08:00:00Z", now)
        )
    }

    @Test fun integrityResultTreatsEveryFailureCategoryAsUnhealthy() {
        val healthy = DataIntegrityResult(
            checkedAt = now.toString(),
            databaseOk = true,
            foreignKeyViolations = 0,
            attachmentCount = 2,
            missingAttachments = 0,
            sizeMismatches = 0,
            checksumMismatches = 0,
            unreadableAttachments = 0,
            invalidAttachmentPaths = 0
        )
        assertTrue(healthy.healthy)
        assertEquals(0, healthy.problemCount)

        listOf(
            healthy.copy(databaseOk = false),
            healthy.copy(foreignKeyViolations = 1),
            healthy.copy(missingAttachments = 1),
            healthy.copy(sizeMismatches = 1),
            healthy.copy(checksumMismatches = 1),
            healthy.copy(unreadableAttachments = 1),
            healthy.copy(invalidAttachmentPaths = 1)
        ).forEach { assertFalse(it.healthy) }
    }

    @Test fun diagnosticReportContainsOnlyOperationalFieldsAndPrivacyDeclaration() {
        val report = SafetyDiagnosticFormatter.format(
            SafetyDiagnosticData(
                generatedAt = now.toString(),
                appVersion = "1.2.7",
                versionCode = 12,
                androidVersion = "17",
                sdk = 37,
                manufacturer = "Example",
                model = "Phone",
                systemStatus = SafetySystemStatus(true, true, true, true, true, false),
                lastBackupAt = null,
                integrity = null,
                enabledFollowUps = 2,
                enabledMedicationSchedules = 3,
                usableStorageBytes = 1024
            )
        )

        assertTrue(report.contains("应用版本：1.2.7 (12)"))
        assertTrue(report.contains("可安排复查计划数：2"))
        assertTrue(report.contains("最近完整性检查：尚未运行"))
        assertTrue(report.contains("不包含姓名、病历正文、病名、药名"))
        assertFalse(report.contains("附件名称："))
        assertFalse(report.contains("文件路径："))
    }
}
