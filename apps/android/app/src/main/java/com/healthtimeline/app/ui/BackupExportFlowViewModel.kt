package com.healthtimeline.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

internal enum class BackupExportKind { EXPORT, SAFETY_BEFORE_REPLACE }

/** Passwords and destination requests survive Activity recreation only in memory, never SavedState. */
internal class BackupExportFlowViewModel : ViewModel() {
    private class Request(val kind: BackupExportKind, val memberId: Long, val password: CharArray, val source: String?) {
        var invalidated = false
    }
    private var request: Request? = null
    private var returnedDestination: String? = null
    private var hasResult = false
    val busy = mutableStateOf(false)
    val revision = mutableStateOf(0L)
    var abortMessage = mutableStateOf<String?>(null)
        private set

    sealed interface Completion {
        class Ready(val kind: BackupExportKind, val memberId: Long, val destination: String, val source: String?, val password: CharArray) : Completion
        class Aborted(val destination: String?, val message: String) : Completion
        data object Cancelled : Completion
    }

    fun begin(kind: BackupExportKind, memberId: Long?, password: CharArray, source: String? = null): Boolean {
        if (busy.value || memberId == null || (kind == BackupExportKind.SAFETY_BEFORE_REPLACE && source == null)) {
            password.fill('\u0000')
            return false
        }
        request = Request(kind, memberId, password, source)
        busy.value = true
        return true
    }

    fun receive(destination: String?) {
        returnedDestination = destination
        hasResult = true
        revision.value++
    }

    fun bindMember(memberId: Long?) {
        request?.let { if (it.memberId != memberId) it.invalidated = true }
    }

    fun acknowledgeAbort(message: String) {
        if (abortMessage.value == message) abortMessage.value = null
    }

    fun takeCompletion(memberId: Long?): Completion? {
        if (!hasResult) return null
        val pending = request
        val destination = returnedDestination
        request = null
        returnedDestination = null
        hasResult = false
        busy.value = false
        if (destination == null) {
            pending?.password?.fill('\u0000')
            return Completion.Cancelled
        }
        if (pending == null || pending.invalidated || pending.memberId != memberId) {
            pending?.password?.fill('\u0000')
            val message = "备份流程已中止（页面状态丢失或成员已切换），未导出备份、未替换资料。请重新选择保存位置。若所选位置有空文件，可删除后重试。"
            abortMessage.value = message
            return Completion.Aborted(destination, message)
        }
        return Completion.Ready(pending.kind, pending.memberId, destination, pending.source, pending.password)
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
    }
}
