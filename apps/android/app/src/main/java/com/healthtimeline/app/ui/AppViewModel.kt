package com.healthtimeline.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.healthtimeline.app.backup.PortableBackupService
import com.healthtimeline.app.data.*
import com.healthtimeline.app.reminders.AlarmScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import com.healthtimeline.shared.BackupImportPreview
import com.healthtimeline.shared.MergeChoice

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val repository: HealthRepository,
    private val scheduler: AlarmScheduler,
    private val backupService: PortableBackupService,
    private val memberSelection: MemberSelectionStore
) : ViewModel() {
    val members = repository.memberFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val selectedMemberIdMutable = MutableStateFlow(memberSelection.read())
    val selectedMemberId = selectedMemberIdMutable
    val selectedMember = combine(members, selectedMemberId) { values, id -> values.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val conditions = selectedMemberId.filterNotNull().flatMapLatest(repository::conditionsForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val records = selectedMemberId.filterNotNull().flatMapLatest(repository::recordsForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val attachments = selectedMemberId.filterNotNull().flatMapLatest(repository::attachmentsForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val followUps = selectedMemberId.filterNotNull().flatMapLatest(repository::followUpsForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val occurrences = selectedMemberId.filterNotNull().flatMapLatest(repository::occurrencesForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medications = selectedMemberId.filterNotNull().flatMapLatest(repository::medicationsForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medicationSchedules = selectedMemberId.filterNotNull().flatMapLatest(repository::medicationSchedulesForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val medicationLogs = selectedMemberId.filterNotNull().flatMapLatest(repository::medicationLogsForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val todayDoses = selectedMemberId.filterNotNull().flatMapLatest(repository::todayDosesForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    private val busyChannel = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy = busyChannel

    init {
        viewModelScope.launch {
            runCatching {
                repository.ensureDefaultMember()
                repository.cleanupOrphanedAttachmentSets()
                scheduler.rescheduleAll()
            }.onFailure { messages.send(it.userMessage("初始化检查失败")) }
        }
        viewModelScope.launch {
            repository.memberFlow.collect { values ->
                val active = values.filterNot { it.archived }
                val selected = selectedMemberIdMutable.value
                if (selected == null || active.none { it.id == selected }) {
                    active.firstOrNull()?.let { selectMember(it.id) }
                }
            }
        }
    }

    fun selectMember(memberId: Long) {
        if (members.value.any { it.id == memberId && !it.archived }) {
            memberSelection.write(memberId)
            selectedMemberIdMutable.value = memberId
        }
    }

    fun saveMember(value: FamilyMemberEntity, onSaved: () -> Unit = {}, onFailed: () -> Unit = {}) =
        launch("保存家庭成员", onFailed) {
            val id = repository.saveMember(value)
            if (value.id == 0L) selectMember(id)
            onSaved()
        }

    fun archiveMember(value: FamilyMemberEntity) = launch("归档家庭成员") {
        scheduler.cancelAllPersisted()
        try {
            repository.archiveMember(value.id)
        } finally {
            scheduler.rescheduleAll()
        }
    }

    fun restoreMember(value: FamilyMemberEntity) = launch("恢复家庭成员") {
        repository.restoreMember(value.id)
        scheduler.rescheduleAll()
    }

    fun deleteEmptyMember(value: FamilyMemberEntity) = launch("删除家庭成员") {
        repository.deleteEmptyMember(value.id)
    }

    fun saveCondition(value: ConditionEntity, onSaved: (Long) -> Unit = {}, onFailed: () -> Unit = {}) = launch("保存病情分类", onFailed) {
        onSaved(repository.saveCondition(value))
    }

    fun archiveCondition(id: Long) = launch("归档病情分类") { repository.archiveCondition(id) }

    fun saveRecord(
        value: ClinicalRecordEntity,
        conditionResolution: RecordConditionResolution = RecordConditionResolution.Selected,
        onSaved: (Long) -> Unit = {},
        onFailed: () -> Unit = {}
    ) = launch("保存病历", onFailed) {
        onSaved(repository.saveRecord(value, conditionResolution))
    }

    fun saveRecords(
        requests: List<ClinicalRecordSaveRequest>,
        onSaved: (List<Long>) -> Unit = {},
        onFailed: () -> Unit = {}
    ) = launch("批量保存病历", onFailed) {
        onSaved(repository.saveRecords(requests))
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

    fun previewBackup(
        uri: Uri,
        password: CharArray,
        onReady: (BackupImportPreview) -> Unit,
        onFailed: () -> Unit = {}
    ) = viewModelScope.launch {
        busyChannel.value = true
        messages.send("正在验证备份")
        try {
            backupService.previewImport(uri, password, selectedMemberId.value).fold(
                onSuccess = onReady,
                onFailure = { onFailed(); messages.send(it.userMessage("备份验证失败")) }
            )
        } finally { busyChannel.value = false }
    }

    fun mergeBackup(uri: Uri, password: CharArray, decisions: Map<String, MergeChoice>) =
        launchResult("正在安全合并", "备份已合并") {
            scheduler.cancelAllPersisted()
            scheduler.cancelAllNotifications()
            try {
                backupService.mergeImport(uri, password, decisions, selectedMemberId.value).getOrThrow()
            } finally {
                runCatching { scheduler.rescheduleAll() }
                    .onFailure { messages.send("数据状态已保持完整，但部分系统提醒安排失败；请重新打开应用") }
            }
        }

    fun importLegacyBackupAsNew(uri: Uri, password: CharArray) =
        launchResult("正在作为新资料导入", "旧版备份已作为新资料导入") {
            backupService.importLegacyAsNew(uri, password, selectedMemberId.value).getOrThrow()
            runCatching { scheduler.rescheduleAll() }
                .onFailure { messages.send("资料已导入，但部分系统提醒安排失败；请重新打开应用") }
        }

    fun restoreBackupWithSafetyExport(
        safetyDestination: Uri,
        source: Uri,
        password: CharArray
    ) = launchResult("正在导出安全备份并恢复", "安全备份已导出，数据已恢复") {
        try {
            backupService.export(safetyDestination, password.copyOf()).getOrThrow()
            scheduler.cancelAllPersisted()
            scheduler.cancelAllNotifications()
            try {
                backupService.replaceFromBackup(source, password, selectedMemberId.value).getOrThrow()
            } finally {
                runCatching { scheduler.rescheduleAll() }
                    .onFailure { messages.send("数据状态已保持完整，但部分系统提醒安排失败；请重新打开应用") }
            }
        } finally {
            password.fill('\u0000')
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
        private val backup: PortableBackupService,
        private val memberSelection: MemberSelectionStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(repository, scheduler, backup, memberSelection) as T
    }
}
