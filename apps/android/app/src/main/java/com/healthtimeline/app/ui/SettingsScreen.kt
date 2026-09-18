package com.healthtimeline.app.ui

import com.healthtimeline.app.BuildConfig
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.healthtimeline.shared.BackupImportPreview
import com.healthtimeline.shared.MergeChoice
import com.healthtimeline.shared.MergeConflict
import com.healthtimeline.app.data.BackupFreshness
import com.healthtimeline.app.data.DataIntegrityResult
import com.healthtimeline.app.data.FamilyMemberEntity
import com.healthtimeline.app.data.SafetyCenterPolicy
import com.healthtimeline.app.data.SafetySystemStatus
import com.healthtimeline.app.data.TrashItem
import com.healthtimeline.app.data.TrashItemType
import com.healthtimeline.app.data.HealthRepository
import com.healthtimeline.app.HealthTimelineApplication

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    appLockEnabled: Boolean,
    onAppLockChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val appLockManager = (context.applicationContext as HealthTimelineApplication).appLockManager
    var showSafetyCenter by rememberSaveable { mutableStateOf(false) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    if (showTrash) {
        TrashScreen(viewModel, padding, onBack = { showTrash = false })
        return
    }
    if (showSafetyCenter) {
        SafetyCenterScreen(viewModel, padding, onBack = { showSafetyCenter = false })
        return
    }

    val members by viewModel.members.collectAsState()
    val lastBackupAt by viewModel.lastBackupAt.collectAsState()
    val trashItems by viewModel.trashItems.collectAsState()
    var createMember by remember { mutableStateOf(false) }
    var editingMember by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var archiveMember by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var deleteMember by remember { mutableStateOf<FamilyMemberEntity?>(null) }
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf<CharArray?>(null) }
    var importPreview by remember { mutableStateOf<BackupImportPreview?>(null) }
    var replacementSource by remember { mutableStateOf<Uri?>(null) }
    var replacementPassword by remember { mutableStateOf<CharArray?>(null) }
    val importedChoices = remember { mutableStateMapOf<String, Boolean>() }

    fun clearPendingImport() {
        importPassword?.fill('\u0000')
        importPassword = null
        restoreUri = null
        importPreview = null
        importedChoices.clear()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        appLockManager.endExternalActivity()
        val password = exportPassword
        if (uri != null && password != null) {
            viewModel.exportBackup(uri, password)
        } else {
            password?.fill('\u0000')
        }
        exportPassword = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        appLockManager.endExternalActivity()
        if (uri != null) { restoreUri = uri; passwordMode = "restore" }
    }
    val safetyExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        appLockManager.endExternalActivity()
        val source = replacementSource
        val password = replacementPassword
        replacementSource = null
        replacementPassword = null
        if (uri != null && source != null && password != null) {
            viewModel.restoreBackupWithSafetyExport(uri, source, password)
        } else {
            password?.fill('\u0000')
        }
    }

    Column(
        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("家庭档案", style = MaterialTheme.typography.titleMedium)
        Text("日历、复查和用药会跟随当前成员切换；所有未归档成员的系统提醒都会继续生效。")
        Button(onClick = { createMember = true }) { Text("添加家庭成员") }
        members.forEach { member ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(member.relationship, fontWeight = FontWeight.SemiBold)
                    Text("${member.name}${if (member.archived) " · 已归档" else ""}")
                    Row {
                        TextButton(onClick = { editingMember = member }) { Text("编辑") }
                        if (member.archived) {
                            TextButton(onClick = { viewModel.restoreMember(member) }) { Text("恢复") }
                        } else {
                            TextButton(onClick = { archiveMember = member }) { Text("归档") }
                        }
                        TextButton(onClick = { deleteMember = member }) { Text("删除空档案") }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("隐私保护", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("进入应用时验证身份", fontWeight = FontWeight.SemiBold)
                Text("离开应用约 30 秒或手机锁屏后，使用系统指纹、面容或锁屏密码验证。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = appLockEnabled, onCheckedChange = onAppLockChange)
        }

        HorizontalDivider()
        Text("安全与提醒", style = MaterialTheme.typography.titleMedium)
        ElevatedCard(
            Modifier.fillMaxWidth().clickable { showSafetyCenter = true }
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Security, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("安全与提醒检查", fontWeight = FontWeight.SemiBold)
                    Text(
                        when (SafetyCenterPolicy.backupFreshness(lastBackupAt)) {
                            BackupFreshness.NEVER -> "尚未记录成功备份；可检查权限、提醒和数据完整性"
                            BackupFreshness.OVERDUE -> "距离上次成功备份已超过 30 天，建议尽快备份"
                            BackupFreshness.CURRENT -> "检查通知、后台运行、备份和本地数据完整性"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (SafetyCenterPolicy.backupFreshness(lastBackupAt) == BackupFreshness.CURRENT) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                Text("进入", color = MaterialTheme.colorScheme.primary)
            }
        }
        ElevatedCard(
            Modifier.fillMaxWidth().clickable { showTrash = true }
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("回收站", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (trashItems.isEmpty()) "暂无待清理资料"
                        else "${trashItems.size} 项资料可在删除后 30 天内恢复",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text("进入", color = MaterialTheme.colorScheme.primary)
            }
        }

        HorizontalDivider()
        Text("加密备份", style = MaterialTheme.typography.titleMedium)
        Text("备份包含全部病历、提醒、用药记录和检查报告。密码无法找回，请妥善保存。整体替换前会在应用私有目录自动保留最近 3 份安全备份。", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { passwordMode = "export" }) {
            Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text("导出加密备份")
        }
        OutlinedButton(onClick = {
            appLockManager.beginExternalActivity()
            importLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }) {
            Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(8.dp)); Text("导入或恢复备份")
        }

        HorizontalDivider()
        Text("隐私与版本", style = MaterialTheme.typography.titleMedium)
        Text("病程日历 ${BuildConfig.VERSION_NAME}")
        Text("数据仅保存在手机中；应用未申请网络权限，不会上传任何医疗信息。卸载前请先导出备份。")
        Text("本应用仅用于记录和提醒，不提供诊断、处方或药物安全建议。", color = MaterialTheme.colorScheme.error)
    }

    if (passwordMode != null) {
        PasswordDialog(
            title = if (passwordMode == "export") "设置备份密码" else "输入备份密码",
            confirmLabel = if (passwordMode == "export") "选择保存位置" else "验证备份",
            onConfirm = { password ->
                if (passwordMode == "export") {
                    exportPassword = password
                    appLockManager.beginExternalActivity()
                    exportLauncher.launch("病程日历-${LocalDate.now()}.htbackup")
                } else {
                    importPassword?.fill('\u0000')
                    importPassword = password.copyOf()
                    val uri = restoreUri
                    if (uri != null) {
                        viewModel.previewBackup(
                            uri,
                            password,
                            onReady = { preview ->
                                importPreview = preview
                                importedChoices.clear()
                            },
                            onFailed = { clearPendingImport() }
                        )
                    } else {
                        password.fill('\u0000')
                        clearPendingImport()
                    }
                }
                passwordMode = null
            },
            onDismiss = { passwordMode = null; clearPendingImport() }
        )
    }
    if (createMember || editingMember != null) {
        FamilyMemberEditorDialog(
            existing = editingMember,
            onSave = { value, onFailed ->
                viewModel.saveMember(value, { createMember = false; editingMember = null }, onFailed)
            },
            onDismiss = { createMember = false; editingMember = null }
        )
    }
    archiveMember?.let { member ->
        ConfirmDialog(
            "归档${member.relationship}？",
            "档案和历史资料会保留，但该成员的复查和用药提醒将暂停。",
            { viewModel.archiveMember(member); archiveMember = null },
            { archiveMember = null }
        )
    }
    deleteMember?.let { member ->
        ConfirmDialog(
            "删除空档案？",
            "只有完全没有病历、分类、复查和用药资料的成员才能删除；有历史资料的成员请使用归档。",
            { viewModel.deleteEmptyMember(member); deleteMember = null },
            { deleteMember = null }
        )
    }
    importPreview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            importedChoices = importedChoices,
            onMerge = {
                val uri = restoreUri
                val password = importPassword?.copyOf()
                val decisions = importedChoices.filterValues { it }.keys.associateWith { MergeChoice.USE_IMPORTED }
                clearPendingImport()
                if (uri != null && password != null) viewModel.mergeBackup(uri, password, decisions)
            },
            onImportLegacyAsNew = {
                val uri = restoreUri
                val password = importPassword?.copyOf()
                clearPendingImport()
                if (uri != null && password != null) viewModel.importLegacyBackupAsNew(uri, password)
                else password?.fill('\u0000')
            },
            onReplace = {
                importPreview = null
                confirmRestore = true
            },
            onDismiss = { clearPendingImport() }
        )
    }
    if (confirmRestore) {
        ConfirmDialog(
            "整体替换当前数据？",
            "这会删除本机独有的资料并改用备份内容。下一步必须先选择位置导出当前数据的安全备份；导出、验证或写入失败都不会改变现有数据。",
            onConfirm = {
                val uri = restoreUri
                val password = importPassword?.copyOf()
                clearPendingImport()
                if (uri != null && password != null) {
                    replacementSource = uri
                    replacementPassword = password
                    appLockManager.beginExternalActivity()
                    safetyExportLauncher.launch("病程日历-替换前安全备份-${LocalDate.now()}.htbackup")
                } else {
                    password?.fill('\u0000')
                }
                confirmRestore = false
            },
            onDismiss = { confirmRestore = false; clearPendingImport() }
        )
    }
}

@Composable
private fun TrashScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val items by viewModel.trashItems.collectAsState()
    var permanentTarget by remember { mutableStateOf<TrashItem?>(null) }

    Column(
        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置") }
            Column {
                Text("回收站", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("当前家庭成员的已删除资料", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "资料删除后保留 30 天，应用下次启动时会尝试清理已到期项目。永久删除无法恢复；重要资料请先导出加密备份。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (items.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text("回收站是空的", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items.forEach { item ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.title, fontWeight = FontWeight.SemiBold)
                    Text("${trashTypeLabel(item.type)} · ${item.detail}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "删除时间：${formatStatusInstant(item.deletedAt)} · ${trashExpiryText(item.deletedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        TextButton(onClick = { viewModel.restoreTrash(item) }) {
                            Icon(Icons.Outlined.Restore, null)
                            Spacer(Modifier.width(4.dp))
                            Text("恢复")
                        }
                        TextButton(onClick = { permanentTarget = item }) {
                            Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text("永久删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    permanentTarget?.let { item ->
        ConfirmDialog(
            title = "永久删除${trashTypeLabel(item.type)}？",
            message = "“${item.title}”及其关联历史将立即删除，无法从回收站恢复。请确认已经保存必要备份。",
            onConfirm = {
                viewModel.permanentlyDeleteTrash(item)
                permanentTarget = null
            },
            onDismiss = { permanentTarget = null }
        )
    }
}

private fun trashTypeLabel(type: TrashItemType): String = when (type) {
    TrashItemType.CLINICAL_RECORD -> "病历"
    TrashItemType.ATTACHMENT -> "检查报告"
    TrashItemType.FOLLOW_UP -> "复查计划"
    TrashItemType.MEDICATION -> "药物"
}

private fun trashExpiryText(deletedAt: String): String = runCatching {
    val expiry = Instant.parse(deletedAt).plusSeconds(HealthRepository.TRASH_RETENTION_DAYS * 24L * 60L * 60L)
    val remaining = java.time.Duration.between(Instant.now(), expiry).toDays().coerceAtLeast(0L)
    if (remaining == 0L) "即将自动清理" else "约剩 $remaining 天"
}.getOrDefault("到期时间未知")

@Composable
private fun SafetyCenterScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appLockManager = (context.applicationContext as HealthTimelineApplication).appLockManager
    val lastBackupAt by viewModel.lastBackupAt.collectAsState()
    val integrity by viewModel.lastIntegrityResult.collectAsState()
    var refreshTick by remember { mutableIntStateOf(0) }
    val status = remember(refreshTick) { viewModel.safetySystemStatus() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                appLockManager.endExternalActivity()
                refreshTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openSystemSettings(intent: Intent) {
        appLockManager.beginExternalActivity()
        safeStartSettings(context, intent)
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        appLockManager.endExternalActivity()
        refreshTick++
    }
    val diagnosticLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        appLockManager.endExternalActivity()
        if (uri != null) viewModel.exportSafetyDiagnostic(uri)
    }

    Column(
        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回设置") }
            Column {
                Text("安全与提醒检查", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("检查结果只保存在本机", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("提醒状态", style = MaterialTheme.typography.titleMedium)
        SafetyStatusCard(
            title = "通知",
            healthy = status.notificationsReady,
            detail = notificationStatusDetail(status),
            actionLabel = if (status.notificationsReady) null else "去设置",
            onAction = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !status.runtimeNotificationPermission
                ) {
                    appLockManager.beginExternalActivity()
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openSystemSettings(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
        )
        SafetyStatusCard(
            title = "精确闹钟",
            healthy = status.exactAlarmAllowed,
            detail = if (status.exactAlarmAllowed) {
                "已允许按设定时间触发提醒"
            } else {
                "未允许；提醒仍会安排，但可能被系统延迟"
            },
            actionLabel = if (!status.exactAlarmAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "去设置" else null,
            onAction = {
                openSystemSettings(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )
        SafetyStatusCard(
            title = "后台与电池优化",
            healthy = status.batteryOptimizationExempt,
            detail = if (status.batteryOptimizationExempt) {
                "Android 标准电池优化未限制本应用"
            } else {
                "系统可能限制后台提醒，请检查电池优化和自启动设置"
            },
            actionLabel = "检查设置",
            onAction = {
                openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        )
        Text(
            "华为、荣耀和 Redmi 还可能有厂商自己的自启动或后台运行开关，Android 无法自动读取这些状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            enabled = status.notificationsReady,
            onClick = viewModel::scheduleTestReminder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Notifications, null)
            Spacer(Modifier.width(8.dp))
            Text("发送 10 秒测试提醒")
        }
        Text(
            "测试提醒用于验证应用、系统闹钟和通知类别；精确闹钟未允许时，测试提醒可能延迟。",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()
        Text("备份状态", style = MaterialTheme.typography.titleMedium)
        BackupStatusCard(lastBackupAt)

        HorizontalDivider()
        Text("数据完整性", style = MaterialTheme.typography.titleMedium)
        IntegrityStatusCard(integrity)
        Button(onClick = viewModel::runSafetyIntegrityCheck, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Security, null)
            Spacer(Modifier.width(8.dp))
            Text("检查数据库和全部附件")
        }
        Text(
            "检查会读取并核对所有附件的大小和 SHA-256，但不会修改、删除或上传任何资料。附件较多时可能需要一些时间。",
            style = MaterialTheme.typography.bodySmall
        )

        HorizontalDivider()
        Text("诊断报告", style = MaterialTheme.typography.titleMedium)
        Text(
            "可在需要协助排查时导出。报告只包含应用版本、设备型号、权限状态和异常数量，不包含姓名、病历正文、病名、药名、附件名称、路径或 UUID。",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedButton(
            onClick = {
                appLockManager.beginExternalActivity()
                diagnosticLauncher.launch("病程日历-诊断报告-${LocalDate.now()}.txt")
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Description, null)
            Spacer(Modifier.width(8.dp))
            Text("导出本地诊断报告")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SafetyStatusCard(
    title: String,
    healthy: Boolean,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (healthy) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall)
            }
            actionLabel?.let { TextButton(onClick = onAction) { Text(it) } }
        }
    }
}

@Composable
private fun BackupStatusCard(lastBackupAt: String?) {
    val freshness = SafetyCenterPolicy.backupFreshness(lastBackupAt)
    SafetyStatusCard(
        title = "最近成功导出加密备份",
        healthy = freshness == BackupFreshness.CURRENT,
        detail = when (freshness) {
            BackupFreshness.NEVER -> "尚未记录成功备份。请返回设置页导出一份加密备份。"
            BackupFreshness.OVERDUE -> "${formatStatusInstant(lastBackupAt)}；已超过 30 天，建议尽快重新备份。"
            BackupFreshness.CURRENT -> formatStatusInstant(lastBackupAt)
        },
        actionLabel = null,
        onAction = {}
    )
}

@Composable
private fun IntegrityStatusCard(value: DataIntegrityResult?) {
    SafetyStatusCard(
        title = "最近一次检查",
        healthy = value?.healthy == true,
        detail = when {
            value == null -> "尚未运行完整性检查"
            value.healthy -> "${formatStatusInstant(value.checkedAt)}；数据库和 ${value.attachmentCount} 份附件均通过检查"
            else -> buildString {
                append("${formatStatusInstant(value.checkedAt)}；发现 ${value.problemCount} 项异常")
                append("（缺失 ${value.missingAttachments}、大小 ${value.sizeMismatches}、校验值 ${value.checksumMismatches}、不可读 ${value.unreadableAttachments}、路径 ${value.invalidAttachmentPaths}、引用 ${value.foreignKeyViolations}）")
            }
        },
        actionLabel = null,
        onAction = {}
    )
}

private fun notificationStatusDetail(value: SafetySystemStatus): String = buildString {
    append(if (value.runtimeNotificationPermission) "通知权限正常" else "通知权限未允许")
    append(if (value.appNotificationsEnabled) "；总开关正常" else "；应用通知总开关已关闭")
    append(if (value.followUpChannelEnabled) "；复查提醒正常" else "；复查提醒类别已关闭")
    append(if (value.medicationChannelEnabled) "；用药提醒正常" else "；用药提醒类别已关闭")
}

private fun formatStatusInstant(value: String?): String {
    if (value.isNullOrBlank()) return "时间未知"
    return runCatching {
        DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
            .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault("时间记录异常")
}

@Composable
private fun FamilyMemberEditorDialog(
    existing: FamilyMemberEntity?,
    onSave: (FamilyMemberEntity, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var relationship by remember(existing) { mutableStateOf(existing?.relationship.orEmpty()) }
    var error by remember(existing) { mutableStateOf<String?>(null) }
    var submitting by remember(existing) { mutableStateOf(false) }
    val now = Instant.now().toString()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (existing == null) "添加家庭成员" else "编辑家庭成员") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(50) }, label = { Text("姓名 *") }, singleLine = true)
                OutlinedTextField(relationship, { relationship = it.take(30) }, label = { Text("关系 *") }, placeholder = { Text("例如：本人、母亲、父亲") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (name.isBlank() || relationship.isBlank()) {
                        error = "请完整填写姓名和关系"
                    } else {
                        submitting = true
                        onSave(
                            FamilyMemberEntity(
                                id = existing?.id ?: 0,
                                name = name.trim(),
                                nickname = relationship.trim(),
                                relationship = relationship.trim(),
                                archived = existing?.archived ?: false,
                                createdAt = existing?.createdAt ?: now,
                                updatedAt = now,
                                uuid = existing?.uuid ?: java.util.UUID.randomUUID().toString()
                            )
                        ) { submitting = false }
                    }
                }
            ) { Text(if (submitting) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } }
    )
}

@Composable
private fun ImportPreviewDialog(
    preview: BackupImportPreview,
    importedChoices: MutableMap<String, Boolean>,
    onMerge: () -> Unit,
    onImportLegacyAsNew: () -> Unit,
    onReplace: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (preview.legacyReplacementOnly) "旧版备份" else "导入预览") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (preview.legacyReplacementOnly) {
                    Text("该备份来自旧 v1 格式，没有稳定 UUID，无法可靠去重。可作为新资料导入当前成员（不会覆盖本机资料，但重复导入会产生重复），或经过安全备份后整体替换。")
                    TextButton(onClick = onReplace) { Text("高级：整体替换") }
                } else {
                    Text("新增 ${preview.additions} 项 · 可更新 ${preview.updates} 项 · 重复 ${preview.duplicates} 项")
                    if (preview.conflicts.isEmpty()) {
                        Text("没有冲突，可以安全合并。")
                    } else {
                        Text("发现 ${preview.conflicts.size} 项差异。默认保留本机；仅为确定需要的项目打开“使用导入版本”。")
                        preview.conflicts.forEach { conflict ->
                            val key = "${conflict.entityType}:${conflict.uuid}"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(conflictLabel(conflict), fontWeight = FontWeight.SemiBold)
                                    Text(conflict.uuid.take(8), style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(
                                    checked = importedChoices[key] == true,
                                    onCheckedChange = { importedChoices[key] = it }
                                )
                            }
                        }
                    }
                    TextButton(onClick = onReplace) { Text("高级：整体替换") }
                }
            }
        },
        confirmButton = {
            if (preview.legacyReplacementOnly) TextButton(onClick = onImportLegacyAsNew) { Text("作为新资料导入") }
            else TextButton(onClick = onMerge) { Text("安全合并") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun conflictLabel(value: MergeConflict): String {
    val type = when (value.entityType) {
        "member" -> "家庭成员"
        "condition" -> "病情分类"
        "record" -> "病历"
        "attachment" -> "附件"
        "followUp" -> "复查计划"
        "occurrence" -> "复查记录"
        "medication" -> "药物"
        "medicationSchedule" -> "用药计划"
        "medicationLog" -> "用药记录"
        else -> "数据"
    }
    return if (value.importedIsNewer) "$type（导入版本较新）" else "$type（本机版本较新或时间相同）"
}

private fun safeStartSettings(context: Context, primary: Intent) {
    runCatching { context.startActivity(primary) }.recoverCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
}

@Composable
private fun PasswordDialog(title: String, confirmLabel: String, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val isExport = title.contains("设置")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(password, { password = it.take(128) }, label = { Text("密码（至少 8 位）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (isExport) OutlinedTextField(confirmation, { confirmation = it.take(128) }, label = { Text("再次输入密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    password.length < 8 -> error = "密码至少需要 8 位"
                    isExport && confirmation != password -> error = "两次输入的密码不一致"
                    else -> onConfirm(password.toCharArray())
                }
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
