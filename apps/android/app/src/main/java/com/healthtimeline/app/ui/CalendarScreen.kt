package com.healthtimeline.app.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.healthtimeline.app.data.*
import com.healthtimeline.app.domain.AiRecordFormattingPromptBuilder
import com.healthtimeline.app.domain.ClinicalRecordQuickParser
import com.healthtimeline.app.domain.IssueSeverity
import com.healthtimeline.app.domain.ParsedClinicalRecordDraft
import com.healthtimeline.app.domain.QuickEntryIssue
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalendarScreen(
    viewModel: AppViewModel,
    padding: PaddingValues,
    navigation: TodayNavigation.AddRecord? = null,
    onNavigationConsumed: (String) -> Unit = {},
    importPickerBusy: Boolean = false,
    onImportAttachments: (ClinicalRecordEntity, String, Boolean) -> Unit
) {
    val cachedRecords by viewModel.records.collectAsStateWithLifecycle()
    val cachedConditions by viewModel.conditions.collectAsStateWithLifecycle()
    val cachedAttachments by viewModel.attachments.collectAsStateWithLifecycle()
    val cachedMembers by viewModel.members.collectAsStateWithLifecycle()
    val selectedMemberId by viewModel.selectedMemberId.collectAsStateWithLifecycle()
    val drafts: UiDraftStateViewModel = composeViewModel()
    val pageScope = "calendar/$selectedMemberId"
    val records = memberScopedEntities(selectedMemberId, cachedRecords) { it.memberId }
    val conditions = memberScopedEntities(selectedMemberId, cachedConditions) { it.memberId }
    val attachments = childrenOfMember(records.map { it.id }.toSet(), cachedAttachments) { it.recordId }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var filterCondition by rememberSaveable(selectedMemberId) { mutableStateOf<Long?>(null) }
    var editing by rememberUiDraftState<ClinicalRecordEntity?>(pageScope, "editing") { null }
    var createForMemberId by rememberUiDraftState<Long?>(pageScope, "creating") { null }
    var details by remember(selectedMemberId) { mutableStateOf<ClinicalRecordEntity?>(null) }
    var manageConditionsForMemberId by remember(selectedMemberId) { mutableStateOf<Long?>(null) }
    var deleteTarget by remember(selectedMemberId) { mutableStateOf<ClinicalRecordEntity?>(null) }
    var searchQuery by rememberSaveable(selectedMemberId) { mutableStateOf("") }

    val currentMonth = YearMonth.parse(month)
    val filtered = records.filter { filterCondition == null || it.conditionId == filterCondition }
    val byDate = filtered.groupBy { LocalDate.parse(it.recordDate) }
    val dayRecords = filtered.filter { it.recordDate == selectedDate }
    val searchResults = remember(records, conditions, searchQuery) {
        searchCalendarRecords(records, conditions, searchQuery)
    }

    LaunchedEffect(selectedMemberId) {
        filterCondition = null
        searchQuery = ""
        details = null
        manageConditionsForMemberId = null
        deleteTarget = null
    }
    LaunchedEffect(navigation?.token, selectedMemberId) {
        navigation?.let { request ->
            if (selectedMemberId == request.memberId) {
                month = YearMonth.from(request.date).toString()
                selectedDate = request.date.toString()
                createForMemberId = request.memberId
            }
            onNavigationConsumed(request.token)
        }
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = {
            FloatingActionButton(onClick = { selectedMemberId?.let { createForMemberId = it } }) { Icon(Icons.Outlined.Add, "新增病历") }
        }
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("病程日历", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            MemberSwitcher(viewModel)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it.take(100) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除搜索")
                        }
                    }
                },
                label = { Text("搜索病历") },
                placeholder = { Text("输入标题、诊断、用药等关键字") }
            )
            if (searchQuery.isNotBlank()) {
                CalendarSearchResults(
                    query = searchQuery,
                    results = searchResults,
                    conditions = conditions,
                    onLocate = { record ->
                        val date = LocalDate.parse(record.recordDate)
                        month = YearMonth.from(date).toString()
                        selectedDate = date.toString()
                        filterCondition = null
                        searchQuery = ""
                        details = record
                    }
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { month = currentMonth.minusMonths(1).toString() }) {
                    Icon(Icons.Outlined.ArrowBackIosNew, "上个月")
                }
                Text(currentMonth.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = { month = currentMonth.plusMonths(1).toString() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, "下个月")
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filterCondition == null, onClick = { filterCondition = null }, label = { Text("全部") })
                conditions.filter { !it.archived }.forEach { condition ->
                    FilterChip(
                        selected = filterCondition == condition.id,
                        onClick = { filterCondition = condition.id },
                        label = { Text(condition.name) }
                    )
                }
                AssistChip(onClick = { selectedMemberId?.let { manageConditionsForMemberId = it } }, label = { Text("管理分类") })
            }
            MonthGrid(
                month = currentMonth,
                records = byDate,
                selected = LocalDate.parse(selectedDate),
                onSelect = { selectedDate = it.toString() }
            )
            HorizontalDivider()
            Text("${LocalDate.parse(selectedDate).format(DateTimeFormatter.ofPattern("M 月 d 日"))}的记录", style = MaterialTheme.typography.titleMedium)
            if (dayRecords.isEmpty()) {
                Text("当天暂无病情记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                dayRecords.forEach { record ->
                    ElevatedCard(
                        Modifier.fillMaxWidth().clickable { details = record }
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(record.title, fontWeight = FontWeight.SemiBold)
                            Text(visitStageLabel(record.stage), style = MaterialTheme.typography.labelMedium)
                            val count = attachments.count { it.recordId == record.id }
                            if (count > 0) Text("$count 份检查报告", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(72.dp))
        }
    }

    val visibleEditing = editing?.takeIf { target -> target.memberId == selectedMemberId && records.any { it.id == target.id && it.uuid == target.uuid } }
    val visibleCreateForMember = createForMemberId?.takeIf { it == selectedMemberId }
    if (visibleCreateForMember != null || visibleEditing != null) {
        RecordEditorDialog(
            initialDate = selectedDate,
            record = visibleEditing,
            memberId = visibleEditing?.memberId ?: requireNotNull(visibleCreateForMember),
            conditions = conditions,
            onSave = { value, conditionResolution, onFailed ->
                if (viewModel.selectedMemberId.value != value.memberId) { onFailed() } else
                viewModel.saveRecord(
                    value = value,
                    conditionResolution = conditionResolution,
                    onSaved = {
                        drafts.clearScope(recordDraftScope(value.memberId, visibleEditing?.uuid))
                        createForMemberId = null; editing = null
                    },
                    onFailed = onFailed
                )
            },
            onSaveBatch = { requests, onFailed ->
                if (requests.any { it.record.memberId != viewModel.selectedMemberId.value }) { onFailed() } else
                viewModel.saveRecords(
                    requests = requests,
                    onSaved = {
                        drafts.clearScope(recordDraftScope(requireNotNull(visibleCreateForMember), null))
                        createForMemberId = null; editing = null
                    },
                    onFailed = onFailed
                )
            },
            onDismiss = {
                drafts.clearScope(recordDraftScope(visibleEditing?.memberId ?: requireNotNull(visibleCreateForMember), visibleEditing?.uuid))
                createForMemberId = null; editing = null
            }
        )
    }
    details?.takeIf { target -> target.memberId == selectedMemberId && records.any { it.id == target.id && it.uuid == target.uuid } }?.let { record ->
        val member = cachedMembers.firstOrNull { it.id == record.memberId && !it.archived } ?: return@let
        RecordDetailDialog(
            record = record,
            memberUuid = member.uuid,
            condition = conditions.firstOrNull { it.id == record.conditionId },
            attachments = attachments.filter { it.recordId == record.id },
            viewModel = viewModel,
            importPickerBusy = importPickerBusy,
            onImportAttachments = { frozenMemberUuid, fromCamera -> onImportAttachments(record, frozenMemberUuid, fromCamera) },
            onEdit = { editing = record; details = null },
            onDelete = { deleteTarget = record; details = null },
            onDismiss = { details = null }
        )
    }
    manageConditionsForMemberId?.takeIf { it == selectedMemberId }?.let { memberId ->
        ConditionManagerDialog(conditions, memberId, viewModel, onDismiss = { manageConditionsForMemberId = null })
    }
    deleteTarget?.takeIf { target -> target.memberId == selectedMemberId && records.any { it.id == target.id && it.uuid == target.uuid } }?.let { target ->
        ConfirmDialog(
            title = "将病历移入回收站？",
            message = "病历和它的全部检查报告会保留 30 天，期间可在设置的回收站中恢复。",
            onConfirm = { if (viewModel.selectedMemberId.value == target.memberId) viewModel.deleteRecord(target); deleteTarget = null },
            onDismiss = { deleteTarget = null }
        )
    }
}

@Composable
private fun CalendarSearchResults(
    query: String,
    results: List<ClinicalRecordEntity>,
    conditions: List<ConditionEntity>,
    onLocate: (ClinicalRecordEntity) -> Unit
) {
    val conditionNames = remember(conditions) { conditions.associate { it.id to it.name } }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                if (results.isEmpty()) "没有找到“$query”" else "找到 ${results.size} 条相关记录",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            results.take(20).forEachIndexed { index, record ->
                ListItem(
                    headlineContent = { Text(record.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val condition = conditionNames[record.conditionId]
                        Text(
                            buildString {
                                append(record.recordDate)
                                if (!condition.isNullOrBlank()) append(" · $condition")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = { Text("定位", color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { onLocate(record) }
                )
                if (index < minOf(results.size, 20) - 1) HorizontalDivider()
            }
            if (results.size > 20) {
                Text(
                    "仅显示前 20 条，请输入更具体的关键字",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    records: Map<LocalDate, List<ClinicalRecordEntity>>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
    Row(Modifier.fillMaxWidth()) {
        weekdays.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium) }
    }
    val leading = month.atDay(1).dayOfWeek.value - 1
    val slots = List(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    slots.chunked(7).forEach { week ->
        Row(Modifier.fillMaxWidth()) {
            (week + List(7 - week.size) { null }).forEach { date ->
                if (date == null) Spacer(Modifier.weight(1f).height(104.dp)) else {
                    val dayRecords = records[date].orEmpty()
                    val selectedColor = if (date == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    Column(
                        Modifier.weight(1f).height(104.dp).padding(1.dp)
                            .background(selectedColor, RoundedCornerShape(8.dp))
                            .clickable { onSelect(date) }.padding(3.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(date.dayOfMonth.toString(), fontWeight = if (date == LocalDate.now()) FontWeight.Bold else FontWeight.Normal)
                        dayRecords.take(2).forEachIndexed { index, record ->
                            val containerColor = if (index == 0) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.78f)
                            } else {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.78f)
                            }
                            val contentColor = if (index == 0) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                            Text(
                                text = record.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp)
                                    .background(containerColor, RoundedCornerShape(5.dp))
                                    .padding(horizontal = 2.dp, vertical = 1.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                color = contentColor
                            )
                        }
                        if (dayRecords.size > 2) Text("+${dayRecords.size - 2}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

internal data class EditableQuickDraft(
    val date: String,
    val title: String,
    val conditionName: String,
    val stage: String,
    val symptoms: String,
    val diagnosis: String,
    val treatment: String,
    val medicationNotes: String,
    val hospital: String,
    val clinician: String,
    val notes: String,
    val parserWarnings: List<QuickEntryIssue>,
    val unrecognizedSegments: List<String>
)

internal fun ParsedClinicalRecordDraft.toEditableQuickDraft() = EditableQuickDraft(
    date = recordDate?.toString().orEmpty(),
    title = title.orEmpty(),
    conditionName = conditionName.orEmpty(),
    stage = stage?.name ?: VisitStage.OTHER.name,
    symptoms = symptoms,
    diagnosis = diagnosis,
    treatment = treatment,
    medicationNotes = medicationNotes,
    hospital = hospital,
    clinician = clinician,
    notes = notes,
    parserWarnings = issues.filter { it.severity == IssueSeverity.WARNING },
    unrecognizedSegments = unrecognizedSegments
)

internal fun EditableQuickDraft.validationIssues(appendUnknownToNotes: Boolean): List<QuickEntryIssue> = buildList {
    if (runCatching { LocalDate.parse(date) }.getOrNull() == null) {
        add(QuickEntryIssue(IssueSeverity.ERROR, "日期", "INVALID_DATE", "日期格式不正确"))
    }
    if (title.isBlank()) add(QuickEntryIssue(IssueSeverity.ERROR, "小标题", "MISSING_TITLE", "请填写小标题"))
    val completedTitle = ClinicalRecordQuickParser.titleWithCondition(title, conditionName)
    if (completedTitle.length > 100) {
        add(QuickEntryIssue(IssueSeverity.ERROR, "小标题", "TITLE_TOO_LONG", "补上病名后不能超过 100 个字符"))
    }
    if (conditionName.length > 50) add(QuickEntryIssue(IssueSeverity.ERROR, "病情分类", "CONDITION_TOO_LONG", "不能超过 50 个字符"))
    if (hospital.length > 100) add(QuickEntryIssue(IssueSeverity.ERROR, "医院", "HOSPITAL_TOO_LONG", "不能超过 100 个字符"))
    if (clinician.length > 100) add(QuickEntryIssue(IssueSeverity.ERROR, "医生", "CLINICIAN_TOO_LONG", "不能超过 100 个字符"))
    val completedNotes = if (appendUnknownToNotes) {
        listOf(notes, unrecognizedSegments.joinToString("\n")).filter(String::isNotBlank).joinToString("\n")
    } else notes
    if (!hasSourceRecord(completedNotes)) {
        add(
            QuickEntryIssue(
                IssueSeverity.ERROR,
                "其他备注",
                "MISSING_SOURCE_RECORD",
                QUICK_SOURCE_REQUIRED_MESSAGE
            )
        )
    }
    listOf(
        "症状/病情" to symptoms,
        "诊断" to diagnosis,
        "治疗方案" to treatment,
        "就诊用药记录" to medicationNotes,
        "其他备注" to completedNotes
    ).forEach { (field, value) ->
        if (value.length > ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH) {
            add(QuickEntryIssue(IssueSeverity.ERROR, field, "FIELD_TOO_LONG", "不能超过 10,000 个字符"))
        }
    }
}

@Composable
internal fun RecordEditorDialog(
    initialDate: String,
    record: ClinicalRecordEntity?,
    memberId: Long,
    conditions: List<ConditionEntity>,
    onSave: (ClinicalRecordEntity, RecordConditionResolution, () -> Unit) -> Unit,
    onSaveBatch: (List<ClinicalRecordSaveRequest>, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val draftScope = recordDraftScope(memberId, record?.uuid)
    var date by rememberUiDraftState(draftScope, "date") { record?.recordDate ?: initialDate }
    var title by rememberUiDraftState(draftScope, "title") { record?.title.orEmpty() }
    var stage by rememberUiDraftState(draftScope, "stage") { record?.stage ?: VisitStage.OTHER.name }
    var conditionId by rememberUiDraftState(draftScope, "condition") { record?.conditionId }
    var symptoms by rememberUiDraftState(draftScope, "symptoms") { record?.symptoms.orEmpty() }
    var diagnosis by rememberUiDraftState(draftScope, "diagnosis") { record?.diagnosis.orEmpty() }
    var treatment by rememberUiDraftState(draftScope, "treatment") { record?.treatment.orEmpty() }
    var medicationNotes by rememberUiDraftState(draftScope, "medicationNotes") { record?.medicationNotes.orEmpty() }
    var hospital by rememberUiDraftState(draftScope, "hospital") { record?.hospital.orEmpty() }
    var clinician by rememberUiDraftState(draftScope, "clinician") { record?.clinician.orEmpty() }
    var notes by rememberUiDraftState(draftScope, "notes") { record?.notes.orEmpty() }
    var error by rememberUiDraftState<String?>(draftScope, "error") { null }
    var submitting by rememberUiDraftState(draftScope, "submitting") { false }
    var quickExpanded by rememberUiDraftState(draftScope, "quickExpanded") { record == null }
    var quickInput by rememberUiDraftState(draftScope, "quickInput") { "" }
    var quickIssues by rememberUiDraftState<List<QuickEntryIssue>>(draftScope, "quickIssues") { emptyList() }
    var unrecognizedSegments by rememberUiDraftState<List<String>>(draftScope, "unknown") { emptyList() }
    var quickApplied by rememberUiDraftState(draftScope, "quickApplied") { false }
    // Editing/clearing the input box must not bypass validation of an already parsed form.
    var requiresSourceRecord by rememberUiDraftState(draftScope, "requiresSourceRecord") { false }
    var aiHelpExpanded by rememberUiDraftState(draftScope, "aiHelpExpanded") { false }
    var aiCopyMessage by rememberUiDraftState<String?>(draftScope, "aiCopyMessage") { null }
    var batchDrafts by rememberUiDraftState<List<EditableQuickDraft>>(draftScope, "batchDrafts") { emptyList() }
    var expandedBatchIndex by rememberUiDraftState<Int?>(draftScope, "expandedBatchIndex") { null }
    var appendBatchUnknownToNotes by rememberUiDraftState(draftScope, "appendBatchUnknownToNotes") { false }
    var confirmBatchSave by rememberUiDraftState(draftScope, "confirmBatchSave") { false }
    var sourceTargetIndex by rememberUiDraftState<Int?>(draftScope, "sourceTargetIndex") { null }
    var sourceOriginalText by rememberUiDraftState(draftScope, "sourceOriginalText") { "" }
    var sourceError by rememberUiDraftState<String?>(draftScope, "sourceError") { null }
    var conditionResolution by rememberUiDraftState<RecordConditionResolution>(draftScope, "conditionResolution") { RecordConditionResolution.Selected }
    var pendingNewCondition by rememberUiDraftState<String?>(draftScope, "pendingNewCondition") { null }
    var pendingArchivedCondition by rememberUiDraftState<ConditionEntity?>(draftScope, "pendingArchivedCondition") { null }
    val now = Instant.now().toString()
    val context = LocalContext.current
    val selectableConditions = conditions.filter { !it.archived || it.id == record?.conditionId }
    val closeSourceDialog: () -> Unit = {
        sourceTargetIndex = null
        sourceOriginalText = ""
        sourceError = null
    }
    val openSourceDialog: (Int, String) -> Unit = { index, currentNotes ->
        sourceOriginalText = initialSourceRecordText(currentNotes)
        sourceError = null
        sourceTargetIndex = index
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (record == null) "新增病情记录" else "编辑病情记录") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (record == null) {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { quickExpanded = !quickExpanded },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("快速录入", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "简单内容可离线解析；复杂长文建议先用 AI 整理成模板，保存前必须核对",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(if (quickExpanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
                            }
                            if (quickExpanded) {
                                OutlinedTextField(
                                    value = quickInput,
                                    onValueChange = {
                                        closeSourceDialog()
                                        quickInput = it
                                        quickApplied = false
                                        batchDrafts = emptyList()
                                        expandedBatchIndex = null
                                    },
                                    label = { Text("病历文字（最多 50,000 字）") },
                                    minLines = 5,
                                    maxLines = 10,
                                    supportingText = { Text("${quickInput.length} / ${ClinicalRecordQuickParser.MAX_INPUT_LENGTH}") },
                                    isError = quickInput.length > ClinicalRecordQuickParser.MAX_INPUT_LENGTH,
                                    modifier = Modifier.fillMaxWidth().testTag("quick-entry-input")
                                )
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp)
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().clickable { aiHelpExpanded = !aiHelpExpanded },
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text("AI 辅助整理（可选）", fontWeight = FontWeight.SemiBold)
                                                Text(
                                                    "只复制提示词和空模板，不会复制上方病历原文",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(if (aiHelpExpanded) "收起" else "查看", color = MaterialTheme.colorScheme.primary)
                                        }
                                        Text(
                                            "使用外部 AI 前，请先删除姓名、身份证号、电话、住址等敏感信息。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        if (aiHelpExpanded) {
                                            Text(
                                                "1. 复制提示词并粘贴到自行选择的 AI。\n" +
                                                    "2. 将脱敏后的病程文字粘贴在提示词末尾。\n" +
                                                    "3. 复制 AI 返回的模板内容。\n" +
                                                    "4. 返回本页粘贴、解析，并逐条展开核对。",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            val defaultYear = runCatching { LocalDate.parse(date).year }
                                                .getOrDefault(LocalDate.now().year)
                                            val aiPrompt = AiRecordFormattingPromptBuilder.build(defaultYear)
                                            Button(
                                                onClick = {
                                                    val copied = runCatching {
                                                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                                                        requireNotNull(clipboard).setPrimaryClip(
                                                            ClipData.newPlainText("病程日历 AI 整理提示词", aiPrompt)
                                                        )
                                                    }.isSuccess
                                                    aiCopyMessage = if (copied) {
                                                        "提示词已复制，未复制病历原文"
                                                    } else {
                                                        "复制失败，请重试"
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) { Text("复制 AI 整理提示词") }
                                            aiCopyMessage?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (it.startsWith("提示词已复制")) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.error
                                                    }
                                                )
                                            }
                                            Text("提示词预览", style = MaterialTheme.typography.labelMedium)
                                            Text(
                                                aiPrompt,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(8.dp)
                                            )
                                            Text(
                                                "外部 AI 的数据处理和隐私规则不受病程日历控制。",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                            val text = clipboard?.primaryClip
                                                ?.takeIf { it.itemCount > 0 }
                                                ?.getItemAt(0)
                                                ?.coerceToText(context)
                                                ?.toString()
                                                .orEmpty()
                                            if (text.isBlank()) {
                                                quickIssues = listOf(
                                                    QuickEntryIssue(
                                                        IssueSeverity.ERROR,
                                                        null,
                                                        "EMPTY_CLIPBOARD",
                                                        "剪贴板中没有可粘贴的文字"
                                                    )
                                                )
                                            } else {
                                                closeSourceDialog()
                                                quickInput = text
                                                quickIssues = emptyList()
                                                unrecognizedSegments = emptyList()
                                                quickApplied = false
                                                batchDrafts = emptyList()
                                                expandedBatchIndex = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("从剪贴板粘贴") }
                                    OutlinedButton(
                                        onClick = {
                                            closeSourceDialog()
                                            val templateDate = runCatching { LocalDate.parse(date) }
                                                .getOrDefault(LocalDate.now())
                                            quickInput = ClinicalRecordQuickParser.templateFor(templateDate)
                                            quickIssues = emptyList()
                                            unrecognizedSegments = emptyList()
                                            quickApplied = false
                                            batchDrafts = emptyList()
                                            expandedBatchIndex = null
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("插入模板") }
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            closeSourceDialog()
                                            val referenceDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                                            val batch = ClinicalRecordQuickParser.parseMany(quickInput, referenceDate)
                                            val draft = batch.records.singleOrNull()
                                            quickIssues = batch.issues + (draft?.issues ?: emptyList())
                                            unrecognizedSegments = draft?.unrecognizedSegments.orEmpty()
                                            quickApplied = false
                                            batchDrafts = emptyList()
                                            expandedBatchIndex = null
                                            appendBatchUnknownToNotes = false
                                            pendingNewCondition = null
                                            pendingArchivedCondition = null
                                            if (batch.records.size > 1) {
                                                batchDrafts = batch.records.map { it.toEditableQuickDraft() }
                                                expandedBatchIndex = 0
                                            } else if (draft != null && !batch.hasErrors) {
                                                date = requireNotNull(draft.recordDate).toString()
                                                title = requireNotNull(draft.title).trim()
                                                stage = draft.stage?.name ?: VisitStage.OTHER.name
                                                symptoms = draft.symptoms
                                                diagnosis = draft.diagnosis
                                                treatment = draft.treatment
                                                medicationNotes = draft.medicationNotes
                                                hospital = draft.hospital
                                                clinician = draft.clinician
                                                notes = draft.notes
                                                conditionResolution = RecordConditionResolution.Selected
                                                val requestedCondition = draft.conditionName?.trim().orEmpty()
                                                val matched = requestedCondition.takeIf { it.isNotBlank() }?.let { requested ->
                                                    val normalized = ClinicalRecordQuickParser.normalizeConditionName(requested)
                                                    conditions.firstOrNull {
                                                        ClinicalRecordQuickParser.normalizeConditionName(it.name) == normalized
                                                    }
                                                }
                                                when {
                                                    requestedCondition.isBlank() -> conditionId = null
                                                    matched == null -> {
                                                        conditionId = null
                                                        pendingNewCondition = requestedCondition
                                                    }
                                                    matched.archived -> {
                                                        conditionId = null
                                                        pendingArchivedCondition = matched
                                                    }
                                                    else -> conditionId = matched.id
                                                }
                                                quickApplied = true
                                                requiresSourceRecord = true
                                                error = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f).testTag("quick-entry-parse")
                                    ) { Text("解析并填入") }
                                    TextButton(
                                        onClick = {
                                            closeSourceDialog()
                                            quickInput = ""
                                            quickIssues = emptyList()
                                            unrecognizedSegments = emptyList()
                                            quickApplied = false
                                            batchDrafts = emptyList()
                                            expandedBatchIndex = null
                                            appendBatchUnknownToNotes = false
                                            pendingNewCondition = null
                                            pendingArchivedCondition = null
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("清空") }
                                }
                                quickIssues.forEach { issue ->
                                    Text(
                                        text = buildString {
                                            if (!issue.field.isNullOrBlank()) append("${issue.field}：")
                                            append(issue.message)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (issue.severity == IssueSeverity.ERROR) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        }
                                    )
                                }
                                if (unrecognizedSegments.isNotEmpty()) {
                                    Text("未识别内容：", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        unrecognizedSegments.joinToString("\n"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(onClick = {
                                        val addition = unrecognizedSegments.joinToString("\n")
                                        val combined = listOf(notes, addition).filter { it.isNotBlank() }.joinToString("\n")
                                        if (combined.length > ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH) {
                                            quickIssues = quickIssues + QuickEntryIssue(
                                                IssueSeverity.ERROR,
                                                "其他备注",
                                                "NOTES_TOO_LONG",
                                                "追加后其他备注将超过 10,000 个字符"
                                            )
                                        } else {
                                            notes = combined
                                            unrecognizedSegments = emptyList()
                                            quickIssues = quickIssues.filterNot { it.code == "UNRECOGNIZED_CONTENT" }
                                        }
                                    }) { Text("追加到其他备注") }
                                }
                                if (quickApplied) {
                                    Text(
                                        "已填入表单，请逐项核对；当前尚未保存。",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (batchDrafts.isNotEmpty()) {
                                    val batchHasErrors = batchDrafts.any {
                                        it.validationIssues(appendBatchUnknownToNotes).any { issue -> issue.severity == IssueSeverity.ERROR }
                                    } || quickIssues.any { it.severity == IssueSeverity.ERROR }
                                    Text(
                                        "已识别 ${batchDrafts.size} 条记录。请逐条展开、修改并核对，确认前不会保存。",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    batchDrafts.forEachIndexed { index, draft ->
                                        val expanded = expandedBatchIndex == index
                                        val draftIssues = draft.parserWarnings + draft.validationIssues(appendBatchUnknownToNotes)
                                        val updateDraft: ((EditableQuickDraft) -> EditableQuickDraft) -> Unit = { transform ->
                                            batchDrafts = batchDrafts.mapIndexed { itemIndex, item ->
                                                if (itemIndex == index) transform(item) else item
                                            }
                                        }
                                        OutlinedCard(Modifier.fillMaxWidth()) {
                                            Column(
                                                Modifier.fillMaxWidth().padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(7.dp)
                                            ) {
                                                Row(
                                                    Modifier.fillMaxWidth().clickable {
                                                        expandedBatchIndex = if (expanded) null else index
                                                    },
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "${index + 1}. ${draft.date.ifBlank { "日期有误" }} · ${draft.title.ifBlank { "标题未填" }}",
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Text(if (expanded) "收起" else "展开核对", color = MaterialTheme.colorScheme.primary)
                                                }
                                                if (!expanded) {
                                                    draft.conditionName.takeIf { it.isNotBlank() }?.let { Text("分类：$it") }
                                                    draft.diagnosis.takeIf { it.isNotBlank() }?.let {
                                                        Text("诊断：$it", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    }
                                                } else {
                                                    OutlinedTextField(
                                                        draft.date,
                                                        { value -> updateDraft { it.copy(date = value) } },
                                                        label = { Text("日期（YYYY-MM-DD）*") },
                                                        singleLine = true,
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    OutlinedTextField(
                                                        draft.title,
                                                        { value -> updateDraft { it.copy(title = value) } },
                                                        label = { Text("小标题 *") },
                                                        supportingText = { Text("保存时会自动补上病情分类名称") },
                                                        singleLine = true,
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    OutlinedTextField(
                                                        draft.conditionName,
                                                        { value -> updateDraft { it.copy(conditionName = value) } },
                                                        label = { Text("病情分类") },
                                                        singleLine = true,
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    LabeledDropdown(
                                                        "记录类型",
                                                        draft.stage,
                                                        VisitStage.entries.map { it.name to visitStageLabel(it.name) },
                                                        { value -> updateDraft { it.copy(stage = value) } }
                                                    )
                                                    MultiField("症状/病情", draft.symptoms) { value ->
                                                        updateDraft { it.copy(symptoms = value) }
                                                    }
                                                    MultiField("诊断", draft.diagnosis) { value ->
                                                        updateDraft { it.copy(diagnosis = value) }
                                                    }
                                                    MultiField("治疗方案", draft.treatment) { value ->
                                                        updateDraft { it.copy(treatment = value) }
                                                    }
                                                    MultiField("就诊用药记录", draft.medicationNotes) { value ->
                                                        updateDraft { it.copy(medicationNotes = value) }
                                                    }
                                                    OutlinedTextField(
                                                        draft.hospital,
                                                        { value -> updateDraft { it.copy(hospital = value) } },
                                                        label = { Text("医院") },
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    OutlinedTextField(
                                                        draft.clinician,
                                                        { value -> updateDraft { it.copy(clinician = value) } },
                                                        label = { Text("医生") },
                                                        modifier = Modifier.fillMaxWidth()
                                                    )
                                                    MultiField("其他备注（包含自然叙述原文）", draft.notes) { value ->
                                                        updateDraft { it.copy(notes = value) }
                                                    }
                                                    if (draft.unrecognizedSegments.isNotEmpty()) {
                                                        Text(
                                                            "未识别：${draft.unrecognizedSegments.joinToString("；")}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.tertiary
                                                        )
                                                    }
                                                    TextButton(onClick = {
                                                        closeSourceDialog()
                                                        batchDrafts = batchDrafts.filterIndexed { itemIndex, _ -> itemIndex != index }
                                                        expandedBatchIndex = null
                                                    }) { Text("不保存此条") }
                                                }
                                                draftIssues.forEach { issue ->
                                                    Text(
                                                        buildString {
                                                            if (!issue.field.isNullOrBlank()) append("${issue.field}：")
                                                            append(issue.message)
                                                        },
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (issue.severity == IssueSeverity.ERROR) {
                                                            MaterialTheme.colorScheme.error
                                                        } else {
                                                            MaterialTheme.colorScheme.tertiary
                                                        }
                                                    )
                                                }
                                                if (draftIssues.any { it.code == "MISSING_SOURCE_RECORD" }) {
                                                    TextButton(
                                                        onClick = { openSourceDialog(index, draft.notes) },
                                                        enabled = !submitting,
                                                        modifier = Modifier.testTag("quick-source-repair-$index")
                                                    ) { Text("补充原文") }
                                                }
                                            }
                                        }
                                    }
                                    if (batchDrafts.any { it.unrecognizedSegments.isNotEmpty() }) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = appendBatchUnknownToNotes,
                                                onCheckedChange = { appendBatchUnknownToNotes = it }
                                            )
                                            Text("把每条未识别内容追加到该条的其他备注")
                                        }
                                    }
                                    Button(
                                        onClick = { confirmBatchSave = true },
                                        enabled = !batchHasErrors && !submitting,
                                        modifier = Modifier.fillMaxWidth().testTag("quick-batch-save")
                                    ) { Text("核对并保存 ${batchDrafts.size} 条记录") }
                                    if (batchHasErrors) {
                                        Text(
                                            "缺少原文时，点击该条记录下的“补充原文”；其他红色提示请展开修改。不需要重新解析整段文字。",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(date, { date = it.take(10) }, label = { Text("日期（YYYY-MM-DD）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    title,
                    { title = it },
                    label = { Text("小标题 *") },
                    supportingText = { Text("${title.length} / 100；保存时会把病情分类名称放在标题第一位") },
                    isError = title.length > 100,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LabeledDropdown(
                    "病情分类",
                    conditionId?.toString().orEmpty(),
                    listOf("" to "未分类") + selectableConditions.map { it.id.toString() to it.name },
                    {
                        conditionId = it.toLongOrNull()
                        conditionResolution = RecordConditionResolution.Selected
                        pendingNewCondition = null
                        pendingArchivedCondition = null
                    }
                )
                when (val resolution = conditionResolution) {
                    is RecordConditionResolution.Create -> Text(
                        "保存时将新建分类：${resolution.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    is RecordConditionResolution.Restore -> Text(
                        "保存时将恢复分类：${conditions.firstOrNull { it.id == resolution.conditionId }?.name.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    RecordConditionResolution.Selected -> Unit
                }
                LabeledDropdown(
                    "记录类型", stage,
                    VisitStage.entries.map { it.name to visitStageLabel(it.name) },
                    { stage = it }
                )
                MultiField("症状/病情", symptoms) { symptoms = it }
                MultiField("诊断", diagnosis) { diagnosis = it }
                MultiField("治疗方案", treatment) { treatment = it }
                MultiField("就诊用药记录", medicationNotes) { medicationNotes = it }
                OutlinedTextField(
                    hospital,
                    { hospital = it },
                    label = { Text("医院") },
                    supportingText = { Text("${hospital.length} / 100") },
                    isError = hospital.length > 100,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    clinician,
                    { clinician = it },
                    label = { Text("医生") },
                    supportingText = { Text("${clinician.length} / 100") },
                    isError = clinician.length > 100,
                    modifier = Modifier.fillMaxWidth()
                )
                MultiField("其他备注", notes) { notes = it }
                if (requiresSourceRecord && batchDrafts.isEmpty() && !hasSourceRecord(notes)) {
                    Text(QUICK_SOURCE_REQUIRED_MESSAGE, color = MaterialTheme.colorScheme.error)
                    TextButton(
                        onClick = { openSourceDialog(-1, notes) },
                        enabled = !submitting,
                        modifier = Modifier.testTag("quick-source-repair-single")
                    ) { Text("补充原文") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = save@{
                if (submitting) return@save
                val validDate = runCatching { LocalDate.parse(date) }.getOrNull()
                val titleConditionName = when (val resolution = conditionResolution) {
                    is RecordConditionResolution.Create -> resolution.name
                    is RecordConditionResolution.Restore -> conditions.firstOrNull { it.id == resolution.conditionId }?.name
                    RecordConditionResolution.Selected -> conditions.firstOrNull { it.id == conditionId }?.name
                }
                val completedTitle = ClinicalRecordQuickParser.titleWithCondition(title, titleConditionName)
                when {
                    title.isBlank() -> error = "请填写小标题"
                    completedTitle.length > 100 -> error = "补上病名后小标题超过 100 个字符，请缩短"
                    validDate == null -> error = "日期格式不正确"
                    hospital.length > 100 -> error = "医院不能超过 100 个字符"
                    clinician.length > 100 -> error = "医生不能超过 100 个字符"
                    requiresSourceRecord && !hasSourceRecord(notes) -> {
                        error = QUICK_SOURCE_REQUIRED_MESSAGE
                        openSourceDialog(-1, notes)
                    }
                    listOf(symptoms, diagnosis, treatment, medicationNotes, notes).any {
                        it.length > ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH
                    } -> error = "症状、诊断、治疗、用药和备注每项不能超过 10,000 个字符"
                    else -> {
                        submitting = true
                        onSave(
                            ClinicalRecordEntity(
                            id = record?.id ?: 0,
                            conditionId = conditionId,
                            recordDate = validDate.toString(),
                            title = completedTitle,
                            stage = stage,
                            symptoms = symptoms.trim(), diagnosis = diagnosis.trim(), treatment = treatment.trim(),
                            medicationNotes = medicationNotes.trim(), hospital = hospital.trim(), clinician = clinician.trim(), notes = notes,
                                createdAt = record?.createdAt ?: now,
                                updatedAt = now,
                                uuid = record?.uuid ?: java.util.UUID.randomUUID().toString(),
                                memberId = memberId,
                                dayOrder = record?.dayOrder ?: 0L
                            ),
                            conditionResolution
                        ) { submitting = false }
                    }
                }
            }, enabled = !submitting && batchDrafts.isEmpty(), modifier = Modifier.testTag("record-save")) {
                Text(
                    when {
                        submitting -> "保存中…"
                        batchDrafts.isNotEmpty() -> "请在上方批量保存"
                        else -> "保存"
                    }
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } }
    )

    sourceTargetIndex?.let { targetIndex ->
        val targetDraft = batchDrafts.getOrNull(targetIndex)
        if ((targetIndex == -1 && batchDrafts.isEmpty()) || targetDraft != null) {
            QuickSourceRecordDialog(
                eventLabel = if (targetIndex == -1) "$date · $title" else "${targetIndex + 1}. ${targetDraft!!.date} · ${targetDraft.title}",
                originalText = sourceOriginalText,
                error = sourceError,
                onTextChange = { sourceOriginalText = it; sourceError = null },
                onError = { sourceError = it },
                onConfirm = {
                    val currentNotes = if (targetIndex == -1) notes else batchDrafts.getOrNull(targetIndex)?.notes
                    if (currentNotes != null && !submitting) {
                        runCatching { completeSourceRecordNotes(currentNotes, sourceOriginalText) }
                            .onSuccess { completed ->
                                if (targetIndex == -1) notes = completed
                                else batchDrafts = batchDrafts.mapIndexed { index, item ->
                                    if (index == targetIndex) item.copy(notes = completed) else item
                                }
                                error = null
                                closeSourceDialog()
                            }
                            .onFailure { sourceError = it.message ?: "补充失败，请保留原文后重试。" }
                    }
                },
                onDismiss = closeSourceDialog
            )
        }
    }

    pendingNewCondition?.let { name ->
        AlertDialog(
            onDismissRequest = {
                pendingNewCondition = null
                conditionResolution = RecordConditionResolution.Selected
            },
            title = { Text("新建病情分类？") },
            text = { Text("当前成员没有“$name”分类。确认后会在保存病历时一并新建；取消则按未分类保存。") },
            confirmButton = {
                TextButton(onClick = {
                    conditionResolution = RecordConditionResolution.Create(name)
                    pendingNewCondition = null
                }) { Text("保存时新建并使用") }
            },
            dismissButton = {
                TextButton(onClick = {
                    conditionId = null
                    conditionResolution = RecordConditionResolution.Selected
                    pendingNewCondition = null
                }) { Text("不分类") }
            }
        )
    }

    pendingArchivedCondition?.let { archivedCondition ->
        AlertDialog(
            onDismissRequest = {
                pendingArchivedCondition = null
                conditionResolution = RecordConditionResolution.Selected
            },
            title = { Text("分类已归档") },
            text = { Text("“${archivedCondition.name}”已经归档。可以在保存病历时恢复并使用，或将本条病历保存为未分类。") },
            confirmButton = {
                TextButton(onClick = {
                    conditionId = archivedCondition.id
                    conditionResolution = RecordConditionResolution.Restore(archivedCondition.id)
                    pendingArchivedCondition = null
                }) { Text("恢复并使用") }
            },
            dismissButton = {
                TextButton(onClick = {
                    conditionId = null
                    conditionResolution = RecordConditionResolution.Selected
                    pendingArchivedCondition = null
                }) { Text("不分类") }
            }
        )
    }

    if (confirmBatchSave && batchDrafts.isNotEmpty()) {
        val requests = prepareQuickBatchRequests(
            drafts = batchDrafts,
            conditions = conditions,
            memberId = memberId,
            appendUnknownToNotes = appendBatchUnknownToNotes,
            timestamp = now
        )
        val createNames = requests.mapNotNull {
            (it.conditionResolution as? RecordConditionResolution.Create)?.name
        }.distinct()
        val restoreNames = requests.mapNotNull { request ->
            (request.conditionResolution as? RecordConditionResolution.Restore)?.conditionId?.let { id ->
                conditions.firstOrNull { it.id == id }?.name
            }
        }.distinct()
        AlertDialog(
            onDismissRequest = { if (!submitting) confirmBatchSave = false },
            title = { Text("确认批量保存？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("将为当前成员一次保存 ${requests.size} 条病历。全部成功才会写入；任意一条失败会整体回滚。")
                    if (createNames.isNotEmpty()) Text("同时新建分类：${createNames.joinToString("、")}")
                    if (restoreNames.isNotEmpty()) Text("同时恢复分类：${restoreNames.joinToString("、")}")
                    Text("保存后仍可逐条编辑。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!submitting && !quickIssues.any { it.severity == IssueSeverity.ERROR } &&
                            batchDrafts.none { draft -> draft.validationIssues(appendBatchUnknownToNotes).any { it.severity == IssueSeverity.ERROR } }) {
                            submitting = true
                            onSaveBatch(requests) {
                                submitting = false
                                confirmBatchSave = false
                            }
                        }
                    },
                    enabled = !submitting,
                    modifier = Modifier.testTag("quick-batch-confirm")
                ) { Text(if (submitting) "保存中…" else "确认保存") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBatchSave = false }, enabled = !submitting) { Text("返回核对") }
            }
        )
    }
}

internal fun prepareQuickBatchRequests(
    drafts: List<EditableQuickDraft>,
    conditions: List<ConditionEntity>,
    memberId: Long,
    appendUnknownToNotes: Boolean,
    timestamp: String
): List<ClinicalRecordSaveRequest> = drafts.map { draft ->
    val conditionName = draft.conditionName.trim()
    val matched = conditionName.takeIf { it.isNotBlank() }?.let { requested ->
        val normalized = ClinicalRecordQuickParser.normalizeConditionName(requested)
        conditions.firstOrNull { ClinicalRecordQuickParser.normalizeConditionName(it.name) == normalized }
    }
    val resolution = when {
        conditionName.isBlank() -> RecordConditionResolution.Selected
        matched == null -> RecordConditionResolution.Create(conditionName)
        matched.archived -> RecordConditionResolution.Restore(matched.id)
        else -> RecordConditionResolution.Selected
    }
    val notes = if (appendUnknownToNotes) {
        listOf(draft.notes, draft.unrecognizedSegments.joinToString("\n"))
            .filter { it.isNotBlank() }
            .joinToString("\n")
    } else {
        draft.notes
    }
    ClinicalRecordSaveRequest(
        record = ClinicalRecordEntity(
            conditionId = matched?.id,
            recordDate = LocalDate.parse(draft.date).toString(),
            title = ClinicalRecordQuickParser.titleWithCondition(draft.title, conditionName),
            stage = draft.stage,
            symptoms = draft.symptoms.trim(),
            diagnosis = draft.diagnosis.trim(),
            treatment = draft.treatment.trim(),
            medicationNotes = draft.medicationNotes.trim(),
            hospital = draft.hospital.trim(),
            clinician = draft.clinician.trim(),
            notes = notes,
            createdAt = timestamp,
            updatedAt = timestamp,
            memberId = memberId
        ),
        conditionResolution = resolution
    )
}

@Composable
private fun MultiField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        supportingText = { Text("${value.length} / ${ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH}") },
        isError = value.length > ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH,
        minLines = 2,
        maxLines = 5,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RecordDetailDialog(
    record: ClinicalRecordEntity,
    memberUuid: String,
    condition: ConditionEntity?,
    attachments: List<AttachmentEntity>,
    viewModel: AppViewModel,
    importPickerBusy: Boolean,
    onImportAttachments: (String, Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    // Freeze the member's permanent identity when opening, not after a picker result or restore.
    val importMemberUuid = remember(record.uuid) { memberUuid }
    var viewer by remember(record.uuid) { mutableStateOf<AttachmentEntity?>(null) }
    var attachmentDeleteTarget by remember(record.uuid) { mutableStateOf<AttachmentEntity?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.title) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${record.recordDate} · ${visitStageLabel(record.stage)}")
                condition?.let { Text("分类：${it.name}", color = MaterialTheme.colorScheme.primary) }
                DetailLine("病情", record.symptoms); DetailLine("诊断", record.diagnosis); DetailLine("治疗", record.treatment)
                DetailLine("用药", record.medicationNotes); DetailLine("医院", record.hospital); DetailLine("医生", record.clinician); DetailLine("备注", record.notes)
                HorizontalDivider()
                Text("检查报告（${attachments.size}）", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !importPickerBusy, onClick = { onImportAttachments(importMemberUuid, false) }) {
                        Icon(Icons.Outlined.AttachFile, null); Text("选择文件")
                    }
                    OutlinedButton(enabled = !importPickerBusy, onClick = { onImportAttachments(importMemberUuid, true) }) {
                        Icon(Icons.Outlined.CameraAlt, null); Text("拍照")
                    }
                }
                attachments.forEach { attachment ->
                    ListItem(
                        headlineContent = { Text(attachment.displayName, maxLines = 1) },
                        supportingContent = { Text(if (attachment.kind == AttachmentKind.PDF.name) "PDF" else "图片") },
                        trailingContent = {
                            IconButton(onClick = { attachmentDeleteTarget = attachment }) {
                                Icon(Icons.Outlined.Delete, "删除${attachment.displayName}", tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.clickable { viewer = attachment }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onEdit) { Text("编辑") } },
        dismissButton = {
            Row { TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("关闭") } }
        }
    )
    viewer?.takeIf { target -> attachments.any { it.uuid == target.uuid && it.recordId == record.id } }?.let { attachment ->
        AttachmentViewerDialog(
            attachment,
            viewModel.attachmentFile(attachment),
            onDismiss = { viewer = null },
            onDelete = { attachmentDeleteTarget = attachment }
        )
    }
    attachmentDeleteTarget?.takeIf { target -> attachments.any { it.uuid == target.uuid && it.recordId == record.id } }?.let { attachment ->
        ConfirmDialog(
            "将检查报告移入回收站？",
            "“${attachment.displayName}”会保留 30 天，期间可在设置的回收站中恢复，不影响所属病历。",
            {
                if (viewModel.selectedMemberId.value == record.memberId) viewModel.deleteAttachment(attachment) {
                    if (viewer?.id == attachment.id) viewer = null
                }
                attachmentDeleteTarget = null
            },
            { attachmentDeleteTarget = null }
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isNotBlank()) Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) }
}

@Composable
private fun ConditionManagerDialog(conditions: List<ConditionEntity>, memberId: Long, viewModel: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("病情分类") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(50) }, label = { Text("新分类名称") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = add@{
                        if (adding) return@add
                        if (name.isNotBlank() && viewModel.selectedMemberId.value == memberId) {
                            adding = true
                            viewModel.saveCondition(
                                ConditionEntity(name = name.trim(), createdAt = Instant.now().toString(), memberId = memberId),
                                { name = ""; adding = false },
                                { adding = false }
                            )
                        }
                    }, enabled = name.isNotBlank() && !adding
                ) { Text("添加") }
                conditions.forEach { condition ->
                    ListItem(
                        headlineContent = { Text(condition.name) },
                        supportingContent = { if (condition.archived) Text("已归档") },
                        trailingContent = {
                            if (!condition.archived) TextButton(onClick = { if (viewModel.selectedMemberId.value == memberId) viewModel.archiveCondition(condition.id) }) { Text("归档") }
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}
