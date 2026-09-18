package com.healthtimeline.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.healthtimeline.app.BuildConfig
import com.healthtimeline.app.NotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

enum class BackupFreshness { NEVER, CURRENT, OVERDUE }

object SafetyCenterPolicy {
    const val BACKUP_WARNING_DAYS = 30L

    fun backupFreshness(
        lastBackupAt: String?,
        now: Instant = Instant.now(),
        warningDays: Long = BACKUP_WARNING_DAYS
    ): BackupFreshness {
        val last = lastBackupAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: return BackupFreshness.NEVER
        return if (Duration.between(last, now).toDays() >= warningDays) {
            BackupFreshness.OVERDUE
        } else {
            BackupFreshness.CURRENT
        }
    }
}

data class SafetySystemStatus(
    val runtimeNotificationPermission: Boolean,
    val appNotificationsEnabled: Boolean,
    val followUpChannelEnabled: Boolean,
    val medicationChannelEnabled: Boolean,
    val exactAlarmAllowed: Boolean,
    val batteryOptimizationExempt: Boolean
) {
    val notificationsReady: Boolean
        get() = runtimeNotificationPermission && appNotificationsEnabled &&
            followUpChannelEnabled && medicationChannelEnabled
}

data class DataIntegrityResult(
    val checkedAt: String,
    val databaseOk: Boolean,
    val foreignKeyViolations: Int,
    val attachmentCount: Int,
    val missingAttachments: Int,
    val sizeMismatches: Int,
    val checksumMismatches: Int,
    val unreadableAttachments: Int,
    val invalidAttachmentPaths: Int
) {
    val healthy: Boolean
        get() = databaseOk && foreignKeyViolations == 0 && missingAttachments == 0 &&
            sizeMismatches == 0 && checksumMismatches == 0 && unreadableAttachments == 0 &&
            invalidAttachmentPaths == 0

    val problemCount: Int
        get() = foreignKeyViolations + missingAttachments + sizeMismatches + checksumMismatches +
            unreadableAttachments + invalidAttachmentPaths + if (databaseOk) 0 else 1
}

class SafetyStatusStore(
    context: Context,
    preferenceName: String = PREFERENCE_NAME
) {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val lastBackupMutable = MutableStateFlow(preferences.getString(KEY_LAST_BACKUP, null))
    private val lastIntegrityMutable = MutableStateFlow(readIntegrity())

    val lastBackupAt: StateFlow<String?> = lastBackupMutable
    val lastIntegrityResult: StateFlow<DataIntegrityResult?> = lastIntegrityMutable

    fun recordSuccessfulBackup(at: Instant = Instant.now()) {
        val value = at.toString()
        preferences.edit().putString(KEY_LAST_BACKUP, value).apply()
        lastBackupMutable.value = value
    }

    fun recordIntegrity(value: DataIntegrityResult) {
        preferences.edit()
            .putString(KEY_CHECKED_AT, value.checkedAt)
            .putBoolean(KEY_DATABASE_OK, value.databaseOk)
            .putInt(KEY_FOREIGN_KEYS, value.foreignKeyViolations)
            .putInt(KEY_ATTACHMENT_COUNT, value.attachmentCount)
            .putInt(KEY_MISSING, value.missingAttachments)
            .putInt(KEY_SIZE, value.sizeMismatches)
            .putInt(KEY_CHECKSUM, value.checksumMismatches)
            .putInt(KEY_UNREADABLE, value.unreadableAttachments)
            .putInt(KEY_INVALID_PATH, value.invalidAttachmentPaths)
            .apply()
        lastIntegrityMutable.value = value
    }

    private fun readIntegrity(): DataIntegrityResult? {
        val checkedAt = preferences.getString(KEY_CHECKED_AT, null) ?: return null
        return DataIntegrityResult(
            checkedAt = checkedAt,
            databaseOk = preferences.getBoolean(KEY_DATABASE_OK, false),
            foreignKeyViolations = preferences.getInt(KEY_FOREIGN_KEYS, 0),
            attachmentCount = preferences.getInt(KEY_ATTACHMENT_COUNT, 0),
            missingAttachments = preferences.getInt(KEY_MISSING, 0),
            sizeMismatches = preferences.getInt(KEY_SIZE, 0),
            checksumMismatches = preferences.getInt(KEY_CHECKSUM, 0),
            unreadableAttachments = preferences.getInt(KEY_UNREADABLE, 0),
            invalidAttachmentPaths = preferences.getInt(KEY_INVALID_PATH, 0)
        )
    }

    private companion object {
        const val PREFERENCE_NAME = "safety_center_status"
        const val KEY_LAST_BACKUP = "last_successful_portable_backup_at"
        const val KEY_CHECKED_AT = "last_integrity_check_at"
        const val KEY_DATABASE_OK = "database_ok"
        const val KEY_FOREIGN_KEYS = "foreign_key_violations"
        const val KEY_ATTACHMENT_COUNT = "attachment_count"
        const val KEY_MISSING = "missing_attachments"
        const val KEY_SIZE = "attachment_size_mismatches"
        const val KEY_CHECKSUM = "attachment_checksum_mismatches"
        const val KEY_UNREADABLE = "unreadable_attachments"
        const val KEY_INVALID_PATH = "invalid_attachment_paths"
    }
}

internal data class SafetyDiagnosticData(
    val generatedAt: String,
    val appVersion: String,
    val versionCode: Int,
    val androidVersion: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
    val systemStatus: SafetySystemStatus,
    val lastBackupAt: String?,
    val integrity: DataIntegrityResult?,
    val enabledFollowUps: Int,
    val enabledMedicationSchedules: Int,
    val usableStorageBytes: Long
)

internal object SafetyDiagnosticFormatter {
    fun format(value: SafetyDiagnosticData): String = buildString {
        appendLine("病程日历本地诊断报告")
        appendLine("生成时间：${safe(value.generatedAt)}")
        appendLine("应用版本：${safe(value.appVersion)} (${value.versionCode})")
        appendLine("Android：${safe(value.androidVersion)} / API ${value.sdk}")
        appendLine("设备：${safe(value.manufacturer)} ${safe(value.model)}")
        appendLine()
        appendLine("通知运行时权限：${yesNo(value.systemStatus.runtimeNotificationPermission)}")
        appendLine("应用通知总开关：${yesNo(value.systemStatus.appNotificationsEnabled)}")
        appendLine("复查提醒类别：${yesNo(value.systemStatus.followUpChannelEnabled)}")
        appendLine("用药提醒类别：${yesNo(value.systemStatus.medicationChannelEnabled)}")
        appendLine("精确闹钟：${yesNo(value.systemStatus.exactAlarmAllowed)}")
        appendLine("标准电池优化豁免：${yesNo(value.systemStatus.batteryOptimizationExempt)}")
        appendLine("可用私有存储字节：${value.usableStorageBytes.coerceAtLeast(0)}")
        appendLine("可安排复查计划数：${value.enabledFollowUps.coerceAtLeast(0)}")
        appendLine("可安排用药时间表数：${value.enabledMedicationSchedules.coerceAtLeast(0)}")
        appendLine("最近成功导出备份：${value.lastBackupAt?.let(::safe) ?: "从未记录"}")
        value.integrity?.let { result ->
            appendLine()
            appendLine("最近完整性检查：${safe(result.checkedAt)}")
            appendLine("数据库检查：${if (result.databaseOk) "通过" else "异常"}")
            appendLine("外键异常：${result.foreignKeyViolations}")
            appendLine("附件记录数：${result.attachmentCount}")
            appendLine("附件缺失：${result.missingAttachments}")
            appendLine("附件大小异常：${result.sizeMismatches}")
            appendLine("附件校验值异常：${result.checksumMismatches}")
            appendLine("附件不可读取：${result.unreadableAttachments}")
            appendLine("附件路径异常：${result.invalidAttachmentPaths}")
        } ?: appendLine("最近完整性检查：尚未运行")
        appendLine()
        appendLine("隐私说明：本报告不包含姓名、病历正文、病名、药名、附件名称、文件路径或数据 UUID。")
    }

    private fun yesNo(value: Boolean) = if (value) "正常" else "需要检查"
    private fun safe(value: String) = value.replace('\r', ' ').replace('\n', ' ').take(200)
}

class SafetyCenterService(
    private val context: Context,
    private val database: AppDatabase,
    private val repository: HealthRepository,
    private val statusStore: SafetyStatusStore
) {
    val lastBackupAt: StateFlow<String?> = statusStore.lastBackupAt
    val lastIntegrityResult: StateFlow<DataIntegrityResult?> = statusStore.lastIntegrityResult

    fun systemStatus(): SafetySystemStatus {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val runtimePermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val followUpChannel = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            (notificationManager.getNotificationChannel(NotificationChannels.FOLLOW_UP)?.importance
                ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE
        val medicationChannel = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            (notificationManager.getNotificationChannel(NotificationChannels.MEDICATION)?.importance
                ?: NotificationManager.IMPORTANCE_NONE) != NotificationManager.IMPORTANCE_NONE
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val exactAlarm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryExempt = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        return SafetySystemStatus(
            runtimeNotificationPermission = runtimePermission,
            appNotificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            followUpChannelEnabled = followUpChannel,
            medicationChannelEnabled = medicationChannel,
            exactAlarmAllowed = exactAlarm,
            batteryOptimizationExempt = batteryExempt
        )
    }

    suspend fun runIntegrityCheck(): DataIntegrityResult = withContext(Dispatchers.IO) {
        repository.mutationMutex.withLock {
            val readableDatabase = database.openHelper.readableDatabase
            val quickCheck = mutableListOf<String>()
            readableDatabase.query("PRAGMA quick_check").use { cursor ->
                while (cursor.moveToNext()) quickCheck += cursor.getString(0).orEmpty()
            }
            var foreignKeyViolations = 0
            readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                while (cursor.moveToNext()) foreignKeyViolations++
            }

            val attachments = database.attachmentDao().all()
            var missing = 0
            var sizeMismatch = 0
            var checksumMismatch = 0
            var unreadable = 0
            var invalidPath = 0
            attachments.forEach { attachment ->
                val file = runCatching { repository.attachmentStore.file(attachment) }.getOrElse {
                    invalidPath++
                    return@forEach
                }
                when {
                    !file.isFile -> missing++
                    !file.canRead() -> unreadable++
                    else -> {
                        if (file.length() != attachment.sizeBytes) sizeMismatch++
                        val digest = runCatching { sha256(file.absolutePath) }.getOrNull()
                        if (digest == null) unreadable++
                        else if (!digest.equals(attachment.sha256, ignoreCase = true)) checksumMismatch++
                    }
                }
            }
            DataIntegrityResult(
                checkedAt = Instant.now().toString(),
                databaseOk = quickCheck.size == 1 && quickCheck.single().equals("ok", ignoreCase = true),
                foreignKeyViolations = foreignKeyViolations,
                attachmentCount = attachments.size,
                missingAttachments = missing,
                sizeMismatches = sizeMismatch,
                checksumMismatches = checksumMismatch,
                unreadableAttachments = unreadable,
                invalidAttachmentPaths = invalidPath
            ).also(statusStore::recordIntegrity)
        }
    }

    suspend fun exportDiagnostic(destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val data = SafetyDiagnosticData(
                generatedAt = Instant.now().toString(),
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                sdk = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                systemStatus = systemStatus(),
                lastBackupAt = statusStore.lastBackupAt.value,
                integrity = statusStore.lastIntegrityResult.value,
                enabledFollowUps = repository.enabledFollowUps().size,
                enabledMedicationSchedules = repository.enabledMedicationSchedules().size,
                usableStorageBytes = context.filesDir.usableSpace
            )
            val descriptor = context.contentResolver.openFileDescriptor(destination, "rwt")
                ?: error("无法创建诊断报告")
            descriptor.use {
                FileOutputStream(it.fileDescriptor).use { output ->
                    output.write(SafetyDiagnosticFormatter.format(data).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
            }
        }
    }

    private fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
