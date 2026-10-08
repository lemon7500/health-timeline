package com.healthtimeline.app.ui

import com.healthtimeline.app.data.SafetySystemStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TodaySafetySummaryTest {
    private val now = Instant.parse("2026-10-08T08:00:00Z")
    private val ready = SafetySystemStatus(true, true, true, true, true, true)

    @Test fun healthySettingsAndRecentBackupHaveNoWarning() {
        assertTrue(todaySafetyMessages(ready, now.toString(), now).isEmpty())
    }

    @Test fun deniedPermissionsAreCollapsedWithoutMedicalInformation() {
        val warnings = todaySafetyMessages(ready.copy(runtimeNotificationPermission = false, followUpChannelEnabled = false, exactAlarmAllowed = false), null, now)
        assertEquals(3, warnings.size)
        assertEquals(1, warnings.count { it.contains("通知未开启") })
        assertTrue(warnings.none { it.contains("提醒一定") })
    }

    @Test fun channelAndThirtyDayBackupBoundaryAreReported() {
        val warnings = todaySafetyMessages(ready.copy(medicationChannelEnabled = false), now.minusSeconds(30 * 86400L).toString(), now)
        assertEquals(2, warnings.size)
        assertTrue(warnings.any { it.contains("30 天") })
    }
}
