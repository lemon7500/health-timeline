package com.healthtimeline.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.healthtimeline.app.data.AttachmentImportTarget
import java.util.UUID

internal data class ExternalImportMember(val id: Long, val uuid: String)
internal data class BackupImportSelection(val token: String, val member: ExternalImportMember, val uri: String)
internal enum class ExternalImportKind { CAMERA, ATTACHMENTS, BACKUP }

/** Only in-memory identities/URIs, never passwords, clinical text, SavedState or database drafts. */
internal class ExternalImportFlowViewModel : ViewModel() {
    // A new process gets different registry keys; rotation retains this same ViewModel/session.
    val registrySession = UUID.randomUUID().toString()
    private data class Request(
        val kind: ExternalImportKind,
        val member: ExternalImportMember,
        val target: AttachmentImportTarget? = null,
        val cameraUri: String? = null,
        var invalidated: Boolean = false
    )
    private data class Response(val kind: ExternalImportKind, val uris: List<String>, val cameraUri: String?)
    private var request: Request? = null
    private var response: Response? = null
    private var bound = false
    private var boundMemberId: Long? = null
    private var boundMemberUuid: String? = null
    var selectionEpoch = 0L
        private set
    val busy = mutableStateOf(false)
    val revision = mutableStateOf(0L)
    val backupSource = mutableStateOf<BackupImportSelection?>(null)
    val abortMessage = mutableStateOf<String?>(null)

    sealed interface Completion {
        data class Attachments(val target: AttachmentImportTarget, val uris: List<String>, val cameraUri: String?) : Completion
        data class Backup(val selection: BackupImportSelection) : Completion
        data class Aborted(val cameraUri: String?) : Completion
        data class Cancelled(val cameraUri: String?) : Completion
    }

    fun beginAttachments(target: AttachmentImportTarget): Boolean = begin(
        Request(ExternalImportKind.ATTACHMENTS, target.member(), target)
    )

    fun beginCamera(target: AttachmentImportTarget, uri: String): Boolean = begin(
        Request(ExternalImportKind.CAMERA, target.member(), target, uri)
    )

    fun beginBackup(member: ExternalImportMember): Boolean = begin(Request(ExternalImportKind.BACKUP, member))

    private fun begin(value: Request): Boolean {
        if (busy.value || backupSource.value != null) return false
        request = value
        response = null
        busy.value = true
        return true
    }

    /** A -> B -> A is still an abort. Unknown UUID during initial database loading is not a switch. */
    fun bindMember(id: Long?, uuid: String?, memberListLoaded: Boolean = false) {
        if (bound && (boundMemberId != id || (uuid != null && boundMemberUuid != null && uuid != boundMemberUuid))) selectionEpoch++
        if (boundMemberId != id) boundMemberUuid = null
        bound = true
        boundMemberId = id
        if (uuid != null) boundMemberUuid = uuid
        fun changed(expected: ExternalImportMember) = expected.id != id ||
            (uuid != null && expected.uuid != uuid) || (memberListLoaded && uuid == null)
        request?.let {
            if (!it.invalidated && changed(it.member)) {
                it.invalidated = true
                revision.value++
            }
        }
        backupSource.value?.let { source ->
            if (changed(source.member)) {
                backupSource.value = null
                abortMessage.value = ABORT_MESSAGE
                revision.value++
            }
        }
    }

    fun receiveDocuments(uris: List<String>) = receive(Response(ExternalImportKind.ATTACHMENTS, uris.toList(), null))
    fun receiveBackup(uri: String?) = receive(Response(ExternalImportKind.BACKUP, listOfNotNull(uri), null))
    fun receiveCamera(success: Boolean, recoveredCameraUri: String?) {
        val uri = request?.takeIf { it.kind == ExternalImportKind.CAMERA }?.cameraUri ?: recoveredCameraUri
        receive(Response(ExternalImportKind.CAMERA, if (success) listOfNotNull(uri) else emptyList(), uri))
    }

    private fun receive(value: Response) {
        // Another contract's stale callback cannot complete or replace a newer request.
        if (request != null && request?.kind != value.kind) {
            abortMessage.value = ABORT_MESSAGE
            return
        }
        if (response != null) return // Each launch completes at most once.
        response = value
        revision.value++
    }

    fun takeCompletion(id: Long?, uuid: String?, locked: Boolean): Completion? {
        if (locked) return null
        val result = response ?: return null
        val pending = request
        // A retained request may return before the restarted database's member list arrives.
        if (pending != null && !pending.invalidated && pending.member.id == id && uuid == null) return null
        request = null
        response = null
        busy.value = false
        if (pending == null || pending.invalidated || pending.member.id != id || pending.member.uuid != uuid) {
            abortMessage.value = ABORT_MESSAGE
            return Completion.Aborted(result.cameraUri ?: pending?.cameraUri)
        }
        if (result.uris.isEmpty()) return Completion.Cancelled(pending.cameraUri)
        return when (pending.kind) {
            ExternalImportKind.BACKUP -> {
                val source = BackupImportSelection(UUID.randomUUID().toString(), pending.member, result.uris.single())
                backupSource.value = source
                Completion.Backup(source)
            }
            ExternalImportKind.CAMERA, ExternalImportKind.ATTACHMENTS -> Completion.Attachments(
                requireNotNull(pending.target), result.uris, pending.cameraUri
            )
        }
    }

    fun finishBackup(token: String) {
        if (backupSource.value?.token == token) backupSource.value = null
    }

    fun acknowledgeAbort(message: String) {
        if (abortMessage.value == message) abortMessage.value = null
    }

    fun reportInterruptedProcess() {
        abortMessage.value = ABORT_MESSAGE
    }

    fun launchFailed(kind: ExternalImportKind): String? {
        val pending = request?.takeIf { it.kind == kind } ?: return null
        request = null
        response = null
        busy.value = false
        abortMessage.value = "无法打开相机或文件选择器，未保存报告、未导入备份。请检查系统应用与剩余空间后重试。"
        revision.value++
        return pending.cameraUri
    }

    override fun onCleared() {
        request = null
        response = null
        backupSource.value = null
        busy.value = false
        abortMessage.value = null
        bound = false
        boundMemberId = null
        boundMemberUuid = null
    }

    private fun AttachmentImportTarget.member() = ExternalImportMember(memberId, memberUuid)

    companion object {
        private const val ABORT_MESSAGE = "选择操作已中止（页面状态丢失或成员已切换），未保存报告、未导入备份。请在确认当前成员后重新选择。"
    }
}
