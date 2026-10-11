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
import androidx.compose.material.icons.outlined.Today
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.healthtimeline.app.reminders.NotificationReviewRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import java.time.LocalDate

private enum class Tab(val label: String) {
    TODAY("今日"), CALENDAR("日历"), FOLLOW_UP("复查"), MEDICATION("用药"), SETTINGS("设置")
}

@Composable
fun HealthTimelineRoot(
    viewModel: AppViewModel,
    appLockManager: AppLockManager,
    authenticate: (() -> Unit) -> Unit,
    notificationReview: NotificationReviewRequest? = null,
    onNotificationReviewConsumed: (NotificationReviewRequest) -> Unit = {}
) {
    val drafts: UiDraftStateViewModel = composeViewModel()
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var navigation by drafts.navigationState
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var lockWriteError by remember { mutableStateOf<String?>(null) }
    fun reportLockWriteFailure() {
        val message = "无法保存保护设置，本次更改未确认成功。请检查手机剩余空间后重试。"
        lockWriteError = message
        uiScope.launch { snackbar.showSnackbar(message) }
    }
    val busy by viewModel.busy.collectAsState()
    val locked by appLockManager.locked.collectAsStateWithLifecycle()
    val appLockEnabled by appLockManager.enabled.collectAsStateWithLifecycle()
    var showLockIntroduction by remember { mutableStateOf(appLockManager.shouldShowIntroduction()) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshTodayOverview()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val selectedMemberId by viewModel.selectedMemberId.collectAsStateWithLifecycle()
    val backupFlow: BackupExportFlowViewModel = composeViewModel()
    backupFlow.bindMember(selectedMemberId)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        appLockManager.endExternalActivity()
        backupFlow.receive(uri?.toString())
    }
    val safetyExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        appLockManager.endExternalActivity()
        backupFlow.receive(uri?.toString())
    }
    fun beginBackupExport(kind: BackupExportKind, password: CharArray, source: Uri? = null) {
        if (!backupFlow.begin(kind, viewModel.selectedMemberId.value, password, source?.toString())) {
            uiScope.launch { snackbar.showSnackbar("已有备份选择操作，或尚未选择成员。请完成当前操作后重试。") }
            return
        }
        appLockManager.beginExternalActivity()
        runCatching {
            if (kind == BackupExportKind.EXPORT) exportLauncher.launch("病程日历-${LocalDate.now()}.htbackup")
            else safetyExportLauncher.launch("病程日历-替换前安全备份-${LocalDate.now()}.htbackup")
        }.onFailure {
            appLockManager.endExternalActivity()
            backupFlow.cancelAfterLaunchFailure()
            uiScope.launch { snackbar.showSnackbar("无法打开保存位置，备份操作未执行，请重试。") }
        }
    }
    // Launchers remain registered while locked; a result waits for authentication before any operation.
    LaunchedEffect(locked, backupFlow.abortMessage.value) {
        if (!locked) backupFlow.abortMessage.value?.let { message ->
            snackbar.showSnackbar(message)
            // Cancellation on rotation/locking leaves the event pending for the next unlocked page.
            backupFlow.acknowledgeAbort(message)
        }
    }
    LaunchedEffect(locked, backupFlow.revision.value, selectedMemberId) {
        if (!locked) when (val completion = backupFlow.takeCompletion(viewModel.selectedMemberId.value)) {
            is BackupExportFlowViewModel.Completion.Ready -> {
                if (completion.kind == BackupExportKind.EXPORT) {
                    viewModel.exportBackup(Uri.parse(completion.destination), completion.password)
                } else {
                    viewModel.restoreBackupWithSafetyExport(
                        Uri.parse(completion.destination), Uri.parse(requireNotNull(completion.source)), completion.password,
                        completion.memberId
                    )
                }
            }
            is BackupExportFlowViewModel.Completion.Aborted -> {
                // Delete only a provably empty document, never an existing valid backup.
                withContext(Dispatchers.IO) {
                    completion.destination?.let { destination ->
                        runCatching {
                            val uri = Uri.parse(destination)
                            val empty = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                                cursor.moveToFirst() && column >= 0 && !cursor.isNull(column) && cursor.getLong(column) == 0L
                            } == true
                            if (empty && DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }
                    }
                }
            }
            else -> Unit
        }
    }
    // Bind before composing forms; clearing afterward could discard a just-created draft on rotation.
    drafts.bindMember(selectedMemberId)
    LaunchedEffect(selectedMemberId) {
        // A notification can switch member and create its destination in the same frame.
        if (navigation?.memberId != selectedMemberId) navigation = null
    }
    LaunchedEffect(locked, notificationReview) {
        notificationReview?.takeUnless { locked }?.let { request ->
            try {
                viewModel.reviewMedicationNotification(request)?.let { target ->
                    navigation = target
                    tab = Tab.MEDICATION
                }
            } finally {
                // Keep an unprocessed intent if locking or recreation cancels validation.
                if (currentCoroutineContext().isActive) onNotificationReviewConsumed(request)
            }
        }
    }

    DisposableEffect(lifecycleOwner, appLockManager) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    appLockManager.onAppForegrounded()
                    viewModel.onOverviewForegrounded()
                }
                Lifecycle.Event.ON_STOP -> {
                    appLockManager.onAppBackgrounded()
                    viewModel.onOverviewBackgrounded()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) viewModel.onOverviewForegrounded()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                viewModel.refreshTodayOverview()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            viewModel.onOverviewBackgrounded()
        }
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

    val overview by viewModel.todayOverview.collectAsStateWithLifecycle()
    val members by viewModel.members.collectAsStateWithLifecycle()
    val todayActionBusy by viewModel.todayActionBusy.collectAsStateWithLifecycle()
    // The selection flow changes before a new database snapshot arrives.
    val visibleOverview = overview.copy(
        member = overview.member?.takeIf { it.id == selectedMemberId },
        data = overview.data?.takeIf { it.memberId == selectedMemberId }
    )
    fun consumeNavigation(token: String) {
        navigation = consumeTodayNavigation(navigation, token)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    val icon = when (item) {
                        Tab.TODAY -> Icons.Outlined.Today
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
                Tab.TODAY -> TodayScreen(
                    state = visibleOverview,
                    padding = padding,
                    members = members,
                    onSelectMember = viewModel::selectMember,
                    onAddRecord = {
                        visibleOverview.member?.let { navigation = TodayNavigation.AddRecord(it.id, visibleOverview.date); tab = Tab.CALENDAR }
                    },
                    onRecordDose = viewModel::recordOverviewDose,
                    onRecordAsNeeded = viewModel::recordOverviewAsNeeded,
                    onConfirmFollowUp = viewModel::confirmOverviewFollowUp,
                    onOpenMedication = {
                        visibleOverview.member?.let { navigation = TodayNavigation.MedicationDay(it.id, visibleOverview.date); tab = Tab.MEDICATION }
                    },
                    onOpenFollowUp = { item ->
                        navigation = TodayNavigation.FollowUpDetail(item.schedule.memberId, item)
                        tab = Tab.FOLLOW_UP
                    },
                    onOpenSafety = {
                        visibleOverview.member?.let { navigation = TodayNavigation.Safety(it.id); tab = Tab.SETTINGS }
                    },
                    busy = busy || todayActionBusy,
                    onRetry = viewModel::refreshTodayOverview
                )
                Tab.CALENDAR -> CalendarScreen(viewModel, padding, navigation as? TodayNavigation.AddRecord, ::consumeNavigation)
                Tab.FOLLOW_UP -> FollowUpScreen(viewModel, padding, navigation as? TodayNavigation.FollowUpDetail, ::consumeNavigation)
                Tab.MEDICATION -> MedicationScreen(viewModel, padding, navigation as? TodayNavigation.MedicationDay, ::consumeNavigation)
                Tab.SETTINGS -> SettingsScreen(
                    viewModel,
                    padding,
                    appLockEnabled = appLockEnabled,
                    onAppLockChange = { enabled ->
                        authenticate { if (!appLockManager.setEnabled(enabled)) reportLockWriteFailure() }
                    },
                    navigation = navigation as? TodayNavigation.Safety,
                    onNavigationConsumed = ::consumeNavigation,
                    backupPickerBusy = backupFlow.busy.value,
                    onExportRequest = { beginBackupExport(BackupExportKind.EXPORT, it) },
                    onSafetyExportRequest = { source, password -> beginBackupExport(BackupExportKind.SAFETY_BEFORE_REPLACE, password, source) }
                )
            }
            if (busy) LinearProgressIndicator()
        }
    }

    if (showLockIntroduction) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("保护家庭病历") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("你可以使用手机指纹、面容或锁屏密码保护病程日历。此功能稍后也可在设置中更改。")
                    lockWriteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    authenticate {
                        if (appLockManager.setEnabled(true) && appLockManager.markIntroductionShown()) {
                            showLockIntroduction = false
                        } else reportLockWriteFailure()
                    }
                }) { Text("开启保护") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (appLockManager.markIntroductionShown()) showLockIntroduction = false
                    else reportLockWriteFailure()
                }) { Text("稍后") }
            }
        )
    }
}
