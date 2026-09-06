package com.healthtimeline.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthtimeline.app.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun MedicationScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val doses by viewModel.todayDoses.collectAsStateWithLifecycle()
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val schedules by viewModel.medicationSchedules.collectAsStateWithLifecycle()
    val logs by viewModel.medicationLogs.collectAsStateWithLifecycle()
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    val selectedMemberId by viewModel.selectedMemberId.collectAsStateWithLifecycle()
    var createForMemberId by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<MedicationEntity?>(null) }
    var archiveTarget by remember { mutableStateOf<MedicationEntity?>(null) }

    LaunchedEffect(selectedMemberId) {
        createForMemberId = null
        editing = null
        archiveTarget = null
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { FloatingActionButton(onClick = { selectedMemberId?.let { createForMemberId = it } }) { Icon(Icons.Outlined.Add, "新增药物") } }
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("用药", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            MemberSwitcher(viewModel)
            Text("今日计划", style = MaterialTheme.typography.titleMedium)
            if (doses.isEmpty()) Text("今天没有待记录的用药", color = MaterialTheme.colorScheme.onSurfaceVariant)
            doses.forEach { dose ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(dose.medication.name, fontWeight = FontWeight.SemiBold)
                        Text("${dose.medication.doseAmount} ${dose.medication.doseUnit} · ${dose.schedule?.localTime ?: "按需"}")
                        if (dose.log == null) {
                            Row {
                                Button(onClick = { viewModel.markDose(dose, MedicationLogStatus.TAKEN) }) { Text("已服") }
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { viewModel.markDose(dose, MedicationLogStatus.SKIPPED) }) { Text("跳过") }
                            }
                        } else {
                            Text(if (dose.log?.status == MedicationLogStatus.TAKEN.name) "已服用" else "已跳过", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("药物疗程", style = MaterialTheme.typography.titleMedium)
            medications.filter { !it.archived }.forEach { medication ->
                val times = schedules.filter { it.medicationId == medication.id }.joinToString("、") { it.localTime }
                ElevatedCard(onClick = { editing = medication }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(medication.name, fontWeight = FontWeight.SemiBold)
                        Text("${medication.doseAmount} ${medication.doseUnit} · ${medication.startDate}${medication.endDate?.let { " 至 $it" } ?: " 起"}")
                        Text(if (medication.mode == MedicationMode.AS_NEEDED.name) "按需记录" else "每日 $times", color = MaterialTheme.colorScheme.primary)
                        if (medication.instructions.isNotBlank()) Text(medication.instructions, style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick = { editing = medication }) { Text("编辑") }; TextButton(onClick = { archiveTarget = medication }) { Text("结束疗程") } }
                    }
                }
            }
            HorizontalDivider()
            Text("最近用药记录", style = MaterialTheme.typography.titleMedium)
            if (logs.isEmpty()) Text("暂无打卡记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            logs.take(30).forEach { log ->
                val medication = medications.firstOrNull { it.id == log.medicationId }
                ListItem(
                    headlineContent = { Text(medication?.name ?: "已删除药物") },
                    supportingContent = { Text("${log.scheduledAt.replace('T', ' ')} · ${log.doseAmountSnapshot} ${log.doseUnitSnapshot}") },
                    trailingContent = { Text(if (log.status == MedicationLogStatus.TAKEN.name) "已服" else "跳过") }
                )
            }
            Spacer(Modifier.height(72.dp))
        }
    }
    if (createForMemberId != null || editing != null) {
        MedicationEditorDialog(
            existing = editing,
            memberId = editing?.memberId ?: requireNotNull(createForMemberId),
            existingTimes = editing?.let { med -> schedules.filter { it.medicationId == med.id }.map { LocalTime.parse(it.localTime) } }.orEmpty(),
            conditions = conditions.filter { !it.archived },
            onSave = { medication, times, onFailed ->
                viewModel.saveMedication(medication, times, { createForMemberId = null; editing = null }, onFailed)
            },
            onDismiss = { createForMemberId = null; editing = null }
        )
    }
    archiveTarget?.let { medication ->
        ConfirmDialog(
            "结束疗程？",
            "该药物将不再生成新的提醒，历史用药记录会保留。",
            { viewModel.archiveMedication(medication.id); archiveTarget = null },
            { archiveTarget = null }
        )
    }
}

@Composable
private fun MedicationEditorDialog(
    existing: MedicationEntity?,
    memberId: Long,
    existingTimes: List<LocalTime>,
    conditions: List<ConditionEntity>,
    onSave: (MedicationEntity, List<LocalTime>, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var amount by remember(existing) { mutableStateOf(existing?.doseAmount.orEmpty()) }
    var unit by remember(existing) { mutableStateOf(existing?.doseUnit ?: "片") }
    var instructions by remember(existing) { mutableStateOf(existing?.instructions.orEmpty()) }
    var start by remember(existing) { mutableStateOf(existing?.startDate ?: LocalDate.now().toString()) }
    var end by remember(existing) { mutableStateOf(existing?.endDate.orEmpty()) }
    var mode by remember(existing) { mutableStateOf(existing?.mode ?: MedicationMode.SCHEDULED.name) }
    var times by remember(existing, existingTimes) {
        mutableStateOf(existingTimes.ifEmpty { listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)) }.distinct().sorted())
    }
    var timePickerTarget by remember(existing) { mutableStateOf<Int?>(null) }
    var addingTime by remember(existing) { mutableStateOf(false) }
    var conditionId by remember(existing) { mutableStateOf(existing?.conditionId) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember(existing) { mutableStateOf(false) }
    val now = Instant.now().toString()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (existing == null) "新增药物" else "编辑药物") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("药名 *") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(amount, { amount = it.take(30) }, label = { Text("每次剂量 *") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it.take(20) }, label = { Text("单位 *") }, modifier = Modifier.weight(1f))
                }
                LabeledDropdown("病情分类", conditionId?.toString().orEmpty(), listOf("" to "未分类") + conditions.map { it.id.toString() to it.name }, { conditionId = it.toLongOrNull() })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it.take(10) }, label = { Text("开始日期") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(end, { end = it.take(10) }, label = { Text("结束日期（可空）") }, modifier = Modifier.weight(1f))
                }
                LabeledDropdown("记录方式", mode, listOf(MedicationMode.SCHEDULED.name to "每日定时", MedicationMode.AS_NEEDED.name to "按需记录"), { mode = it })
                if (mode == MedicationMode.SCHEDULED.name) {
                    Text("每日提醒时间", style = MaterialTheme.typography.labelLarge)
                    times.forEachIndexed { index, time ->
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth().clickable {
                                timePickerTarget = index
                                addingTime = false
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(time.format(TIME_FORMATTER), fontWeight = FontWeight.SemiBold)
                                Row {
                                    TextButton(onClick = {
                                        timePickerTarget = index
                                        addingTime = false
                                    }) { Text("修改") }
                                    TextButton(onClick = { times = times.filterIndexed { itemIndex, _ -> itemIndex != index } }) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            addingTime = true
                            timePickerTarget = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ 添加提醒时间") }
                    Text(
                        "上下滑动选择时间，分钟以 5 分钟为间隔",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(instructions, { instructions = it.take(1000) }, label = { Text("服用说明") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = save@{
                if (submitting) return@save
                val startDate = runCatching { LocalDate.parse(start) }.getOrNull()
                val endDate = if (end.isBlank()) null else runCatching { LocalDate.parse(end) }.getOrNull()
                val parsedTimes = if (mode == MedicationMode.AS_NEEDED.name) emptyList() else times.distinct().sorted()
                when {
                    name.isBlank() || amount.isBlank() || unit.isBlank() -> error = "请填写药名、剂量和单位"
                    startDate == null -> error = "开始日期格式不正确"
                    end.isNotBlank() && endDate == null -> error = "结束日期格式不正确"
                    endDate != null && endDate.isBefore(startDate) -> error = "结束日期不能早于开始日期"
                    mode == MedicationMode.SCHEDULED.name && parsedTimes.isEmpty() -> error = "至少填写一个有效时间"
                    else -> {
                        submitting = true
                        onSave(
                            MedicationEntity(
                            id = existing?.id ?: 0, conditionId = conditionId, name = name.trim(),
                            doseAmount = amount.trim(), doseUnit = unit.trim(), instructions = instructions.trim(),
                            startDate = startDate.toString(), endDate = endDate?.toString(), mode = mode,
                                archived = false,
                                createdAt = existing?.createdAt ?: now,
                                updatedAt = now,
                                uuid = existing?.uuid ?: java.util.UUID.randomUUID().toString(),
                                memberId = memberId
                            ), parsedTimes
                        ) { submitting = false }
                    }
                }
            }, enabled = !submitting) { Text(if (submitting) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } }
    )

    if (addingTime || timePickerTarget != null) {
        val initial = timePickerTarget?.let { times[it] } ?: roundToFiveMinutes(LocalTime.now())
        MedicationTimePickerDialog(
            initial = initial,
            onConfirm = { selected ->
                val duplicate = times.withIndex().any { (index, value) ->
                    value == selected && index != timePickerTarget
                }
                if (duplicate) {
                    error = "这个提醒时间已经添加"
                } else {
                    times = if (timePickerTarget == null) {
                        (times + selected).distinct().sorted()
                    } else {
                        times.mapIndexed { index, value -> if (index == timePickerTarget) selected else value }
                            .distinct().sorted()
                    }
                }
                addingTime = false
                timePickerTarget = null
            },
            onDismiss = {
                addingTime = false
                timePickerTarget = null
            }
        )
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
internal val MEDICATION_MINUTE_OPTIONS: List<Int> = (0..55 step 5).toList()

internal fun roundToFiveMinutes(time: LocalTime): LocalTime {
    val roundedMinute = ((time.minute + 2) / 5) * 5
    return if (roundedMinute == 60) {
        LocalTime.of((time.hour + 1) % 24, 0)
    } else {
        LocalTime.of(time.hour, roundedMinute)
    }
}

@Composable
private fun MedicationTimePickerDialog(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember(initial) { mutableIntStateOf(initial.hour) }
    var minute by remember(initial) { mutableIntStateOf((initial.minute / 5) * 5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择提醒时间") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "%02d:%02d".format(hour, minute),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WheelPicker(
                        values = (0..23).toList(),
                        selected = hour,
                        suffix = "时",
                        onSelected = { hour = it },
                        modifier = Modifier.weight(1f)
                    )
                    Text("：", style = MaterialTheme.typography.headlineSmall)
                    WheelPicker(
                        values = MEDICATION_MINUTE_OPTIONS,
                        selected = minute,
                        suffix = "分",
                        onSelected = { minute = it },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "上下滑动，分钟间隔为 5 分钟",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(hour, minute)) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun WheelPicker(
    values: List<Int>,
    selected: Int,
    suffix: String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val initialIndex = values.indexOf(selected).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(listState)
    LaunchedEffect(listState, values) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val layout = listState.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                val closest = layout.visibleItemsInfo.minByOrNull { item ->
                    abs(item.offset + item.size / 2 - center)
                }
                closest?.index?.let { index -> values.getOrNull(index)?.let(onSelected) }
            }
        }
    }
    Box(modifier.height(144.dp)) {
        LazyColumn(
            state = listState,
            flingBehavior = flingBehavior,
            contentPadding = PaddingValues(vertical = 48.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(values, key = { _, value -> value }) { index, value ->
                Text(
                    text = "%02d $suffix".format(value),
                    textAlign = TextAlign.Center,
                    style = if (value == selected) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                    color = if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(48.dp).wrapContentHeight(Alignment.CenterVertically)
                        .clickable {
                            onSelected(value)
                        }
                )
            }
        }
        Column(Modifier.matchParentSize(), verticalArrangement = Arrangement.Center) {
            HorizontalDivider()
            Spacer(Modifier.height(48.dp))
            HorizontalDivider()
        }
    }
}
