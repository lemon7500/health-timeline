package com.healthtimeline.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.healthtimeline.app.backup.BackupService
import com.healthtimeline.app.data.*
import com.healthtimeline.app.reminders.AlarmScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

class AppViewModel(
    private val repository: HealthRepository,
    private val scheduler: AlarmScheduler,
    private val backupService: BackupService
) : ViewModel() {
    val conditions = repository.conditionFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val records = repository.recordFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val attachments = repository.attachmentFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val followUps = repository.followUpFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val occurrences = repository.occurrenceFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medications = repository.medicationFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medicationSchedules = repository.medicationScheduleFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medicationLogs = repository.medicationLogFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val todayDoses = repository.todayDoses.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    private val busyChannel = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy = busyChannel

    init {
        viewModelScope.launch {
            runCatching {
                repository.cleanupOrphanedAttachmentSets()
                scheduler.rescheduleAll()
            }.onFailure { messages.send(it.userMessage("初始化检查失败")) }
        }
    }

    fun saveCondition(value: ConditionEntity, onSaved: (Long) -> Unit = {}, onFailed: () -> Unit = {}) = launch("保存病情分类", onFailed) {
        onSaved(repository.saveCondition(value))
    }

    fun archiveCondition(id: Long) = launch("归档病情分类") { repository.archiveCondition(id) }

    fun saveRecord(value: ClinicalRecordEntity, onSaved: (Long) -> Unit = {}, onFailed: () -> Unit = {}) = launch("保存病历", onFailed) {
        onSaved(repository.saveRecord(value))
    }

    fun deleteRecord(value: ClinicalRecordEntity) = launch("删除病历") { repository.deleteRecord(value) }

    fun importAttachments(recordId: Long, uris: List<Uri>) = viewModelScope.launch {
        busyChannel.value = true
        try {
            var imported = 0
            uris.forEach { uri ->
                repository.importAttachment(recordId, uri)
                    .onSuccess { imported++ }
                    .onFailure { messages.send(it.userMessage("导入附件失败")) }
            }
            if (imported > 0) messages.send("已导入 $imported 份报告")
        } finally { busyChannel.value = false }
    }

    fun deleteAttachment(value: AttachmentEntity) = launch("删除附件") { repository.deleteAttachment(value) }

    fun saveFollowUp(value: FollowUpScheduleEntity, onSaved: () -> Unit = {}, onFailed: () -> Unit = {}) = launch("保存复查计划", onFailed) {
        val id = repository.saveFollowUp(value)
        runCatching { scheduler.scheduleFollowUp(value.copy(id = id)) }
            .onFailure { messages.send("计划已保存，但系统提醒安排失败；请检查权限并重新打开应用") }
        onSaved()
    }

    fun deleteFollowUp(value: FollowUpScheduleEntity) = launch("删除复查计划") {
        scheduler.cancelFollowUp(value.id)
        repository.deleteFollowUp(value)
    }

    fun completeOccurrence(id: Long, skipped: Boolean = false) = launch("更新复查状态") {
        repository.completeOccurrence(id, skipped)?.let(scheduler::scheduleFollowUp)
    }

    fun saveMedication(value: MedicationEntity, times: List<LocalTime>, onSaved: () -> Unit = {}, onFailed: () -> Unit = {}) = launch("保存用药计划", onFailed) {
        val result = repository.saveMedication(value, times)
        result.replacedScheduleIds.forEach(scheduler::cancelMedication)
        runCatching { scheduler.rescheduleAll() }
            .onFailure { messages.send("用药计划已保存，但系统提醒安排失败；请检查权限并重新打开应用") }
        onSaved()
    }

    fun archiveMedication(id: Long) = launch("归档药物") {
        medicationSchedules.value.filter { it.medicationId == id }.forEach { scheduler.cancelMedication(it.id) }
        repository.archiveMedication(id)
        scheduler.rescheduleAll()
    }

    fun markDose(value: TodayDose, status: MedicationLogStatus) = launch("记录用药") {
        val inserted = repository.markDose(
            value.medication.id,
            value.schedule?.id,
            value.scheduledAt,
            status
        )
        if (!inserted) messages.send("这一剂已经记录过了")
    }

    fun exportBackup(uri: Uri, password: CharArray) = launchResult("正在导出备份", "备份已导出") {
        backupService.export(uri, password).getOrThrow()
    }

    fun restoreBackup(uri: Uri, password: CharArray) = launchResult("正在恢复备份", "备份已恢复") {
        scheduler.cancelAllPersisted()
        scheduler.cancelAllNotifications()
        try {
            backupService.restore(uri, password).getOrThrow()
        } finally {
            runCatching { scheduler.rescheduleAll() }
                .onFailure { messages.send("数据状态已保持完整，但部分系统提醒安排失败；请重新打开应用") }
        }
    }

    fun canScheduleExact() = scheduler.canScheduleExact()
    fun attachmentFile(value: AttachmentEntity) = repository.attachmentStore.file(value)

    private fun launch(label: String, onFailed: () -> Unit = {}, block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure {
            onFailed()
            messages.send(it.userMessage("$label 失败"))
        }
    }

    private fun launchResult(progress: String, success: String, block: suspend () -> Unit) = viewModelScope.launch {
        busyChannel.value = true
        messages.send(progress)
        try {
            runCatching { block() }
                .onSuccess { messages.send(success) }
                .onFailure { messages.send(it.userMessage("操作失败")) }
        } finally { busyChannel.value = false }
    }

    private fun Throwable.userMessage(fallback: String): String {
        val current = generateSequence(this) { it.cause }.mapNotNull { it.message }.firstOrNull { it.isNotBlank() }
        return current ?: fallback
    }

    class Factory(
        private val repository: HealthRepository,
        private val scheduler: AlarmScheduler,
        private val backup: BackupService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository, scheduler, backup) as T
    }
}
