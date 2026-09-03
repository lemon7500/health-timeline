package com.healthtimeline.app.ui

import com.healthtimeline.app.BuildConfig
import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.LocalDate
import com.healthtimeline.shared.BackupImportPreview
import com.healthtimeline.shared.MergeChoice
import com.healthtimeline.shared.MergeConflict
import com.healthtimeline.app.data.FamilyMemberEntity
import java.time.Instant
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
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val exactAllowed = viewModel.canScheduleExact()
    val members by viewModel.members.collectAsState()
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

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        appLockManager.endExternalActivity()
        permissionRefresh++
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
                    Text(member.nickname, fontWeight = FontWeight.SemiBold)
                    Text("${member.name} · ${member.relationship}${if (member.archived) " · 已归档" else ""}")
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
        Text("提醒权限", style = MaterialTheme.typography.titleMedium)
        PermissionCard(
            "通知权限",
            if (notificationPermission) "已允许" else "未允许：不会显示任何复查或用药通知",
            notificationPermission
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appLockManager.beginExternalActivity()
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else safeStartSettings(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        PermissionCard(
            "精确闹钟",
            if (exactAllowed) "已允许" else "未允许：提醒仍会安排，但可能延迟",
            exactAllowed
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                safeStartSettings(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
        OutlinedButton(onClick = { safeStartSettings(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
            Icon(Icons.Outlined.Notifications, null); Spacer(Modifier.width(8.dp)); Text("检查后台与电池优化设置")
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
            "归档${member.nickname}？",
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
private fun FamilyMemberEditorDialog(
    existing: FamilyMemberEntity?,
    onSave: (FamilyMemberEntity, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var nickname by remember(existing) { mutableStateOf(existing?.nickname.orEmpty()) }
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
                OutlinedTextField(nickname, { nickname = it.take(30) }, label = { Text("称呼 *") }, placeholder = { Text("例如：妈妈") }, singleLine = true)
                OutlinedTextField(relationship, { relationship = it.take(30) }, label = { Text("关系 *") }, placeholder = { Text("例如：母亲") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting,
                onClick = {
                    if (name.isBlank() || nickname.isBlank() || relationship.isBlank()) {
                        error = "请完整填写姓名、称呼和关系"
                    } else {
                        submitting = true
                        onSave(
                            FamilyMemberEntity(
                                id = existing?.id ?: 0,
                                name = name.trim(),
                                nickname = nickname.trim(),
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
private fun PermissionCard(title: String, detail: String, allowed: Boolean, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (allowed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, style = MaterialTheme.typography.bodySmall) }
            if (!allowed) TextButton(onClick = onClick) { Text("去设置") }
        }
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
