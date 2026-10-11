package com.healthtimeline.app.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.healthtimeline.app.domain.ClinicalRecordQuickParser

internal const val QUICK_SOURCE_REQUIRED_MESSAGE =
    "缺少这条事件的原始病程。请点击“补充原文”，粘贴对应原文并确认；软件会自动添加“原文记录：”。"

private val sourceMarker = Regex("原文记录[\\t ]*[：:]")

internal fun hasSourceRecord(value: String): Boolean {
    val marker = sourceMarker.find(value) ?: return false
    return value.substring(marker.range.last + 1).isNotBlank()
}

/** Never prefill AI summaries or the whole batch as the event's original narrative. */
internal fun initialSourceRecordText(notes: String): String {
    val normalized = normalizeSourceNewlines(notes)
    val marker = sourceMarker.find(normalized) ?: return ""
    // Remove only the template's one structural newline, not whitespace in the source.
    return normalized.substring(marker.range.last + 1).removePrefix("\n")
}

private fun normalizeSourceNewlines(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n')

/** Explicit user-supplied original text only; retains existing notes without truncation. */
internal fun completeSourceRecordNotes(notes: String, originalText: String): String {
    val normalized = normalizeSourceNewlines(originalText)
    val leadingMarker = sourceMarker.find(normalized)?.takeIf {
        normalized.substring(0, it.range.first).isBlank()
    }
    val source = if (leadingMarker != null) {
        normalized.substring(0, leadingMarker.range.first) +
            normalized.substring(leadingMarker.range.last + 1).removePrefix("\n")
    } else normalized
    require(source.isNotBlank()) { "请粘贴这条事件对应的原始病程，不能只填写“原文记录：”。" }
    val prefixed = "${ClinicalRecordQuickParser.SOURCE_PREFIX}\n$source"
    val existing = normalizeSourceNewlines(notes)
    val markerOnly = sourceMarker.matchEntire(existing.trim()) != null
    val completed = when {
        existing.isBlank() || markerOnly || existing == source || existing == prefixed -> prefixed
        else -> "$prefixed\n\n补充备注：\n$existing"
    }
    require(completed.length <= ClinicalRecordQuickParser.MAX_NARRATIVE_LENGTH) {
        "原文与已有备注合计超过 10,000 个字符，请分成独立事件后录入；没有截断或删除内容。"
    }
    return completed
}

@Composable
internal fun QuickSourceRecordDialog(
    eventLabel: String,
    originalText: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onError: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("补充原始病程") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(eventLabel, style = MaterialTheme.typography.titleSmall)
                    Text("粘贴这条事件整理前的原始文字，不需要自己填写标签。已有备注会保留；确认补充不会保存病历。")
                    Text("若使用了外部 AI，请从整理前的病程文字中复制对应片段，软件不能重建缺失的原文。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = originalText,
                        onValueChange = onTextChange,
                        label = { Text("这条事件的原始文字") },
                        minLines = 4,
                        maxLines = 8,
                        isError = error != null,
                        supportingText = { Text("${originalText.length} 字；原文与已有备注合计最多 10,000 字") },
                        modifier = Modifier.fillMaxWidth().testTag("quick-source-input")
                    )
                    TextButton(
                        onClick = {
                            // Clipboard access is user-initiated only, with no history or background reads.
                            val pasted = runCatching {
                                context.getSystemService(ClipboardManager::class.java)?.primaryClip
                                    ?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                                    ?.takeIf { it.text != null }?.text?.toString()
                            }.getOrNull()
                            if (pasted.isNullOrBlank()) onError("剪贴板中没有文字，请复制原始病程后重试。")
                            else onTextChange(pasted)
                        },
                        modifier = Modifier.testTag("quick-source-paste")
                    ) { Text("从剪贴板粘贴原文") }
                }
                // Validation stays visible even when a long original scrolls out of view.
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("quick-source-error")) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("quick-source-confirm")) { Text("补充到这条记录") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("quick-source-cancel")) { Text("取消") }
        }
    )
}
