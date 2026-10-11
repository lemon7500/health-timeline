package com.healthtimeline.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import java.util.UUID

internal enum class BackupExportKind { EXPORT, SAFETY_BEFORE_REPLACE }

/** Passwords, import sources and full target requests survive recreation only in memory, never SavedState. */
internal class BackupExportFlowViewModel : ViewModel() {
    val registrySession = UUID.randomUUID().toString()
    private class Request(val kind: BackupExportKind, val memberId: Long, val password: CharArray, val source: String?, val memberUuid: String?) {
        var invalidated = false
    }
    private var request: Request? = null
    private var returnedDestination: String? = null
    private var hasResult = false
    val busy = mutableStateOf(false)
    val revision = mutableStateOf(0L)
    val abortedDestinations = mutableStateOf(listOf<String>())
    var abortMessage = mutableStateOf<String?>(null)
        private set

    sealed interface Completion {
        class Ready(val kind: BackupExportKind, val memberId: Long, val destination: String, val source: String?, val password: CharArray, val memberUuid: String? = null) : Completion
        class Aborted(val destination: String?, val message: String) : Completion
        data object Cancelled : Completion
    }

    fun begin(kind: BackupExportKind, memberId: Long?, password: CharArray, source: String? = null, memberUuid: String? = null): Boolean {
        if (busy.value || hasResult || memberId == null || (kind == BackupExportKind.SAFETY_BEFORE_REPLACE && source == null)) {
            password.fill('\u0000')
            return false
        }
        request = Request(kind, memberId, password, source, memberUuid)
        busy.value = true
        return true
    }

    fun receive(destination: String?) {
        returnedDestination = destination
        hasResult = true
        revision.value++
    }

    fun bindMember(memberId: Long?, memberUuid: String? = null, memberListLoaded: Boolean = false) {
        request?.let {
            if (!it.invalidated && (it.memberId != memberId ||
                (memberUuid != null && it.memberUuid != null && it.memberUuid != memberUuid) ||
                (memberListLoaded && memberUuid == null && it.memberUuid != null))) {
                it.invalidated = true
                revision.value++
            }
        }
    }

    fun acknowledgeAbort(message: String) {
        if (abortMessage.value == message) abortMessage.value = null
    }

    fun reportInterruptedProcess(destination: String? = null) {
        abortMessage.value = ABORT_MESSAGE
        queueAbortedDestination(destination)
    }

    fun acknowledgeAbortedDestination(destination: String) {
        abortedDestinations.value = abortedDestinations.value.filterNot { it == destination }
    }

    private fun queueAbortedDestination(destination: String?) {
        destination?.let { abortedDestinations.value = (abortedDestinations.value + it).distinct() }
    }

    fun takeCompletion(memberId: Long?, memberUuid: String? = null): Completion? {
        if (!hasResult) return null
        val pending = request
        if (pending != null && !pending.invalidated && pending.memberId == memberId && pending.memberUuid != null && memberUuid == null) return null
        val destination = returnedDestination
        request = null
        returnedDestination = null
        hasResult = false
        busy.value = false
        if (destination == null) {
            pending?.password?.fill('\u0000')
            return Completion.Cancelled
        }
        if (pending == null || pending.invalidated || pending.memberId != memberId || (pending.memberUuid != null && pending.memberUuid != memberUuid)) {
            pending?.password?.fill('\u0000')
            abortMessage.value = ABORT_MESSAGE
            queueAbortedDestination(destination)
            return Completion.Aborted(destination, ABORT_MESSAGE)
        }
        return Completion.Ready(pending.kind, pending.memberId, destination, pending.source, pending.password, pending.memberUuid)
    }

    fun cancelAfterLaunchFailure() {
        clearPassword()
        busy.value = false
    }

    private fun clearPassword() {
        request?.password?.fill('\u0000')
        request = null
    }

    override fun onCleared() {
        clearPassword()
        returnedDestination = null
        hasResult = false
        busy.value = false
        abortMessage.value = null
        abortedDestinations.value = emptyList()
    }

    companion object {
        private const val ABORT_MESSAGE = "备份流程已中止（页面状态丢失或成员已切换），未导出备份、未替换资料。请重新选择保存位置。若所选位置有空文件，可删除后重试。"
    }
}
