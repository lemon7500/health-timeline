package com.healthtimeline.app.ui

import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.healthtimeline.app.data.AttachmentImportTarget

internal class ExternalImportActions(
    val camera: (AttachmentImportTarget, Uri) -> Boolean,
    val attachments: (AttachmentImportTarget) -> Boolean,
    val backup: (ExternalImportMember) -> Boolean
)

private data class ImportLaunchers(
    val camera: ActivityResultLauncher<Uri>,
    val attachments: ActivityResultLauncher<Array<String>>,
    val backup: ActivityResultLauncher<Array<String>>
)

/** Must be composed before the locked-root early return, not inside a medical detail page. */
@Composable
internal fun rememberExternalImportActions(
    flow: ExternalImportFlowViewModel,
    memberId: Long?,
    memberUuid: String?,
    locked: Boolean,
    canProcess: () -> Boolean,
    onExternalStarted: () -> Unit,
    onExternalFinished: () -> Unit,
    onAttachmentsReady: (AttachmentImportTarget, List<Uri>, Uri?) -> Unit,
    onBackupReady: (BackupImportSelection) -> Unit,
    discardCamera: (Uri) -> Unit
): ExternalImportActions {
    val registry = requireNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val started by rememberUpdatedState(onExternalStarted)
    val finished by rememberUpdatedState(onExternalFinished)
    val discard by rememberUpdatedState(discardCamera)
    // Framework metadata only. No target, member identity, password or medical text enters a Bundle.
    var previousSession by rememberSaveable { mutableStateOf(flow.registrySession) }
    var unfinished by rememberSaveable { mutableStateOf(false) }
    var cameraCleanupUri by rememberSaveable { mutableStateOf<String?>(null) }
    var orphanSessions by rememberSaveable { mutableStateOf(listOf<String>()) }
    var orphanCameraUris by rememberSaveable { mutableStateOf(listOf<String>()) }
    var activeLaunchers by remember(registry, flow) { mutableStateOf<ImportLaunchers?>(null) }

    if (previousSession != flow.registrySession) {
        // Distinguish process-memory loss from rotation without restoring the lost target.
        if (unfinished) {
            orphanSessions = orphanSessions + previousSession
            orphanCameraUris = orphanCameraUris + cameraCleanupUri.orEmpty()
            flow.reportInterruptedProcess()
        }
        previousSession = flow.registrySession
        cameraCleanupUri = null
        unfinished = false
    }

    fun finishOrphan(session: String) {
        // The old process's exemption is gone. Never end a new request's external-activity exemption.
        flow.reportInterruptedProcess()
        val index = orphanSessions.indexOf(session)
        if (index >= 0) {
            orphanCameraUris.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { discard(Uri.parse(it)) }
            orphanSessions = orphanSessions.filterIndexed { i, _ -> i != index }
            orphanCameraUris = orphanCameraUris.filterIndexed { i, _ -> i != index }
        }
    }

    // Old process keys have abort-only handlers: a delayed result cannot complete a new request.
    orphanSessions.forEach { session ->
        key(session) {
            DisposableEffect(registry, session) {
                val camera = registry.register("external-import/$session/camera", ActivityResultContracts.TakePicture()) { finishOrphan(session) }
                val documents = registry.register("external-import/$session/attachments", ActivityResultContracts.OpenMultipleDocuments()) { finishOrphan(session) }
                val backup = registry.register("external-import/$session/backup", ActivityResultContracts.OpenDocument()) { finishOrphan(session) }
                onDispose { camera.unregister(); documents.unregister(); backup.unregister() }
            }
        }
    }

    DisposableEffect(registry, flow) {
        val session = flow.registrySession
        val camera = registry.register("external-import/$session/camera", ActivityResultContracts.TakePicture()) { success ->
            finished()
            flow.receiveCamera(success, cameraCleanupUri)
            // Retain cleanup metadata until consumption, including a result delivered while locked.
        }
        val documents = registry.register("external-import/$session/attachments", ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            finished()
            flow.receiveDocuments(uris.map(Uri::toString))
        }
        val backup = registry.register("external-import/$session/backup", ActivityResultContracts.OpenDocument()) { uri ->
            finished()
            flow.receiveBackup(uri?.toString())
        }
        activeLaunchers = ImportLaunchers(camera, documents, backup)
        onDispose {
            camera.unregister(); documents.unregister(); backup.unregister()
            activeLaunchers = null
        }
    }

    SideEffect { unfinished = flow.busy.value || flow.backupSource.value != null }
    LaunchedEffect(locked, lifecycleState, orphanSessions, orphanCameraUris) {
        if (!locked && lifecycleState.isAtLeast(Lifecycle.State.STARTED) && canProcess()) {
            // The old result may have been delivered while locked, so no callback will be replayed.
            // Its lost target is irrevocably aborted; clean only owned temporary camera files.
            orphanCameraUris.forEach { value -> if (value.isNotEmpty()) discard(Uri.parse(value)) }
            orphanCameraUris = orphanCameraUris.map { "" }
        }
    }
    LaunchedEffect(locked, lifecycleState, flow.revision.value, memberId, memberUuid) {
        if (!locked && lifecycleState.isAtLeast(Lifecycle.State.STARTED) && canProcess()) {
            // No suspension between consuming a valid target and handing it to a durable import job.
            when (val completion = flow.takeCompletion(memberId, memberUuid, locked = !canProcess())) {
                is ExternalImportFlowViewModel.Completion.Attachments -> {
                    onAttachmentsReady(completion.target, completion.uris.map(Uri::parse), completion.cameraUri?.let(Uri::parse))
                    cameraCleanupUri = null
                }
                is ExternalImportFlowViewModel.Completion.Backup -> onBackupReady(completion.selection)
                is ExternalImportFlowViewModel.Completion.Cancelled -> {
                    completion.cameraUri?.let { discard(Uri.parse(it)) }
                    cameraCleanupUri = null
                }
                is ExternalImportFlowViewModel.Completion.Aborted -> {
                    completion.cameraUri?.let { discard(Uri.parse(it)) }
                    cameraCleanupUri = null
                }
                null -> Unit
            }
        }
    }
    return ExternalImportActions(
        camera = cameraAction@{ target, uri ->
            val launchers = activeLaunchers
            if (!canProcess() || launchers == null || !flow.beginCamera(target, uri.toString())) {
                discard(uri)
                return@cameraAction false
            }
            cameraCleanupUri = uri.toString()
            unfinished = true
            try {
                started()
                launchers.camera.launch(uri)
                true
            } catch (_: Exception) {
                finished()
                flow.launchFailed(ExternalImportKind.CAMERA)?.let { discard(Uri.parse(it)) }
                cameraCleanupUri = null
                unfinished = false
                false
            }
        },
        attachments = attachmentsAction@{ target ->
            val launchers = activeLaunchers
            if (!canProcess() || launchers == null || !flow.beginAttachments(target)) return@attachmentsAction false
            unfinished = true
            try {
                started()
                launchers.attachments.launch(arrayOf("image/*", "application/pdf"))
                true
            } catch (_: Exception) {
                finished()
                flow.launchFailed(ExternalImportKind.ATTACHMENTS)
                unfinished = false
                false
            }
        },
        backup = backupAction@{ member ->
            val launchers = activeLaunchers
            if (!canProcess() || launchers == null || !flow.beginBackup(member)) return@backupAction false
            unfinished = true
            try {
                started()
                launchers.backup.launch(arrayOf("application/octet-stream", "*/*"))
                true
            } catch (_: Exception) {
                finished()
                flow.launchFailed(ExternalImportKind.BACKUP)
                unfinished = false
                false
            }
        }
    )
}
