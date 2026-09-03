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

@Composable
fun SettingsScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val context = LocalContext.current
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val exactAllowed = viewModel.canScheduleExact()
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionRefresh++ }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = exportPassword
        if (uri != null && password != null) viewModel.exportBackup(uri, password)
        exportPassword = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; passwordMode = "restore" }
    }

    Column(
        Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("提醒权限", style = MaterialTheme.typography.titleMedium)
        PermissionCard(
            "通知权限",
            if (notificationPermission) "已允许" else "未允许：不会显示任何复查或用药通知",
            notificationPermission
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        Text("备份包含全部病历、提醒、用药记录和检查报告。密码无法找回，请妥善保存。", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { passwordMode = "export" }) {
            Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(8.dp)); Text("导出加密备份")
        }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) }) {
            Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(8.dp)); Text("从备份恢复")
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
            confirmLabel = if (passwordMode == "export") "选择保存位置" else "验证并恢复",
            onConfirm = { password ->
                if (passwordMode == "export") {
                    exportPassword = password
                    exportLauncher.launch("病程日历-${LocalDate.now()}.htbackup")
                } else {
                    exportPassword = password
                    confirmRestore = true
                }
                passwordMode = null
            },
            onDismiss = { passwordMode = null; restoreUri = null }
        )
    }
    if (confirmRestore) {
        ConfirmDialog(
            "恢复备份？",
            "验证成功后，手机当前的全部病程数据将由备份内容整体替换。验证失败不会修改现有数据。",
            onConfirm = {
                val uri = restoreUri
                val password = exportPassword
                if (uri != null && password != null) viewModel.restoreBackup(uri, password)
                restoreUri = null; exportPassword = null; confirmRestore = false
            },
            onDismiss = { confirmRestore = false; restoreUri = null; exportPassword = null }
        )
    }
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
