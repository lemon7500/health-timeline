package com.healthtimeline.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.healthtimeline.app.data.AttachmentImportTarget
import com.healthtimeline.app.ui.theme.HealthTimelineTheme

/** Debug-only: the production host with real registry state and simulated platform launches. */
class ExternalImportHarnessActivity : ComponentActivity() {
    internal val registry = HarnessResultRegistry()
    internal val fixture by lazy { ViewModelProvider(this)[ExternalImportFixture::class.java] }
    internal val flow by lazy { ViewModelProvider(this)[ExternalImportFlowViewModel::class.java] }
    internal val backupFlow by lazy { ViewModelProvider(this)[BackupExportFlowViewModel::class.java] }
    internal lateinit var actions: ExternalImportActions
    internal lateinit var backupActions: BackupDocumentActions
    internal lateinit var openLegacyDetail: () -> Unit
    internal lateinit var launchLegacyDocuments: () -> Unit
    private var dropMemoryAfterSaving = false

    internal fun simulateLostProcessMemoryOnRecreation() { dropMemoryAfterSaving = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.onRestoreInstanceState(savedInstanceState?.getBundle("synthetic-registry"))
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                HealthTimelineTheme {
                    if (intent.getBooleanExtra("legacy", false)) LegacyLocalDetail() else {
                        val member = fixture.memberState.value
                        flow.bindMember(member.id, member.uuid)
                        backupFlow.bindMember(member.id, member.uuid)
                        actions = rememberExternalImportActions(
                            flow = flow,
                            memberId = member.id,
                            memberUuid = member.uuid,
                            locked = fixture.locked.value,
                            canProcess = { !fixture.locked.value },
                            onExternalStarted = { fixture.started++ },
                            onExternalFinished = { fixture.finished++ },
                            onAttachmentsReady = { target, uris, cameraUri ->
                                fixture.attachments.add(target to uris)
                                cameraUri?.let { fixture.cameraAfterImport.add(it) }
                            },
                            onBackupReady = { fixture.backupPrompts++ },
                            discardCamera = { fixture.discarded.add(it) }
                        )
                        backupActions = rememberBackupDocumentActions(
                            flow = backupFlow,
                            onExternalStarted = { fixture.started++ },
                            onExternalFinished = { fixture.finished++ },
                            canLaunch = { !fixture.locked.value }
                        )
                        LaunchedEffect(fixture.locked.value, backupFlow.revision.value, member) {
                            if (!fixture.locked.value) when (val result = backupFlow.takeCompletion(member.id, member.uuid)) {
                                is BackupExportFlowViewModel.Completion.Ready -> {
                                    fixture.exportDestinations.add(result.kind to result.destination)
                                    fixture.exportSourceUris.add(result.source)
                                    result.password.fill('\u0000')
                                }
                                else -> Unit
                            }
                        }
                        LaunchedEffect(fixture.locked.value, backupFlow.abortedDestinations.value) {
                            if (!fixture.locked.value) backupFlow.abortedDestinations.value.forEach { uri ->
                                fixture.abortedExportDestinations.add(uri)
                                backupFlow.acknowledgeAbortedDestination(uri)
                            }
                        }
                        // Matches the real root's early return, removing all medical UI but not launchers.
                        if (fixture.locked.value) Text("已锁定") else Text("测试医疗页面")
                    }
                }
            }
        }
    }

    @Composable
    private fun LegacyLocalDetail() {
        var detail by remember { mutableStateOf(false) }
        openLegacyDetail = { detail = true }
        if (fixture.locked.value || !detail) {
            Text("详情未打开")
            return
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fixture.attachments.add(fixture.target to uris)
        }
        launchLegacyDocuments = { launcher.launch(arrayOf("image/*", "application/pdf")) }
        Text("旧版本地详情")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("synthetic-registry", Bundle().also(registry::onSaveInstanceState))
        super.onSaveInstanceState(outState)
        // An OS kill doesn't run ViewModel.onCleared before saving. Save first, then drop the store.
        // This is deterministic memory-loss simulation, not a claim of testing a real OS process kill.
        if (dropMemoryAfterSaving) viewModelStore.clear()
    }
}

internal class HarnessResultRegistry : ActivityResultRegistry() {
    var lastRequestCode = 0
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?
    ) {
        lastRequestCode = requestCode
    }
}

internal class ExternalImportFixture : ViewModel() {
    val memberState = mutableStateOf(ExternalImportMember(1, "member-a"))
    val member get() = memberState.value
    val target = AttachmentImportTarget(4, "record-a", 1, "member-a")
    val locked = mutableStateOf(false)
    val attachments = mutableListOf<Pair<AttachmentImportTarget, List<Uri>>>()
    val discarded = mutableListOf<Uri>()
    val cameraAfterImport = mutableListOf<Uri>()
    val exportDestinations = mutableListOf<Pair<BackupExportKind, String>>()
    val exportSourceUris = mutableListOf<String?>()
    val abortedExportDestinations = mutableListOf<String>()
    var backupPrompts = 0
    var started = 0
    var finished = 0
}
