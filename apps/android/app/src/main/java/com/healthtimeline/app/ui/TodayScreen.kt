package com.healthtimeline.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.healthtimeline.app.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class TodayOverviewState(
    val member: FamilyMemberEntity?,
    val date: LocalDate,
    val data: TodayOverviewData?,
    val safetyMessages: List<String> = emptyList(),
    val errorMessage: String? = null
)

/** Read-only presentation: only an explicit, confirmed action reaches the caller. */
@Composable
fun TodayScreen(
    state: TodayOverviewState,
    padding: PaddingValues,
    onSelectMember: (Long) -> Unit,
    members: List<FamilyMemberEntity>,
    onAddRecord: () -> Unit,
    onRecordDose: (MedicationDayEntry, MedicationLogStatus, LocalDateTime?) -> Unit,
    onRecordAsNeeded: (MedicationEntity, LocalDateTime) -> Unit,
    onConfirmFollowUp: (TodayFollowUpItem, Boolean) -> Unit,
    onOpenMedication: () -> Unit,
    onOpenFollowUp: (TodayFollowUpItem) -> Unit,
    onOpenSafety: () -> Unit,
    busy: Boolean = false,
    onRetry: () -> Unit = {}
) {
    // Member/date changes dispose all expansion and confirmation state immediately.
    key(state.member?.id, state.date) {
        val member = state.member?.takeUnless { it.archived }
        val data = state.data?.takeIf { it.memberId == member?.id && it.date == state.date }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp).testTag("today-scroll"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("今日", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(state.date.format(TODAY_DATE_FORMAT), style = MaterialTheme.typography.titleMedium)
            val activeMembers = members.filterNot { it.archived }
            if (activeMembers.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        LabeledDropdown(
                            label = "当前家庭成员",
                            value = member?.id?.toString().orEmpty(),
                            options = activeMembers.map { it.id.toString() to "${it.relationship} · ${it.name}" },
                            onSelect = { it.toLongOrNull()?.let(onSelectMember) }
                        )
                    }
                }
            }
            Button(
                onClick = onAddRecord,
                enabled = member != null && !busy,
                modifier = Modifier.fillMaxWidth().testTag("today-add-record")
            ) { Text("记录今天病情") }
            when {
                member == null -> Text("请先选择一个有效的家庭成员")
                data == null && state.errorMessage != null -> {
                    Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.testTag("today-retry")) {
                        Text("重新加载")
                    }
                }
                data == null -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("today-loading"))
                    Text("正在加载当前成员的今日事项…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> TodayContent(
                    member, state.date, data, state.safetyMessages, busy,
                    onRecordDose, onRecordAsNeeded, onConfirmFollowUp,
                    onOpenMedication, onOpenFollowUp, onOpenSafety
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TodayContent(
    member: FamilyMemberEntity,
    date: LocalDate,
    data: TodayOverviewData,
    safetyMessages: List<String>,
    busy: Boolean,
    onRecordDose: (MedicationDayEntry, MedicationLogStatus, LocalDateTime?) -> Unit,
    onRecordAsNeeded: (MedicationEntity, LocalDateTime) -> Unit,
    onConfirmFollowUp: (TodayFollowUpItem, Boolean) -> Unit,
    onOpenMedication: () -> Unit,
    onOpenFollowUp: (TodayFollowUpItem) -> Unit,
    onOpenSafety: () -> Unit
) {
    var showAllPending by remember { mutableStateOf(false) }
    var showRecorded by remember { mutableStateOf(false) }
    var doseTarget by remember { mutableStateOf<TodayDoseTarget?>(null) }
    var followUpTarget by remember { mutableStateOf<TodayFollowUpTarget?>(null) }
    var asNeededSnapshot by remember { mutableStateOf<List<MedicationEntity>?>(null) }
    var asNeededChanged by remember { mutableStateOf(false) }
    val entries = data.medicationEntries.filter { it.medication.memberId == member.id && it.medication.deletedAt == null }
    val pending = entries.filter { it.status == MedicationDayStatus.UNRECORDED }
        .sortedWith(compareBy<MedicationDayEntry> { it.plannedAt ?: it.actualAt }.thenBy { it.medication.id })
    val recorded = entries.filter { it.status != MedicationDayStatus.UNRECORDED }
        .sortedWith(compareBy<MedicationDayEntry> { it.plannedAt ?: it.actualAt }.thenBy { it.medication.id })
    val asNeeded = data.asNeededMedications.filter { it.memberId == member.id && !courseEnded(it, date) && it.deletedAt == null }
    val overdue = data.overdueFollowUps.filter { it.schedule.memberId == member.id && it.schedule.deletedAt == null }
    val upcoming = data.upcomingFollowUps.filter { it.schedule.memberId == member.id && it.schedule.deletedAt == null }

    // Database updates can invalidate a confirmation while it is open.
    LaunchedEffect(entries, overdue, upcoming, asNeeded) {
        doseTarget?.let { target ->
            if (entries.none { it == target.entry && it.log == null && !courseEnded(it.medication, date) }) doseTarget = null
        }
        followUpTarget?.let { target ->
            if ((overdue + upcoming).none { it == target.item && !it.dueDate.isAfter(date) }) followUpTarget = null
        }
        if (asNeededSnapshot != null && asNeededSnapshot != asNeeded) {
            asNeededSnapshot = null
            asNeededChanged = true
        }
    }

    Text("今日用药", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(
        "已服 ${entries.count { it.status == MedicationDayStatus.TAKEN }} · 跳过 ${entries.count { it.status == MedicationDayStatus.SKIPPED }} · 未记录 ${pending.size}",
        modifier = Modifier.testTag("today-dose-summary")
    )
    if (entries.isEmpty()) Text("今天没有用药计划或记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
    pending.take(if (showAllPending) pending.size else 3).forEach { entry ->
        TodayMedicationCard(
            entry, date, busy,
            onTaken = { doseTarget = TodayDoseTarget(entry, MedicationLogStatus.TAKEN) },
            onSkipped = { doseTarget = TodayDoseTarget(entry, MedicationLogStatus.SKIPPED) },
            onOpen = onOpenMedication
        )
    }
    if (pending.size > 3) {
        TextButton(onClick = { showAllPending = !showAllPending }, modifier = Modifier.testTag("today-pending-expand")) {
            Text(if (showAllPending) "收起待记录用药" else "查看全部待记录用药（${pending.size}）")
        }
    }
    if (recorded.isNotEmpty()) {
        TextButton(onClick = { showRecorded = !showRecorded }, modifier = Modifier.testTag("today-recorded-expand")) {
            Text(if (showRecorded) "收起已记录用药" else "查看已记录用药（${recorded.size}）")
        }
        if (showRecorded) recorded.forEach { entry ->
            TodayMedicationCard(entry, date, busy, {}, {}, onOpenMedication)
        }
    }
    if (asNeeded.isNotEmpty()) {
        OutlinedButton(
            onClick = { asNeededSnapshot = asNeeded; asNeededChanged = false }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("today-as-needed")
        ) { Text("补记按需用药") }
    }
    if (asNeededChanged) {
        Text("按需药物资料已更新，请重新打开补记并核对。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = onOpenMedication, modifier = Modifier.testTag("today-open-medication")) { Text("查看当天用药详情") }
    Text("近期复查", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    TodayFollowUpGroup(
        "逾期未处理", "overdue", overdue, date, busy,
        { followUpTarget = TodayFollowUpTarget(it, false) },
        { followUpTarget = TodayFollowUpTarget(it, true) }, onOpenFollowUp
    )
    TodayFollowUpGroup(
        "近期 7 天", "upcoming", upcoming, date, busy,
        { followUpTarget = TodayFollowUpTarget(it, false) },
        { followUpTarget = TodayFollowUpTarget(it, true) }, onOpenFollowUp
    )
    if (safetyMessages.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("提醒与备份需要检查", fontWeight = FontWeight.SemiBold)
                Text(safetyMessages.joinToString("\n"))
                TextButton(onClick = onOpenSafety, modifier = Modifier.testTag("today-open-safety")) {
                    Text("安全与提醒检查")
                }
            }
        }
    }
    doseTarget?.let { target ->
        TodayDoseConfirmation(member, date, target, busy,
            onConfirm = { actualAt ->
                doseTarget = null
                onRecordDose(target.entry, target.status, actualAt)
            }, onDismiss = { doseTarget = null })
    }
    followUpTarget?.let { target ->
        TodayFollowUpConfirmation(member, target, busy,
            onConfirm = {
                followUpTarget = null
                onConfirmFollowUp(target.item, target.skipped)
            }, onDismiss = { followUpTarget = null })
    }
    // Freeze displayed dose/version until confirmation. Changes close rather than replace it.
    asNeededSnapshot?.takeIf { it == asNeeded && it.isNotEmpty() }?.let { snapshot ->
        TodayAsNeededConfirmation(member, date, snapshot, busy,
            onConfirm = { medication, actualAt ->
                asNeededSnapshot = null
                onRecordAsNeeded(medication, actualAt)
            }, onDismiss = { asNeededSnapshot = null })
    }
}

@Composable
private fun TodayMedicationCard(
    entry: MedicationDayEntry,
    date: LocalDate,
    busy: Boolean,
    onTaken: () -> Unit,
    onSkipped: () -> Unit,
    onOpen: () -> Unit
) {
    val ended = courseEnded(entry.medication, date)
    val modifier = Modifier.fillMaxWidth().testTag("today-dose-${entry.medication.id}-${entry.schedule?.id ?: entry.log?.id ?: 0}")
    ElevatedCard(if (entry.log != null) modifier.clickable(onClick = onOpen) else modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.medication.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(todayDoseLabel(entry))
            entry.plannedAt?.let { Text("计划时间：${it.toLocalTime().format(TODAY_TIME_FORMAT)}") }
            entry.actualAt?.let { Text("实际时间：${it.toLocalTime().format(TODAY_TIME_FORMAT)}") }
            Text(when (entry.status) {
                MedicationDayStatus.TAKEN -> "已服"
                MedicationDayStatus.SKIPPED -> "已跳过"
                MedicationDayStatus.UNRECORDED -> if (ended) "未记录 · 疗程已结束" else "未记录"
            }, color = MaterialTheme.colorScheme.primary)
            if (entry.log == null && entry.status == MedicationDayStatus.UNRECORDED && !ended) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onTaken, enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("today-taken-${entry.medication.id}-${entry.schedule?.id ?: 0}")
                            .semantics { contentDescription = "记录${entry.medication.name}已服" }
                    ) { Text("已服") }
                    OutlinedButton(
                        onClick = onSkipped, enabled = !busy,
                        modifier = Modifier.weight(1f).testTag("today-skipped-${entry.medication.id}-${entry.schedule?.id ?: 0}")
                            .semantics { contentDescription = "记录${entry.medication.name}跳过" }
                    ) { Text("跳过") }
                }
            } else if (entry.log != null) {
                Text("点击查看或修正当天记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TodayFollowUpGroup(
    title: String,
    tag: String,
    items: List<TodayFollowUpItem>,
    date: LocalDate,
    busy: Boolean,
    onComplete: (TodayFollowUpItem) -> Unit,
    onSkip: (TodayFollowUpItem) -> Unit,
    onOpen: (TodayFollowUpItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Text(title, style = MaterialTheme.typography.titleMedium)
    val unique = items.distinctBy { it.schedule.id to it.dueDate }
        .sortedWith(compareBy<TodayFollowUpItem> { it.dueDate }.thenBy { it.schedule.id })
    if (unique.isEmpty()) Text(if (tag == "overdue") "没有逾期未处理事项" else "近期 7 天没有待处理复查", color = MaterialTheme.colorScheme.onSurfaceVariant)
    unique.take(if (expanded) unique.size else 3).forEach { item ->
        ElevatedCard(Modifier.fillMaxWidth().testTag("today-follow-up-${item.schedule.id}-${item.dueDate}")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.schedule.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("病情分类：${item.conditionName ?: "未分类"}")
                Text("应复查：${item.dueDate}", color = if (item.dueDate.isBefore(date)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(if (item.schedule.leadDays == 0) "当天 ${item.schedule.reminderTime} 提醒" else "提前 ${item.schedule.leadDays} 天 ${item.schedule.reminderTime} 提醒", style = MaterialTheme.typography.bodySmall)
                if (!item.dueDate.isAfter(date)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onComplete(item) }, enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("today-follow-complete-${item.schedule.id}-${item.dueDate}")) { Text("完成") }
                        OutlinedButton(onClick = { onSkip(item) }, enabled = !busy,
                            modifier = Modifier.weight(1f).testTag("today-follow-skip-${item.schedule.id}-${item.dueDate}")) { Text("跳过") }
                    }
                }
                TextButton(onClick = { onOpen(item) }, modifier = Modifier.testTag("today-follow-open-${item.schedule.id}-${item.dueDate}")) { Text("查看详情") }
            }
        }
    }
    if (unique.size > 3) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("today-$tag-expand")) {
            Text(if (expanded) "收起$title" else "查看全部$title（${unique.size}）")
        }
    }
}

@Composable
private fun TodayDoseConfirmation(
    member: FamilyMemberEntity, date: LocalDate, target: TodayDoseTarget, busy: Boolean,
    onConfirm: (LocalDateTime?) -> Unit, onDismiss: () -> Unit
) {
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var showPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!submitting && !busy) onDismiss() },
        title = { Text(if (target.status == MedicationLogStatus.TAKEN) "确认已服？" else "确认跳过？") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${member.relationship} · ${member.name}")
                Text("${target.entry.medication.name} · ${todayDoseLabel(target.entry)}")
                Text("日期：$date")
                if (target.status == MedicationLogStatus.TAKEN) {
                    Text("实际服药时间：${time.format(TODAY_TIME_FORMAT)}")
                    OutlinedButton(onClick = { showPicker = true }, enabled = !submitting && !busy) { Text("调整时间") }
                } else Text("只记录跳过，不填写实际服药时间。")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!submitting && !busy) {
                    submitting = true
                    onConfirm(if (target.status == MedicationLogStatus.TAKEN) date.atTime(time) else null)
                }
            }, enabled = !submitting && !busy, modifier = Modifier.testTag("today-dose-confirm")) { Text("确认记录") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting && !busy, modifier = Modifier.testTag("today-dose-cancel")) { Text("取消") } }
    )
    if (showPicker) MedicationTimePickerDialog(
        title = "选择实际服药时间", initial = time,
        onConfirm = { time = it; showPicker = false }, onDismiss = { showPicker = false }
    )
}

@Composable
private fun TodayFollowUpConfirmation(
    member: FamilyMemberEntity, target: TodayFollowUpTarget, busy: Boolean,
    onConfirm: () -> Unit, onDismiss: () -> Unit
) {
    var submitting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!submitting && !busy) onDismiss() },
        title = { Text(if (target.skipped) "确认跳过复查？" else "确认完成复查？") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${member.relationship} · ${member.name}")
            Text(target.item.schedule.title)
            Text("应复查日期：${target.item.dueDate}")
            Text("确认后将更新这次状态，并计算下一次复查日期。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = {
            if (!submitting && !busy) { submitting = true; onConfirm() }
        }, enabled = !submitting && !busy, modifier = Modifier.testTag("today-follow-confirm")) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting && !busy, modifier = Modifier.testTag("today-follow-cancel")) { Text("取消") } }
    )
}

@Composable
private fun TodayAsNeededConfirmation(
    member: FamilyMemberEntity, date: LocalDate, medications: List<MedicationEntity>, busy: Boolean,
    onConfirm: (MedicationEntity, LocalDateTime) -> Unit, onDismiss: () -> Unit
) {
    var medicationId by remember { mutableStateOf(medications.firstOrNull()?.id) }
    var time by remember { mutableStateOf(LocalTime.now().withSecond(0).withNano(0)) }
    var showPicker by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val medication = medications.firstOrNull { it.id == medicationId }
    AlertDialog(
        onDismissRequest = { if (!submitting && !busy) onDismiss() },
        title = { Text("补记按需用药") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${member.relationship} · ${member.name} · $date")
            LabeledDropdown("药物", medicationId?.toString().orEmpty(), medications.map { it.id.toString() to it.name }, { medicationId = it.toLongOrNull() })
            medication?.let { Text("剂量：${it.doseAmount} ${it.doseUnit}") }
            Text("实际服药时间：${time.format(TODAY_TIME_FORMAT)}")
            OutlinedButton(onClick = { showPicker = true }, enabled = !submitting && !busy) { Text("调整时间") }
        } },
        confirmButton = { TextButton(onClick = {
            if (!submitting && !busy && medication != null) { submitting = true; onConfirm(medication, date.atTime(time)) }
        }, enabled = medication != null && !submitting && !busy, modifier = Modifier.testTag("today-as-needed-confirm")) { Text("确认记录") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !submitting && !busy, modifier = Modifier.testTag("today-as-needed-cancel")) { Text("取消") } }
    )
    if (showPicker) MedicationTimePickerDialog(
        title = "选择实际服药时间", initial = time,
        onConfirm = { time = it; showPicker = false }, onDismiss = { showPicker = false }
    )
}

private data class TodayDoseTarget(val entry: MedicationDayEntry, val status: MedicationLogStatus)
private data class TodayFollowUpTarget(val item: TodayFollowUpItem, val skipped: Boolean)
private val TODAY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINA)
private val TODAY_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun courseEnded(medication: MedicationEntity, date: LocalDate): Boolean =
    medication.archived || medication.endedAt != null || medication.deletedAt != null ||
        (medication.endDate?.let { LocalDate.parse(it).isBefore(date) } == true)

private fun todayDoseLabel(entry: MedicationDayEntry): String = entry.log?.let { "${it.doseAmountSnapshot} ${it.doseUnitSnapshot}" }
    ?: entry.schedule?.let { "${it.doseAmountSnapshot} ${it.doseUnitSnapshot}" }
    ?: "${entry.medication.doseAmount} ${entry.medication.doseUnit}"
