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
import kotlinx.coroutines.launch

private enum class Tab(val label: String) {
    CALENDAR("日历"), FOLLOW_UP("复查"), MEDICATION("用药"), SETTINGS("设置")
}

@Composable
fun HealthTimelineRoot(viewModel: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.CALENDAR) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val busy by viewModel.busy.collectAsState()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.messageFlow.collect { snackbar.showSnackbar(it) }
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
                Tab.SETTINGS -> SettingsScreen(viewModel, padding)
            }
            if (busy) LinearProgressIndicator()
        }
    }
}
