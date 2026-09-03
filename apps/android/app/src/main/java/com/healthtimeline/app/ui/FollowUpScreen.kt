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
import com.healthtimeline.app.domain.RecurrenceCalculator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun FollowUpScreen(viewModel: AppViewModel, padding: PaddingValues) {
    val schedules by viewModel.followUps.collectAsStateWithLifecycle()
    val occurrences by viewModel.occurrences.collectAsStateWithLifecycle()
    val conditions by viewModel.conditions.collectAsStateWithLifecycle()
    val selectedMemberId by viewModel.selectedMemberId.collectAsStateWithLifecycle()
    var createForMemberId by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<FollowUpScheduleEntity?>(null) }
    var deleting by remember { mutableStateOf<FollowUpScheduleEntity?>(null) }
    val pendingOccurrences = occurrences.filter { it.status == OccurrenceStatus.PENDING.name }

    LaunchedEffect(selectedMemberId) {
        createForMemberId = null
        editing = null
        deleting = null
    }

    Scaffold(
        modifier = Modifier.padding(padding),
        floatingActionButton = { FloatingActionButton(onClick = { selectedMemberId?.let { createForMemberId = it } }) { Icon(Icons.Outlined.Add, "新增复查") } }
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("复查提醒", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            MemberSwitcher(viewModel)
            if (!viewModel.canScheduleExact()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text("尚未允许精确闹钟，提醒可能延迟。请在“设置”页授权。", Modifier.padding(12.dp))
                }
            }
            if (pendingOccurrences.isNotEmpty()) {
                Text("待处理", style = MaterialTheme.typography.titleMedium)
                pendingOccurrences.forEach { occurrence ->
                    val schedule = schedules.firstOrNull { it.id == occurrence.scheduleId }
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(schedule?.title ?: "复查", fontWeight = FontWeight.SemiBold)
                            Text("应复查：${occurrence.dueDate}", color = if (LocalDate.parse(occurrence.dueDate).isBefore(LocalDate.now())) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Row {
                                TextButton(onClick = { viewModel.completeOccurrence(occurrence.id) }) { Text("标记完成") }
                                TextButton(onClick = { viewModel.completeOccurrence(occurrence.id, skipped = true) }) { Text("跳过") }
                            }
                        }
                    }
                }
            }
            Text("计划", style = MaterialTheme.typography.titleMedium)
            if (schedules.isEmpty()) Text("暂无复查计划。点击右下角添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            schedules.forEach { schedule ->
                ElevatedCard(onClick = { editing = schedule }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(schedule.title, fontWeight = FontWeight.SemiBold)
                        Text(if (schedule.enabled) "下次：${schedule.nextDueDate} ${schedule.reminderTime}" else "一次性计划已结束")
                        Text(recurrenceLabel(schedule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row { TextButton(onClick = { editing = schedule }) { Text("编辑未来计划") }; TextButton(onClick = { deleting = schedule }) { Text("删除") } }
                    }
                }
            }
            Spacer(Modifier.height(72.dp))
        }
    }
    if (createForMemberId != null || editing != null) {
        FollowUpEditorDialog(
            existing = editing,
            memberId = editing?.memberId ?: requireNotNull(createForMemberId),
            conditions = conditions.filter { !it.archived },
            onSave = { value, onFailed ->
                viewModel.saveFollowUp(value, { createForMemberId = null; editing = null }, onFailed)
            },
            onDismiss = { createForMemberId = null; editing = null }
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            "删除复查计划？",
            "未来提醒会停止，已经产生的历史状态会随计划删除。",
            { viewModel.deleteFollowUp(target); deleting = null },
            { deleting = null }
        )
    }
}

@Composable
private fun FollowUpEditorDialog(
    existing: FollowUpScheduleEntity?,
    memberId: Long,
    conditions: List<ConditionEntity>,
    onSave: (FollowUpScheduleEntity, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var conditionId by remember(existing) { mutableStateOf(existing?.conditionId) }
    var rule by remember(existing) { mutableStateOf(existing?.recurrenceType ?: RecurrenceType.ONCE.name) }
    var interval by remember(existing) { mutableStateOf((existing?.interval ?: 1).toString()) }
    var anchor by remember(existing) { mutableStateOf(existing?.anchorDate ?: LocalDate.now().toString()) }
    var weekday by remember(existing) { mutableStateOf(existing?.weekday ?: LocalDate.now().dayOfWeek.value) }
    var time by remember(existing) { mutableStateOf(existing?.reminderTime ?: "09:00") }
    var lead by remember(existing) { mutableIntStateOf(existing?.leadDays ?: 0) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember(existing) { mutableStateOf(false) }
    val now = Instant.now().toString()

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(if (existing == null) "新增复查计划" else "编辑未来计划") },
        text = {
            Column(Modifier.heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(100) }, label = { Text("计划标题 *") }, modifier = Modifier.fillMaxWidth())
                LabeledDropdown("病情分类", conditionId?.toString().orEmpty(), listOf("" to "未分类") + conditions.map { it.id.toString() to it.name }, { conditionId = it.toLongOrNull() })
                OutlinedTextField(anchor, { anchor = it.take(10) }, label = { Text("起始/下次日期（YYYY-MM-DD）") }, modifier = Modifier.fillMaxWidth())
                LabeledDropdown(
                    "重复规则", rule,
                    listOf(
                        RecurrenceType.ONCE.name to "仅一次",
                        RecurrenceType.EVERY_N_DAYS.name to "每 N 天",
                        RecurrenceType.EVERY_N_WEEKS.name to "每 N 周",
                        RecurrenceType.EVERY_N_MONTHS.name to "每 N 个月"
                    ), { rule = it }
                )
                if (rule != RecurrenceType.ONCE.name) {
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(3) }, label = { Text("间隔 N") }, modifier = Modifier.fillMaxWidth())
                }
                if (rule == RecurrenceType.EVERY_N_WEEKS.name) {
                    LabeledDropdown(
                        "星期", weekday.toString(),
                        listOf("1" to "星期一", "2" to "星期二", "3" to "星期三", "4" to "星期四", "5" to "星期五", "6" to "星期六", "7" to "星期日"),
                        { weekday = it.toInt() }
                    )
                }
                OutlinedTextField(time, { time = it.take(5) }, label = { Text("提醒时间（HH:mm）") }, modifier = Modifier.fillMaxWidth())
                LabeledDropdown("提前提醒", lead.toString(), listOf("0" to "当天", "1" to "提前 1 天", "3" to "提前 3 天", "7" to "提前 7 天"), { lead = it.toInt() })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = save@{
                if (submitting) return@save
                val date = runCatching { LocalDate.parse(anchor) }.getOrNull()
                val parsedTime = runCatching { LocalTime.parse(time) }.getOrNull()
                val parsedInterval = interval.toIntOrNull()?.coerceAtLeast(1)
                when {
                    title.isBlank() -> error = "请填写计划标题"
                    date == null -> error = "日期格式不正确"
                    parsedTime == null -> error = "提醒时间格式不正确"
                    parsedInterval == null -> error = "间隔必须大于 0"
                    else -> {
                        val nextDate = if (rule == RecurrenceType.EVERY_N_WEEKS.name) RecurrenceCalculator.firstWeeklyOnOrAfter(date, weekday) else date
                        submitting = true
                        onSave(
                            FollowUpScheduleEntity(
                                id = existing?.id ?: 0,
                                conditionId = conditionId,
                                title = title.trim(), recurrenceType = rule, interval = parsedInterval,
                                anchorDate = date.toString(), anchorDayOfMonth = date.dayOfMonth,
                                weekday = if (rule == RecurrenceType.EVERY_N_WEEKS.name) weekday else null,
                                reminderTime = parsedTime.toString(), leadDays = lead,
                                nextDueDate = nextDate.toString(), enabled = true,
                                createdAt = existing?.createdAt ?: now,
                                updatedAt = now,
                                uuid = existing?.uuid ?: java.util.UUID.randomUUID().toString(),
                                memberId = memberId
                            )
                        ) { submitting = false }
                    }
                }
            }, enabled = !submitting) { Text(if (submitting) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") } }
    )
}

private fun recurrenceLabel(value: FollowUpScheduleEntity): String = when (RecurrenceType.valueOf(value.recurrenceType)) {
    RecurrenceType.ONCE -> "仅一次"
    RecurrenceType.EVERY_N_DAYS -> "每 ${value.interval} 天"
    RecurrenceType.EVERY_N_WEEKS -> "每 ${value.interval} 周 · 星期${"一二三四五六日"[(value.weekday ?: 1) - 1]}"
    RecurrenceType.EVERY_N_MONTHS -> "每 ${value.interval} 个月 · ${value.anchorDayOfMonth} 日锚点"
}
