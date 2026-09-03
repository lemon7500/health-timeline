package com.healthtimeline.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.healthtimeline.app.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun MedicationScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val doses by viewModel.todayDoses.collectAsStateWithLifecycle()
    val medications by viewModel.medications.collectAsStateWithLifecycle()
    val schedules by viewModel.medicationSchedules.collectAsStateWithLifecycle()
    val logs by viewModel.medicationLogs.collectAsStateWithLifecycle()
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MedicationEntity?>(null) }
    var archiveTarget by remember { mutableStateOf<MedicationEntity?>(null) }

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { FloatingActionButton(onClick = { create = true }) { Icon(Icons.Outlined.Add, "新增药物") } }
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("用药", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
    if (create || editing != null) {
        MedicationEditorDialog(
            existing = editing,
            existingTimes = editing?.let { med -> schedules.filter { it.medicationId == med.id }.map { LocalTime.parse(it.localTime) } }.orEmpty(),
            conditions = conditions.filter { !it.archived },
            onSave = { medication, times, onFailed ->
                viewModel.saveMedication(medication, times, { create = false; editing = null }, onFailed)
            },
            onDismiss = { create = false; editing = null }
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
    var times by remember(existing) { mutableStateOf((existingTimes.ifEmpty { listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)) }).joinToString(",") { it.toString() }) }
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
                    OutlinedTextField(times, { times = it.take(100) }, label = { Text("时间，用逗号分隔（如 08:00,20:00）") }, modifier = Modifier.fillMaxWidth())
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
                val timeTokens = if (mode == MedicationMode.AS_NEEDED.name) emptyList() else times.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                val parsedTimes = timeTokens.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
                when {
                    name.isBlank() || amount.isBlank() || unit.isBlank() -> error = "请填写药名、剂量和单位"
                    startDate == null -> error = "开始日期格式不正确"
                    end.isNotBlank() && endDate == null -> error = "结束日期格式不正确"
                    endDate != null && endDate.isBefore(startDate) -> error = "结束日期不能早于开始日期"
                    parsedTimes.size != timeTokens.size -> error = "存在无效时间，请使用 HH:mm 格式"
                    mode == MedicationMode.SCHEDULED.name && parsedTimes.isEmpty() -> error = "至少填写一个有效时间"
                    else -> {
                        submitting = true
                        onSave(
                            MedicationEntity(
                            id = existing?.id ?: 0, conditionId = conditionId, name = name.trim(),
                            doseAmount = amount.trim(), doseUnit = unit.trim(), instructions = instructions.trim(),
                            startDate = startDate.toString(), endDate = endDate?.toString(), mode = mode,
                                archived = false, createdAt = existing?.createdAt ?: now, updatedAt = now
                            ), parsedTimes
                        ) { submitting = false }
                    }
                }
            }, enabled = !submitting) { Text(if (submitting) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } }
    )
}
