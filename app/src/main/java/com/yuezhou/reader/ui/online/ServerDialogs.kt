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

/**
 * 新增/编辑共用弹窗（服务器设置优化 §6）：
 * 只输入服务器地址，校验格式、处理首尾空格与末尾斜杠；新增默认启用，启用状态由列表开关管理。
 * 保存后由调用方清空该地址检测缓存并标记为未检测。
 */
@Composable
fun CandidateEditorDialog(
    title: String,
    initial: ServerCandidate,
    onDismiss: () -> Unit,
    onSave: (ServerCandidate) -> Unit,
) {
    var url by remember { mutableStateOf(initial.baseUrl) }
    val error = ServerUrlPolicy.validate(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址（https://）") },
                    placeholder = { Text("https://server.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = url.isNotBlank() && error != null,
                )
                if (url.isNotBlank() && error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(initial.copy(baseUrl = url.trim().trimEnd('/'))) },
                enabled = error == null,
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除服务器二次确认（服务器设置优化 §7.1）。 */
@Composable
fun ConfirmDeleteDialog(
    candidate: ServerCandidate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除服务器") },
        text = { Text("确定删除 ${candidate.baseUrl} 吗？此操作不可撤销。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 恢复默认服务器列表二次确认（§10）。 */
@Composable
fun ConfirmResetDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复默认服务器列表") },
        text = { Text("将清空当前所有服务器地址（不写入占位地址），不影响账号及其他书源设置。该操作可回滚。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认恢复") } },
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
                    Text("替换会移除当前列表中的服务器，此操作不可撤销（可在配置管理中回滚）。", style = MaterialTheme.typography.bodySmall)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text("默认合并：保留现有项，仅追加新地址。", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replace, onCheckedChange = onReplaceChange)
                        Text("替换当前列表")
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
