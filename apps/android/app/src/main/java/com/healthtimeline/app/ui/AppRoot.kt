package com.healthtimeline.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.healthtimeline.app.security.AppLockManager

private enum class Tab(val label: String) {
    CALENDAR("日历"), FOLLOW_UP("复查"), MEDICATION("用药"), SETTINGS("设置")
}

@Composable
fun HealthTimelineRoot(
    viewModel: AppViewModel,
    appLockManager: AppLockManager,
    authenticate: (() -> Unit) -> Unit
) {
    var tab by rememberSaveable { mutableStateOf(Tab.CALENDAR) }
    val snackbar = remember { SnackbarHostState() }
    val busy by viewModel.busy.collectAsState()
    val locked by appLockManager.locked.collectAsStateWithLifecycle()
    val appLockEnabled by appLockManager.enabled.collectAsStateWithLifecycle()
    var showLockIntroduction by remember { mutableStateOf(appLockManager.shouldShowIntroduction()) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, appLockManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appLockManager.onAppForegrounded()
                Lifecycle.Event.ON_STOP -> appLockManager.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(locked) {
        if (!locked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { snackbar.showSnackbar(it) }
    }

    if (locked) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("病程日历已锁定", style = MaterialTheme.typography.headlineSmall)
                Text("请使用手机指纹、面容或锁屏密码验证身份。", modifier = Modifier.padding(vertical = 16.dp))
                Button(onClick = { authenticate(appLockManager::unlock) }) { Text("验证并进入") }
            }
        }
        LaunchedEffect(Unit) { authenticate(appLockManager::unlock) }
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    val icon = when (item) {
                        Tab.CALENDAR -> Icons.Outlined.CalendarMonth
                        Tab.FOLLOW_UP -> Icons.Outlined.EventRepeat
                        Tab.MEDICATION -> Icons.Outlined.Medication
                        Tab.SETTINGS -> Icons.Outlined.Settings
                    }
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(icon, item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                Tab.CALENDAR -> CalendarScreen(viewModel, padding)
                Tab.FOLLOW_UP -> FollowUpScreen(viewModel, padding)
                Tab.MEDICATION -> MedicationScreen(viewModel, padding)
                Tab.SETTINGS -> SettingsScreen(
                    viewModel,
                    padding,
                    appLockEnabled = appLockEnabled,
                    onAppLockChange = { enabled ->
                        authenticate { appLockManager.setEnabled(enabled) }
                    }
                )
            }
            if (busy) LinearProgressIndicator()
        }
    }

    if (showLockIntroduction) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("保护家庭病历") },
            text = { Text("你可以使用手机指纹、面容或锁屏密码保护病程日历。此功能稍后也可在设置中更改。") },
            confirmButton = {
                TextButton(onClick = {
                    authenticate {
                        appLockManager.setEnabled(true)
                        appLockManager.markIntroductionShown()
                        showLockIntroduction = false
                    }
                }) { Text("开启保护") }
            },
            dismissButton = {
                TextButton(onClick = {
                    appLockManager.markIntroductionShown()
                    showLockIntroduction = false
                }) { Text("稍后") }
            }
        )
    }
}
