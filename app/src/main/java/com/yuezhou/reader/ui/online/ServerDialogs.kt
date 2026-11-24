package com.yuezhou.reader.ui.online

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ServerUrlPolicy

/** 新增/编辑候选弹窗：地址输入、启用开关、取消、保存。保存后由调用方清空探测状态标记为未检测。 */
@Composable
fun CandidateEditorDialog(
    title: String,
    initial: ServerCandidate,
    onDismiss: () -> Unit,
    onSave: (ServerCandidate) -> Unit,
) {
    var url by remember { mutableStateOf(initial.baseUrl) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    val error = ServerUrlPolicy.validate(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("HTTPS 地址（不含路径）") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = url.isNotBlank() && error != null,
                )
                if (url.isNotBlank() && error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("启用该候选")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(baseUrl = url.trim().trimEnd('/'), enabled = enabled))
            }, enabled = error == null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 导入预览弹窗：展示新增/重复/无效项，用户确认合并或替换；替换需二次确认。 */
@Composable
fun ImportPreviewDialog(
    preview: ServerImportPreview,
    replace: Boolean,
    onReplaceChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var confirmReplace by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmReplace) "确认替换列表" else "导入预览") },
        text = {
            Column {
                Text("新增 ${preview.added.size} 项 · 重复 ${preview.duplicates.size} 项 · 无效 ${preview.invalid.size} 项")
                if (preview.added.isNotEmpty()) {
                    Text("新增：${preview.added.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                }
                if (preview.invalid.isNotEmpty()) {
                    Text(
                        "无效：${preview.invalid.joinToString("、") { (url, reason) -> "$url（$reason）" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (confirmReplace) {
                    Spacer(Modifier.height(8.dp))
                    Text("替换会移除当前列表中的候选项，此操作不可撤销（可在配置管理中回滚）。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replace, onCheckedChange = onReplaceChange)
                        Text("替换当前列表（默认合并）")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (replace && !confirmReplace) confirmReplace = true else onConfirm()
            }) { Text(if (replace && !confirmReplace) "继续" else "确认导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
