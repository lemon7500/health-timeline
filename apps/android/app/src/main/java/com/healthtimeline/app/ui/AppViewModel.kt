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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import com.healthtimeline.shared.BackupImportPreview
import com.healthtimeline.shared.MergeChoice

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val repository: HealthRepository,
    private val scheduler: AlarmScheduler,
    private val backupService: PortableBackupService,
    private val memberSelection: MemberSelectionStore,
    private val safetyCenter: SafetyCenterService
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
    val trashItems = selectedMemberId.filterNotNull().flatMapLatest(repository::trashForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val todayDoses = selectedMemberId.filterNotNull().flatMapLatest(repository::todayDosesForMember)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val medicationMonthMutable = MutableStateFlow(YearMonth.now())
    val medicationMonth = medicationMonthMutable
    val medicationMonthData = combine(selectedMemberId.filterNotNull(), medicationMonthMutable) { memberId, month -> memberId to month }
        .flatMapLatest { (memberId, month) -> repository.medicationMonthForMember(memberId, month) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            MedicationMonthData(YearMonth.now(), emptyMap())
        )

    private val messages = Channel<String>(Channel.BUFFERED)
    val messageFlow = messages.receiveAsFlow()
    private val busyChannel = kotlinx.coroutines.flow.MutableStateFlow(false)
    val busy = busyChannel
    val lastBackupAt = safetyCenter.lastBackupAt
    val lastIntegrityResult = safetyCenter.lastIntegrityResult

    init {
        viewModelScope.launch {
            runCatching {
                repository.ensureDefaultMember()
                val purged = repository.purgeExpiredTrash()
                if (purged > 0) messages.send("回收站已自动清理 $purged 项超过 30 天的内容")
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

    fun selectMedicationMonth(month: YearMonth) {
        medicationMonthMutable.value = month
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

    fun deleteRecord(value: ClinicalRecordEntity) = launch("将病历移入回收站") {
        repository.deleteRecord(value)
        messages.send("病历已移入回收站，可在 30 天内恢复")
    }

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

    fun deleteAttachment(value: AttachmentEntity, onDeleted: () -> Unit = {}) = launch("删除附件") {
        when (repository.deleteAttachment(value)) {
            AttachmentDeleteResult.MOVED_TO_TRASH -> {
                onDeleted()
                messages.send("检查报告已移入回收站，可在 30 天内恢复")
            }
            AttachmentDeleteResult.DELETED -> onDeleted()
            AttachmentDeleteResult.MISSING_FILE_RECORD_REMOVED -> {
                onDeleted()
                messages.send("原附件文件已不存在，已清除无效记录")
            }
            AttachmentDeleteResult.ALREADY_DELETED -> messages.send("该附件已经删除")
        }
    }

    fun saveFollowUp(value: FollowUpScheduleEntity, onSaved: () -> Unit = {}, onFailed: () -> Unit = {}) = launch("保存复查计划", onFailed) {
        val id = repository.saveFollowUp(value)
        runCatching { scheduler.scheduleFollowUp(value.copy(id = id)) }
            .onFailure { messages.send("计划已保存，但系统提醒安排失败；请检查权限并重新打开应用") }
        onSaved()
    }

    fun deleteFollowUp(value: FollowUpScheduleEntity) = launch("将复查计划移入回收站") {
        scheduler.cancelFollowUp(value.id)
        repository.deleteFollowUp(value)
        messages.send("复查计划已移入回收站，可在 30 天内恢复")
    }

    fun completeOccurrence(id: Long, skipped: Boolean = false) = launch("更新复查状态") {
        repository.completeOccurrence(id, skipped)?.let(scheduler::scheduleFollowUp)
    }

    fun saveMedication(
        value: MedicationEntity,
        times: List<LocalTime>,
        effectiveDate: LocalDate? = null,
        onSaved: () -> Unit = {},
        onFailed: () -> Unit = {}
    ) = launch("保存用药计划", onFailed) {
        val result = repository.saveMedication(value, times, effectiveDate)
        result.replacedScheduleIds.forEach(scheduler::cancelMedication)
        runCatching { scheduler.rescheduleAll() }
            .onFailure { messages.send("用药计划已保存，但系统提醒安排失败；请检查权限并重新打开应用") }
        onSaved()
    }

    fun archiveMedication(id: Long) = launch("结束疗程") {
        repository.archiveMedication(id)
        medicationSchedules.value.filter { it.medicationId == id }.forEach { scheduler.cancelMedication(it.id) }
        runCatching { scheduler.rescheduleAll() }
            .onFailure { messages.send("疗程已结束，但系统提醒刷新失败；请检查通知权限并重新打开应用") }
    }

    fun restoreMedication(id: Long) = launch("恢复疗程") {
        repository.restoreMedication(id)
        runCatching { scheduler.rescheduleAll() }
            .onFailure { messages.send("疗程已恢复，但系统提醒刷新失败；请检查通知权限并重新打开应用") }
    }

    fun deleteMedication(value: MedicationEntity) = launch("将药物移入回收站") {
        medicationSchedules.value.filter { it.medicationId == value.id }.forEach { scheduler.cancelMedication(it.id) }
        repository.deleteMedication(value.id)
        messages.send("药物及其历史记录已移入回收站，可在 30 天内恢复")
    }

    fun restoreTrash(item: TrashItem) = launch("恢复资料") {
        repository.restoreTrash(item)
        if (item.type == TrashItemType.FOLLOW_UP || item.type == TrashItemType.MEDICATION) {
            runCatching { scheduler.rescheduleAll() }
                .onFailure { messages.send("资料已恢复，但提醒安排失败；请检查权限并重新打开应用") }
        }
        messages.send("已从回收站恢复")
    }

    fun permanentlyDeleteTrash(item: TrashItem) = launch("永久删除") {
        val result = repository.permanentlyDeleteTrash(item)
        if (result == AttachmentDeleteResult.MISSING_FILE_RECORD_REMOVED) {
            messages.send("附件文件原已缺失，无效记录已永久删除")
        } else {
            messages.send("已永久删除，无法恢复")
        }
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

    fun recordDose(
        value: MedicationDayEntry,
        status: MedicationLogStatus,
        actualAt: LocalDateTime?
    ) = launch("记录用药") {
        val planned = value.plannedAt ?: requireNotNull(actualAt)
        if (!repository.recordDose(value.medication.id, value.schedule?.id, planned, actualAt, status)) {
            messages.send("这一剂已经记录过了")
        }
    }

    fun recordAsNeeded(medication: MedicationEntity, actualAt: LocalDateTime) = launch("补记用药") {
        if (!repository.recordDose(medication.id, null, actualAt, actualAt, MedicationLogStatus.TAKEN)) {
            messages.send("该药物在这个时间已经记录过了")
        }
    }

    fun correctDose(logId: Long, status: MedicationLogStatus, actualAt: LocalDateTime?) = launch("修正用药记录") {
        if (!repository.correctDose(logId, status, actualAt)) messages.send("这条用药记录已不存在")
    }

    fun canScheduleExact() = scheduler.canScheduleExact()
    fun safetySystemStatus() = safetyCenter.systemStatus()

    fun scheduleTestReminder() = launch("安排测试提醒") {
        require(safetyCenter.systemStatus().notificationsReady) { "请先允许应用通知，并开启复查和用药提醒类别" }
        val exact = scheduler.scheduleTestReminder()
        messages.send(
            if (exact) "测试提醒将在约 10 秒后出现"
            else "测试提醒已安排；精确闹钟未允许，系统可能延迟显示"
        )
    }

    fun runSafetyIntegrityCheck() = viewModelScope.launch {
        busyChannel.value = true
        messages.send("正在检查数据库和全部附件，请保持应用开启")
        try {
            runCatching { safetyCenter.runIntegrityCheck() }
                .onSuccess { result ->
                    messages.send(
                        if (result.healthy) "完整性检查通过"
                        else "检查发现 ${result.problemCount} 项异常，请先导出备份并保留诊断报告"
                    )
                }
                .onFailure { messages.send(it.userMessage("完整性检查失败")) }
        } finally {
            busyChannel.value = false
        }
    }

    fun exportSafetyDiagnostic(uri: Uri) = launchResult("正在生成诊断报告", "诊断报告已导出") {
        safetyCenter.exportDiagnostic(uri).getOrThrow()
    }

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
        private val memberSelection: MemberSelectionStore,
        private val safetyCenter: SafetyCenterService
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(repository, scheduler, backup, memberSelection, safetyCenter) as T
    }
}
