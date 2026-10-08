package com.healthtimeline.app.ui

import com.healthtimeline.app.data.BackupFreshness
import com.healthtimeline.app.data.SafetyCenterPolicy
import com.healthtimeline.app.data.SafetySystemStatus
import java.time.Instant

fun todaySafetyMessages(status: SafetySystemStatus, lastBackupAt: String?, now: Instant): List<String> = buildList {
    if (!status.runtimeNotificationPermission || !status.appNotificationsEnabled) add("通知未开启，系统提醒可能无法显示")
    else if (!status.followUpChannelEnabled || !status.medicationChannelEnabled) add("部分提醒类别已关闭")
    if (!status.exactAlarmAllowed) add("精确闹钟未允许，提醒可能延迟")
    if (!status.batteryOptimizationExempt) add("手机省电限制可能影响提醒，请检查后台设置")
    when (SafetyCenterPolicy.backupFreshness(lastBackupAt, now)) {
        BackupFreshness.NEVER -> add("尚未记录成功备份，建议导出加密备份")
        BackupFreshness.OVERDUE -> add("距离上次成功备份已超过 30 天")
        BackupFreshness.CURRENT -> Unit
    }
}
