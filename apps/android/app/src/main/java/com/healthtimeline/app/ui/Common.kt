package com.healthtimeline.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun MemberSwitcher(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val members by viewModel.members.collectAsStateWithLifecycle()
    val selectedId by viewModel.selectedMemberId.collectAsStateWithLifecycle()
    val active = members.filterNot { it.archived }
    if (active.isEmpty()) return
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "当前档案",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            LabeledDropdown(
                label = "家庭成员",
                value = selectedId?.toString().orEmpty(),
                options = active.map { it.id.toString() to "${it.nickname} · ${it.name}（${it.relationship}）" },
                onSelect = { it.toLongOrNull()?.let(viewModel::selectMember) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun LabeledDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.first == value }?.second ?: "未选择")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (key, title) ->
                    DropdownMenuItem(
                        text = { Text(title) },
                        onClick = { onSelect(key); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

fun visitStageLabel(value: String) = when (value) {
    "BEFORE_VISIT" -> "就诊前"
    "AFTER_VISIT" -> "就诊后"
    "CHECKUP" -> "检查/复查"
    "SURGERY" -> "手术"
    else -> "其他"
}
