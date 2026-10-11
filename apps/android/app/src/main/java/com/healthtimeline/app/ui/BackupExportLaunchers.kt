package com.healthtimeline.app.ui

import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

internal class BackupDocumentActions(val launch: (BackupExportKind, String) -> Boolean)

/** Root registrations outlive medical pages; only opaque destination/session metadata is saved. */
@Composable
internal fun rememberBackupDocumentActions(
    flow: BackupExportFlowViewModel,
    onExternalStarted: () -> Unit,
    onExternalFinished: () -> Unit,
    canLaunch: () -> Boolean
): BackupDocumentActions {
    val registry = requireNotNull(LocalActivityResultRegistryOwner.current).activityResultRegistry
    val started by rememberUpdatedState(onExternalStarted)
    val finished by rememberUpdatedState(onExternalFinished)
    var previousSession by rememberSaveable { mutableStateOf(flow.registrySession) }
    var unfinished by rememberSaveable { mutableStateOf(false) }
    var returnedDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var orphanSessions by rememberSaveable { mutableStateOf(listOf<String>()) }
    var export by remember(registry, flow) { mutableStateOf<ActivityResultLauncher<String>?>(null) }
    var safety by remember(registry, flow) { mutableStateOf<ActivityResultLauncher<String>?>(null) }

    if (previousSession != flow.registrySession) {
        if (unfinished) {
            orphanSessions = orphanSessions + previousSession
            // A result delivered while locked won't be replayed, but its empty document can be checked.
            flow.reportInterruptedProcess(returnedDestination)
        }
        previousSession = flow.registrySession
        unfinished = false
        returnedDestination = null
    }

    fun finishOrphan(session: String, destination: String?) {
        // Never end a newer picker's exemption or complete its request with this old result.
        flow.reportInterruptedProcess(destination)
        orphanSessions = orphanSessions.filterNot { it == session }
    }
    orphanSessions.forEach { session ->
        key(session) {
            DisposableEffect(registry, session) {
                val oldExport = registry.register("backup-document/$session/export", ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    finishOrphan(session, uri?.toString())
                }
                val oldSafety = registry.register("backup-document/$session/safety", ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    finishOrphan(session, uri?.toString())
                }
                onDispose { oldExport.unregister(); oldSafety.unregister() }
            }
        }
    }
    DisposableEffect(registry, flow) {
        val session = flow.registrySession
        val newExport = registry.register("backup-document/$session/export", ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            finished()
            returnedDestination = uri?.toString()
            flow.receive(returnedDestination)
        }
        val newSafety = registry.register("backup-document/$session/safety", ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            finished()
            returnedDestination = uri?.toString()
            flow.receive(returnedDestination)
        }
        export = newExport
        safety = newSafety
        onDispose {
            newExport.unregister(); newSafety.unregister()
            export = null
            safety = null
        }
    }
    SideEffect {
        unfinished = flow.busy.value
        if (!unfinished) returnedDestination = null
    }
    return BackupDocumentActions { kind, name ->
        val launcher = if (kind == BackupExportKind.EXPORT) export else safety
        if (!canLaunch() || launcher == null) {
            flow.cancelAfterLaunchFailure()
            false
        } else try {
            unfinished = true
            returnedDestination = null
            started()
            launcher.launch(name)
            true
        } catch (_: Exception) {
            finished()
            flow.cancelAfterLaunchFailure()
            unfinished = false
            false
        }
    }
}
