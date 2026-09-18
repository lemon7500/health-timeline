package com.healthtimeline.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthtimeline.app.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun MedicationScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val schedules by viewModel.medicationSchedules.collectAsStateWithLifecycle()
    val month by viewModel.medicationMonth.collectAsStateWithLifecycle()
    val monthData by viewModel.medicationMonthData.collectAsStateWithLifecycle()
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    val selectedMemberId by viewModel.selectedMemberId.collectAsStateWithLifecycle()
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var createForMemberId by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<MedicationEntity?>(null) }
    var archiveTarget by remember { mutableStateOf<MedicationEntity?>(null) }
    var restoreTarget by remember { mutableStateOf<MedicationEntity?>(null) }
    var deleteTarget by remember { mutableStateOf<MedicationEntity?>(null) }
    var takeTarget by remember { mutableStateOf<MedicationDayEntry?>(null) }
    var correctionTarget by remember { mutableStateOf<MedicationDayEntry?>(null) }
    var addAsNeeded by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(MedicationSection.TODAY) }

    val selected = LocalDate.parse(selectedDate)
    val selectedEntries = monthData.entries(selected)

    LaunchedEffect(selectedMemberId) {
        val today = LocalDate.now()
        selectedDate = today.toString()
        viewModel.selectMedicationMonth(YearMonth.from(today))
        createForMemberId = null
        editing = null
        archiveTarget = null
        restoreTarget = null
        deleteTarget = null
        section = MedicationSection.TODAY
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
            MedicationCalendar(
                month = month,
                data = monthData,
                selectedDate = selected,
                onPrevious = {
                    val target = month.minusMonths(1)
                    viewModel.selectMedicationMonth(target)
                    selectedDate = target.atDay(1).toString()
                },
                onNext = {
                    val target = month.plusMonths(1)
                    viewModel.selectMedicationMonth(target)
                    selectedDate = target.atDay(1).toString()
                },
                onSelect = { date -> selectedDate = date.toString() }
            )
            TabRow(selectedTabIndex = section.ordinal) {
                MedicationSection.entries.forEach { item ->
                    Tab(
                        selected = section == item,
                        onClick = { section = item },
                        text = { Text(item.label, maxLines = 1) }
                    )
                }
            }
            when (section) {
                MedicationSection.TODAY -> {
                    Text("${selected.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))}用药", style = MaterialTheme.typography.titleMedium)
                    if (selectedEntries.isEmpty()) Text("这一天没有用药计划或记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    selectedEntries.forEach { entry ->
                        MedicationDayCard(
                            entry = entry,
                            editable = !selected.isAfter(LocalDate.now()),
                            onTaken = { takeTarget = entry },
                            onSkipped = { viewModel.recordDose(entry, MedicationLogStatus.SKIPPED, null) },
                            onCorrect = { correctionTarget = entry }
                        )
                    }
                    if (!selected.isAfter(LocalDate.now()) && medications.any { medicationActiveOn(it, selected) }) {
                        OutlinedButton(onClick = { addAsNeeded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("补记用药")
                        }
                    }
                }
                MedicationSection.COURSES -> {
                    val active = medications.filter { !it.archived }
                    if (active.isEmpty()) {
                        Text("暂无进行中的用药疗程", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    active.forEach { medication ->
                        val today = LocalDate.now()
                        val times = schedules.filter {
                            it.medicationId == medication.id && it.enabled &&
                                (it.effectiveTo == null || !LocalDate.parse(it.effectiveTo).isBefore(today))
                        }.joinToString("、") { it.localTime }
                        ElevatedCard(onClick = { editing = medication }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(medication.name, fontWeight = FontWeight.SemiBold)
                                Text("${medication.doseAmount} ${medication.doseUnit} · ${medication.startDate}${medication.endDate?.let { " 至 $it" } ?: " 起"}")
                                Text(if (medication.mode == MedicationMode.AS_NEEDED.name) "按需记录" else "每日 $times", color = MaterialTheme.colorScheme.primary)
                                if (medication.instructions.isNotBlank()) Text(medication.instructions, style = MaterialTheme.typography.bodySmall)
                                Row {
                                    TextButton(onClick = { editing = medication }) { Text("编辑") }
                                    TextButton(onClick = { archiveTarget = medication }) { Text("结束疗程") }
                                    TextButton(onClick = { deleteTarget = medication }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
                MedicationSection.ENDED -> {
                    val ended = medications.filter { it.archived }
                        .sortedWith(compareByDescending<MedicationEntity> { it.endedAt ?: it.updatedAt }.thenByDescending { it.id })
                    if (ended.isEmpty()) {
                        Text("暂无已结束的用药", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ended.forEach { medication ->
                        val historicalTimes = schedules.filter { it.medicationId == medication.id }
                            .map { it.localTime }.distinct().sorted().joinToString("、")
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(medication.name, fontWeight = FontWeight.SemiBold)
                                Text("${medication.doseAmount} ${medication.doseUnit}")
                                Text("开始日期：${medication.startDate}")
                                Text("结束时间：${formatMedicationEndedAt(medication)}", color = MaterialTheme.colorScheme.primary)
                                medication.archivedPreviousEndDate?.let { Text("原计划结束日期：$it") }
                                Text(
                                    if (medication.mode == MedicationMode.AS_NEEDED.name) "原疗程：按需记录"
                                    else "原服药时间：${historicalTimes.ifBlank { "无固定时间" }}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (medication.instructions.isNotBlank()) Text(medication.instructions, style = MaterialTheme.typography.bodySmall)
                                Text("历史计划和服药记录仍可在上方日历中查询。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row {
                                    TextButton(onClick = { restoreTarget = medication }) { Text("恢复疗程") }
                                    TextButton(onClick = { deleteTarget = medication }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(72.dp))
        }
    }
    if (createForMemberId != null || editing != null) {
        MedicationEditorDialog(
            existing = editing,
            memberId = editing?.memberId ?: requireNotNull(createForMemberId),
            existingTimes = editing?.let { med ->
                val effectiveDate = LocalDate.now().plusDays(1)
                schedules.filter { it.medicationId == med.id && scheduleEffectiveOn(it, effectiveDate) }
                    .map { LocalTime.parse(it.localTime) }
            }.orEmpty(),
            conditions = conditions.filter { !it.archived },
            onSave = { medication, times, effectiveDate, onFailed ->
                viewModel.saveMedication(
                    medication, times, effectiveDate,
                    { createForMemberId = null; editing = null }, onFailed
                )
            },
            onDismiss = { createForMemberId = null; editing = null }
        )
    }
    archiveTarget?.let { medication ->
        ConfirmDialog(
            "结束疗程？",
            "将记录当前结束日期和时间，并停止后续提醒。当天及此前的用药计划和服药记录都会保留。",
            {
                viewModel.archiveMedication(medication.id)
                archiveTarget = null
                section = MedicationSection.ENDED
            },
            { archiveTarget = null }
        )
    }
    restoreTarget?.let { medication ->
        ConfirmDialog(
            "恢复疗程？",
            if (medication.archivedPreviousEndDate == null) {
                "将恢复为进行中的疗程，并重新启用后续用药提醒。已有日历和服药记录不会改变。"
            } else {
                "将恢复原计划结束日期 ${medication.archivedPreviousEndDate}，并重新启用后续用药提醒。已有日历和服药记录不会改变。"
            },
            {
                viewModel.restoreMedication(medication.id)
                restoreTarget = null
                section = MedicationSection.COURSES
            },
            { restoreTarget = null }
        )
    }
    deleteTarget?.let { medication ->
        ConfirmDialog(
            "将药物移入回收站？",
            "药物、服药计划和历史打卡会保留 30 天，期间可从设置的回收站恢复；未来提醒会停止。",
            {
                viewModel.deleteMedication(medication)
                deleteTarget = null
            },
            { deleteTarget = null }
        )
    }
    takeTarget?.let { entry ->
        MedicationTimePickerDialog(
            title = "选择实际服药时间",
            initial = roundToFiveMinutes(
                if (selected == LocalDate.now()) LocalTime.now() else entry.plannedAt?.toLocalTime() ?: LocalTime.NOON
            ),
            onConfirm = { time ->
                viewModel.recordDose(entry, MedicationLogStatus.TAKEN, selected.atTime(time))
                takeTarget = null
            },
            onDismiss = { takeTarget = null }
        )
    }
    correctionTarget?.let { entry ->
        MedicationCorrectionDialog(
            entry = entry,
            date = selected,
            onConfirm = { status, actualAt ->
                viewModel.correctDose(requireNotNull(entry.log).id, status, actualAt)
                correctionTarget = null
            },
            onDismiss = { correctionTarget = null }
        )
    }
    if (addAsNeeded) {
        AsNeededRecordDialog(
            medications = medications.filter { medicationActiveOn(it, selected) },
            date = selected,
            onConfirm = { medication, actualAt ->
                viewModel.recordAsNeeded(medication, actualAt)
                addAsNeeded = false
            },
            onDismiss = { addAsNeeded = false }
        )
    }
}

private enum class MedicationSection(val label: String) {
    TODAY("当日用药"),
    COURSES("用药疗程"),
    ENDED("已结束用药")
}

private fun formatMedicationEndedAt(medication: MedicationEntity): String {
    val formatted = medication.endedAt?.let { value ->
        runCatching {
            Instant.parse(value).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }.getOrNull()
    }
    return formatted ?: medication.endDate ?: "结束时间未记录"
}

@Composable
private fun MedicationEditorDialog(
    existing: MedicationEntity?,
    memberId: Long,
    existingTimes: List<LocalTime>,
    conditions: List<ConditionEntity>,
    onSave: (MedicationEntity, List<LocalTime>, LocalDate?, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var amount by remember(existing) { mutableStateOf(existing?.doseAmount.orEmpty()) }
    var unit by remember(existing) { mutableStateOf(existing?.doseUnit ?: "片") }
    var instructions by remember(existing) { mutableStateOf(existing?.instructions.orEmpty()) }
    var start by remember(existing) { mutableStateOf(existing?.startDate ?: LocalDate.now().toString()) }
    var end by remember(existing) { mutableStateOf(existing?.endDate.orEmpty()) }
    var effectiveOn by remember(existing) { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
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
                if (existing != null) {
                    OutlinedTextField(
                        effectiveOn,
                        { effectiveOn = it.take(10) },
                        label = { Text("新计划生效日期") },
                        supportingText = { Text("默认明天；今天已有记录时不能改为今天") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                val effectiveDate = if (existing == null) null else runCatching { LocalDate.parse(effectiveOn) }.getOrNull()
                val parsedTimes = if (mode == MedicationMode.AS_NEEDED.name) emptyList() else times.distinct().sorted()
                when {
                    name.isBlank() || amount.isBlank() || unit.isBlank() -> error = "请填写药名、剂量和单位"
                    startDate == null -> error = "开始日期格式不正确"
                    end.isNotBlank() && endDate == null -> error = "结束日期格式不正确"
                    existing != null && effectiveDate == null -> error = "生效日期格式不正确"
                    effectiveDate != null && effectiveDate.isBefore(LocalDate.now()) -> error = "生效日期不能早于今天"
                    endDate != null && endDate.isBefore(startDate) -> error = "结束日期不能早于开始日期"
                    existing != null && endDate != null && endDate.isBefore(LocalDate.now()) -> error = "已有疗程不能追溯结束到今天以前"
                    mode == MedicationMode.SCHEDULED.name && effectiveDate != null && endDate != null && endDate.isBefore(effectiveDate) -> error = "新计划生效日期不能晚于疗程结束日期"
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
                            ), parsedTimes, effectiveDate
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

@Composable
private fun MedicationCalendar(
    month: YearMonth,
    data: MedicationMonthData,
    selectedDate: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(Icons.Outlined.ArrowBackIosNew, "上个月") }
                Text(month.format(DateTimeFormatter.ofPattern("yyyy年M月")), fontWeight = FontWeight.Bold)
                IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, "下个月") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            }
            val leading = month.atDay(1).dayOfWeek.value - 1
            val cells = leading + month.lengthOfMonth()
            repeat((cells + 6) / 7) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val day = row * 7 + column - leading + 1
                        if (day !in 1..month.lengthOfMonth()) {
                            Spacer(Modifier.weight(1f).height(54.dp))
                        } else {
                            val date = month.atDay(day)
                            val entries = data.entries(date)
                            val recorded = entries.count { it.status != MedicationDayStatus.UNRECORDED }
                            Column(
                                Modifier.weight(1f).height(54.dp)
                                    .background(
                                        if (date == selectedDate) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onSelect(date) }
                                    .padding(3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(day.toString())
                                if (entries.isNotEmpty()) {
                                    Text(
                                        "$recorded/${entries.size}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (recorded == entries.size) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MedicationDayCard(
    entry: MedicationDayEntry,
    editable: Boolean,
    onTaken: () -> Unit,
    onSkipped: () -> Unit,
    onCorrect: () -> Unit
) {
    val dose = entry.log?.let { "${it.doseAmountSnapshot} ${it.doseUnitSnapshot}" }
        ?: entry.schedule?.let { "${it.doseAmountSnapshot} ${it.doseUnitSnapshot}" }
        ?: "${entry.medication.doseAmount} ${entry.medication.doseUnit}"
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.medication.name, fontWeight = FontWeight.SemiBold)
            Text(dose)
            entry.plannedAt?.let { Text("计划时间：${it.toLocalTime().format(TIME_FORMATTER)}") }
            entry.actualAt?.let { Text("实际时间：${it.toLocalTime().format(TIME_FORMATTER)}") }
            Text(
                when (entry.status) {
                    MedicationDayStatus.TAKEN -> "已服"
                    MedicationDayStatus.SKIPPED -> "已跳过"
                    MedicationDayStatus.UNRECORDED -> "未记录"
                },
                color = MaterialTheme.colorScheme.primary
            )
            if (editable) {
                if (entry.log == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTaken) { Text("已服") }
                        OutlinedButton(onClick = onSkipped) { Text("跳过") }
                    }
                } else {
                    TextButton(onClick = onCorrect) { Text("修正记录") }
                }
            }
        }
    }
}

private fun medicationActiveOn(value: MedicationEntity, date: LocalDate): Boolean =
    !date.isBefore(LocalDate.parse(value.startDate)) &&
        (value.endDate == null || !date.isAfter(LocalDate.parse(value.endDate)))

private fun scheduleEffectiveOn(value: MedicationScheduleEntity, date: LocalDate): Boolean =
    value.enabled && !date.isBefore(LocalDate.parse(value.effectiveFrom)) &&
        (value.effectiveTo == null || !date.isAfter(LocalDate.parse(value.effectiveTo)))

@Composable
private fun MedicationCorrectionDialog(
    entry: MedicationDayEntry,
    date: LocalDate,
    onConfirm: (MedicationLogStatus, LocalDateTime?) -> Unit,
    onDismiss: () -> Unit
) {
    val log = requireNotNull(entry.log)
    var status by remember(log.id) {
        mutableStateOf(if (log.status == MedicationLogStatus.TAKEN.name) MedicationLogStatus.TAKEN else MedicationLogStatus.SKIPPED)
    }
    var time by remember(log.id) {
        mutableStateOf(log.actualAt?.let { LocalDateTime.parse(it).toLocalTime() } ?: entry.plannedAt?.toLocalTime() ?: LocalTime.NOON)
    }
    var showPicker by remember(log.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认修正用药记录？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${entry.medication.name} · $date")
                LabeledDropdown(
                    "状态",
                    status.name,
                    listOf(MedicationLogStatus.TAKEN.name to "已服", MedicationLogStatus.SKIPPED.name to "跳过"),
                    { status = MedicationLogStatus.valueOf(it) }
                )
                if (status == MedicationLogStatus.TAKEN) {
                    OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("实际服药时间 ${time.format(TIME_FORMATTER)}")
                    }
                }
                Text("将保留原记录编号、创建时间和历史剂量。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(status, if (status == MedicationLogStatus.TAKEN) date.atTime(time) else null)
            }) { Text("确认修正") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showPicker) {
        MedicationTimePickerDialog(
            title = "选择实际服药时间",
            initial = time,
            onConfirm = { time = it; showPicker = false },
            onDismiss = { showPicker = false }
        )
    }
}

@Composable
private fun AsNeededRecordDialog(
    medications: List<MedicationEntity>,
    date: LocalDate,
    onConfirm: (MedicationEntity, LocalDateTime) -> Unit,
    onDismiss: () -> Unit
) {
    var medicationId by remember(medications) { mutableStateOf(medications.firstOrNull()?.id) }
    var time by remember(date) {
        mutableStateOf(roundToFiveMinutes(if (date == LocalDate.now()) LocalTime.now() else LocalTime.NOON))
    }
    var showPicker by remember(date) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补记用药") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(date.toString())
                LabeledDropdown(
                    "药物",
                    medicationId?.toString().orEmpty(),
                    medications.map { it.id.toString() to it.name },
                    { medicationId = it.toLongOrNull() }
                )
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("实际服药时间 ${time.format(TIME_FORMATTER)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { medications.firstOrNull { it.id == medicationId }?.let { onConfirm(it, date.atTime(time)) } },
                enabled = medicationId != null
            ) { Text("确认记录") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (showPicker) {
        MedicationTimePickerDialog(
            title = "选择实际服药时间",
            initial = time,
            onConfirm = { time = it; showPicker = false },
            onDismiss = { showPicker = false }
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
    title: String = "选择提醒时间",
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    var hour by remember(initial) { mutableIntStateOf(initial.hour) }
    var minute by remember(initial) { mutableIntStateOf((initial.minute / 5) * 5) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
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
