package com.yuezhou.reader.ui.online

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ProbeStatus
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ZLibraryConfig

/** 手动模式：固定地址、保存、测试连接及状态；失败不自动切换。 */
@Composable
fun ManualServerCard(
    config: ZLibraryConfig,
    probeResults: Map<String, ProbeResult>,
    onSave: (String) -> Unit,
    onTest: (String, String) -> Unit,
) {
    var text by remember(config.servers.manualBaseUrl) { mutableStateOf(config.servers.manualBaseUrl) }
    val result = probeResults["manual"]
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = YuezhouColors.Surface,
        border = BorderStroke(1.dp, YuezhouColors.Divider),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("手动指定", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("HTTPS 服务器地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row {
                TextButton(onClick = { onSave(text.trim().trimEnd('/')) }) { Text("保存") }
                TextButton(onClick = { onTest(text.trim().trimEnd('/'), "manual") }) { Text("测试连接") }
            }
            result?.let { Text(probeLabel(it), style = MaterialTheme.typography.bodySmall, color = probeColor(it)) }
        }
    }
}

/** 候选服务器列表：增删改、启停、排序、单项检测。 */
@Composable
fun CandidateListCard(
    config: ZLibraryConfig,
    probeResults: Map<String, ProbeResult>,
    selectedBaseUrl: String?,
    busy: Boolean,
    onAdd: () -> Unit,
    onEdit: (ServerCandidate) -> Unit,
    onToggle: (ServerCandidate) -> Unit,
    onMove: (ServerCandidate, Int) -> Unit,
    onDelete: (ServerCandidate) -> Unit,
    onProbe: (ServerCandidate) -> Unit,
    onDetectAll: () -> Unit,
    onReselect: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onReset: () -> Unit,
) {
    val sorted = config.servers.candidates.sortedBy { it.priority }
    val enabledCount = sorted.count { it.enabled }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = YuezhouColors.Surface,
        border = BorderStroke(1.dp, YuezhouColors.Divider),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "候选服务器（$enabledCount/${sorted.size} 已启用）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAdd, enabled = !busy) { Text("+ 添加") }
            }
            if (sorted.isEmpty()) {
                Text("列表为空，请添加地址或恢复默认列表。", style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            } else if (enabledCount == 0) {
                Text("全部候选已禁用，自动模式无法选择服务器。", style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            }
            sorted.forEachIndexed { index, candidate ->
                Spacer(Modifier.height(8.dp))
                CandidateRow(
                    candidate = candidate,
                    position = index + 1,
                    total = sorted.size,
                    result = probeResults[candidate.id],
                    isCurrent = selectedBaseUrl?.equals(candidate.baseUrl, ignoreCase = true) == true,
                    onEdit = { onEdit(candidate) },
                    onToggle = { onToggle(candidate) },
                    onMoveUp = { onMove(candidate, -1) },
                    onMoveDown = { onMove(candidate, 1) },
                    onDelete = { onDelete(candidate) },
                    onProbe = { onProbe(candidate) },
                )
            }
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onDetectAll, enabled = !busy) { Text("检测全部") }
                TextButton(onClick = onReselect, enabled = !busy) { Text("重新选择服务器") }
            }
            Row {
                TextButton(onClick = onImport, enabled = !busy) { Text("批量导入") }
                TextButton(onClick = onExport, enabled = !busy) { Text("导出列表") }
            }
            TextButton(onClick = onReset, enabled = !busy) { Text("恢复内置默认列表") }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: ServerCandidate,
    position: Int,
    total: Int,
    result: ProbeResult?,
    isCurrent: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${position}. ${candidate.baseUrl}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                if (candidate.enabled) "ON" else "OFF",
                style = MaterialTheme.typography.bodySmall,
                color = if (candidate.enabled) YuezhouColors.Brand else YuezhouColors.TextSecondary,
            )
            Switch(checked = candidate.enabled, onCheckedChange = { onToggle() })
        }
        val summary = buildString {
            if (result != null) append(probeLabel(result))
            if (isCurrent) {
                if (isNotEmpty()) append(" · ")
                append("当前使用")
            }
            if (!candidate.enabled && result == null) append("已禁用")
            if (isEmpty()) append("未检测")
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = result?.let { probeColor(it) } ?: YuezhouColors.TextSecondary)
        Row {
            TextButton(onClick = onProbe) { Text("检测") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onMoveUp, enabled = position > 1) { Text("上移") }
            TextButton(onClick = onMoveDown, enabled = position < total) { Text("下移") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

internal fun probeLabel(result: ProbeResult): String = when (ProbeStatus.valueOf(result.status)) {
    ProbeStatus.AVAILABLE -> "可用 · ${result.latencyMs ?: 0}ms"
    ProbeStatus.CHECKING -> "检测中"
    ProbeStatus.TIMEOUT -> "连接超时"
    ProbeStatus.NETWORK_ERROR -> "网络错误"
    ProbeStatus.TLS_ERROR -> "证书错误"
    ProbeStatus.API_INCOMPATIBLE -> "接口不兼容"
    ProbeStatus.CANCELLED -> "已取消"
    ProbeStatus.UNTESTED -> "未检测"
}

@Composable
internal fun probeColor(result: ProbeResult) = when (ProbeStatus.valueOf(result.status)) {
    ProbeStatus.AVAILABLE -> YuezhouColors.Brand
    ProbeStatus.TIMEOUT, ProbeStatus.NETWORK_ERROR, ProbeStatus.TLS_ERROR,
    ProbeStatus.API_INCOMPATIBLE -> androidx.compose.material3.MaterialTheme.colorScheme.error
    else -> YuezhouColors.TextSecondary
}
