package com.healthtimeline.app.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthtimeline.app.data.*
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun CalendarScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var filterCondition by rememberSaveable { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<ClinicalRecordEntity?>(null) }
    var createNew by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ClinicalRecordEntity?>(null) }
    var manageConditions by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ClinicalRecordEntity?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val currentMonth = YearMonth.parse(month)
    val filtered = records.filter { filterCondition == null || it.conditionId == filterCondition }
    val byDate = filtered.groupBy { LocalDate.parse(it.recordDate) }
    val dayRecords = filtered.filter { it.recordDate == selectedDate }
    val searchResults = remember(records, conditions, searchQuery) {
        searchCalendarRecords(records, conditions, searchQuery)
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = {
            FloatingActionButton(onClick = { createNew = true }) { Icon(Icons.Outlined.Add, "新增病历") }
        }
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("病程日历", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
                AssistChip(onClick = { manageConditions = true }, label = { Text("管理分类") })
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

    if (createNew || editing != null) {
        RecordEditorDialog(
            initialDate = selectedDate,
            record = editing,
            conditions = conditions.filter { !it.archived },
            onSave = { value, onFailed ->
                viewModel.saveRecord(value, { createNew = false; editing = null }, onFailed)
            },
            onDismiss = { createNew = false; editing = null }
        )
    }
    details?.let { record ->
        RecordDetailDialog(
            record = record,
            condition = conditions.firstOrNull { it.id == record.conditionId },
            attachments = attachments.filter { it.recordId == record.id },
            viewModel = viewModel,
            onEdit = { editing = record; details = null },
            onDelete = { deleteTarget = record; details = null },
            onDismiss = { details = null }
        )
    }
    if (manageConditions) {
        ConditionManagerDialog(conditions, viewModel, onDismiss = { manageConditions = false })
    }
    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除病历？",
            message = "病历和它的全部检查报告会被永久删除。",
            onConfirm = { viewModel.deleteRecord(target); deleteTarget = null },
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

@Composable
private fun RecordEditorDialog(
    initialDate: String,
    record: ClinicalRecordEntity?,
    conditions: List<ConditionEntity>,
    onSave: (ClinicalRecordEntity, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var date by remember(record) { mutableStateOf(record?.recordDate ?: initialDate) }
    var title by remember(record) { mutableStateOf(record?.title.orEmpty()) }
    var stage by remember(record) { mutableStateOf(record?.stage ?: VisitStage.OTHER.name) }
    var conditionId by remember(record) { mutableStateOf(record?.conditionId) }
    var symptoms by remember(record) { mutableStateOf(record?.symptoms.orEmpty()) }
    var diagnosis by remember(record) { mutableStateOf(record?.diagnosis.orEmpty()) }
    var treatment by remember(record) { mutableStateOf(record?.treatment.orEmpty()) }
    var medicationNotes by remember(record) { mutableStateOf(record?.medicationNotes.orEmpty()) }
    var hospital by remember(record) { mutableStateOf(record?.hospital.orEmpty()) }
    var clinician by remember(record) { mutableStateOf(record?.clinician.orEmpty()) }
    var notes by remember(record) { mutableStateOf(record?.notes.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember(record) { mutableStateOf(false) }
    val now = Instant.now().toString()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (record == null) "新增病情记录" else "编辑病情记录") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(date, { date = it.take(10) }, label = { Text("日期（YYYY-MM-DD）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("小标题 *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LabeledDropdown(
                    "病情分类",
                    conditionId?.toString().orEmpty(),
                    listOf("" to "未分类") + conditions.map { it.id.toString() to it.name },
                    { conditionId = it.toLongOrNull() }
                )
                LabeledDropdown(
                    "记录类型", stage,
                    VisitStage.entries.map { it.name to visitStageLabel(it.name) },
                    { stage = it }
                )
                MultiField("症状/病情", symptoms) { symptoms = it }
                MultiField("诊断", diagnosis) { diagnosis = it }
                MultiField("治疗方案", treatment) { treatment = it }
                MultiField("就诊用药记录", medicationNotes) { medicationNotes = it }
                OutlinedTextField(hospital, { hospital = it.take(100) }, label = { Text("医院") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clinician, { clinician = it.take(100) }, label = { Text("医生") }, modifier = Modifier.fillMaxWidth())
                MultiField("其他备注", notes) { notes = it }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = save@{
                if (submitting) return@save
                val validDate = runCatching { LocalDate.parse(date) }.getOrNull()
                when {
                    title.isBlank() -> error = "请填写小标题"
                    validDate == null -> error = "日期格式不正确"
                    else -> {
                        submitting = true
                        onSave(
                            ClinicalRecordEntity(
                            id = record?.id ?: 0,
                            conditionId = conditionId,
                            recordDate = validDate.toString(),
                            title = title.trim(),
                            stage = stage,
                            symptoms = symptoms.trim(), diagnosis = diagnosis.trim(), treatment = treatment.trim(),
                            medicationNotes = medicationNotes.trim(), hospital = hospital.trim(), clinician = clinician.trim(), notes = notes.trim(),
                                createdAt = record?.createdAt ?: now, updatedAt = now
                            )
                        ) { submitting = false }
                    }
                }
            }, enabled = !submitting) { Text(if (submitting) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } }
    )
}

@Composable
private fun MultiField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onChange(it.take(10_000)) },
        label = { Text(label) },
        minLines = 2,
        maxLines = 5,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RecordDetailDialog(
    record: ClinicalRecordEntity,
    condition: ConditionEntity?,
    attachments: List<AttachmentEntity>,
    viewModel: AppViewModel,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var viewer by remember { mutableStateOf<AttachmentEntity?>(null) }
    var cameraUriText by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.importAttachments(record.id, uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUriText?.let { viewModel.importAttachments(record.id, listOf(Uri.parse(it))) }
        cameraUriText = null
    }
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
                    OutlinedButton(onClick = { documentPicker.launch(arrayOf("image/*", "application/pdf")) }) {
                        Icon(Icons.Outlined.AttachFile, null); Text("选择文件")
                    }
                    OutlinedButton(onClick = {
                        val uri = createCameraUri(context)
                        cameraUriText = uri.toString()
                        camera.launch(uri)
                    }) { Icon(Icons.Outlined.CameraAlt, null); Text("拍照") }
                }
                attachments.forEach { attachment ->
                    ListItem(
                        headlineContent = { Text(attachment.displayName, maxLines = 1) },
                        supportingContent = { Text(if (attachment.kind == AttachmentKind.PDF.name) "PDF" else "图片") },
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
    viewer?.let { AttachmentViewerDialog(it, viewModel.attachmentFile(it), onDismiss = { viewer = null }) }
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isNotBlank()) Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) }
}

@Composable
private fun ConditionManagerDialog(conditions: List<ConditionEntity>, viewModel: AppViewModel, onDismiss: () -> Unit) {
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
                        if (name.isNotBlank()) {
                            adding = true
                            viewModel.saveCondition(
                                ConditionEntity(name = name.trim(), createdAt = Instant.now().toString()),
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
                            if (!condition.archived) TextButton(onClick = { viewModel.archiveCondition(condition.id) }) { Text("归档") }
                        }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun createCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "report-${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
