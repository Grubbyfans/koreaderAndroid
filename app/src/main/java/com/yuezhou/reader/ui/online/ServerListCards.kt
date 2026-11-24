package com.yuezhou.reader.ui.online

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ProbeStatus
import com.yuezhou.reader.core.online.config.ServerCandidate

/**
 * 统一服务器列表（服务器设置优化 §5）：自动/手动共用同一份列表。
 *
 * - 手动模式行首单选；自动模式用 ⋮ 内的上移/下移替代拖动排序。
 * - 行内只展示地址、状态、当前使用、启用开关与 ⋮；低频操作收进 ⋮ 菜单。
 * 响应耗时与检测时间均来自实际探测结果（ProbeResult），不使用模拟值。
 */
@Composable
fun ServerListCard(
    candidates: List<ServerCandidate>,
    probeResults: Map<String, ProbeResult>,
    selectedBaseUrl: String?,
    selectedId: String?,
    manualMode: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
    onSelect: (ServerCandidate) -> Unit,
    onToggle: (ServerCandidate) -> Unit,
    onMove: (ServerCandidate, Int) -> Unit,
    onEdit: (ServerCandidate) -> Unit,
    onDelete: (ServerCandidate) -> Unit,
    onProbe: (ServerCandidate) -> Unit,
) {
    val sorted = candidates.sortedBy { it.priority }
    val enabledCount = sorted.count { it.enabled }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = YuezhouColors.Surface,
        border = BorderStroke(1.dp, YuezhouColors.Divider),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "服务器列表（$enabledCount/${sorted.size} 已启用）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAdd, enabled = enabled) { Text("+ 添加") }
            }

            when {
                sorted.isEmpty() -> Text(
                    "暂无服务器，请添加服务器地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
                enabledCount == 0 -> Text(
                    "没有已启用的服务器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
            }

            sorted.forEachIndexed { index, candidate ->
                ServerRow(
                    candidate = candidate,
                    position = index + 1,
                    total = sorted.size,
                    result = probeResults[candidate.id],
                    isCurrent = isCurrent(candidate, selectedBaseUrl, selectedId, manualMode),
                    manualMode = manualMode,
                    enabled = enabled,
                    onSelect = { onSelect(candidate) },
                    onToggle = { onToggle(candidate) },
                    onMoveUp = { onMove(candidate, -1) },
                    onMoveDown = { onMove(candidate, 1) },
                    onEdit = { onEdit(candidate) },
                    onDelete = { onDelete(candidate) },
                    onProbe = { onProbe(candidate) },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    candidate: ServerCandidate,
    position: Int,
    total: Int,
    result: ProbeResult?,
    isCurrent: Boolean,
    manualMode: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onProbe: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = statusSummary(candidate, result, isCurrent)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && manualMode && candidate.enabled) { onSelect() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 手动模式：单选控件；自动模式：优先级序号（排序在 ⋮ 菜单内）。
        if (manualMode) {
            RadioButton(
                selected = isCurrent,
                onClick = onSelect,
                enabled = enabled && candidate.enabled,
                modifier = Modifier.size(36.dp),
            )
        } else {
            Text(
                "$position",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
                modifier = Modifier.width(24.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                candidate.baseUrl,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status.text,
                style = MaterialTheme.typography.bodySmall,
                color = status.color,
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = candidate.enabled,
            onCheckedChange = { onToggle() },
            enabled = enabled,
        )
        androidx.compose.material3.IconButton(onClick = { menuOpen = true }, enabled = enabled) {
            Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("检测此服务器") },
                onClick = { menuOpen = false; onProbe() },
            )
            DropdownMenuItem(
                text = { Text("编辑地址") },
                onClick = { menuOpen = false; onEdit() },
            )
            if (!manualMode) {
                DropdownMenuItem(
                    text = { Text("上移") },
                    onClick = { menuOpen = false; onMoveUp() },
                    enabled = position > 1,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text("下移") },
                    onClick = { menuOpen = false; onMoveDown() },
                    enabled = position < total,
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                )
            }
            DropdownMenuItem(
                text = { Text("删除服务器") },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}

private fun isCurrent(
    candidate: ServerCandidate,
    selectedBaseUrl: String?,
    selectedId: String?,
    manualMode: Boolean,
): Boolean = if (manualMode) {
    selectedId == candidate.id
} else {
    selectedBaseUrl?.equals(candidate.baseUrl, ignoreCase = true) == true
}

private data class StatusText(val text: String, val color: androidx.compose.ui.graphics.Color)

@Composable
private fun statusSummary(
    candidate: ServerCandidate,
    result: ProbeResult?,
    isCurrent: Boolean,
): StatusText {
    if (!candidate.enabled) {
        return StatusText("已禁用", YuezhouColors.TextSecondary)
    }
    val probe = result?.let { runCatching { ProbeStatus.valueOf(it.status) }.getOrNull() }
    val latencyText = "${result?.latencyMs ?: 0}ms"
    val base = when (probe) {
        null -> "未检测"
        ProbeStatus.CHECKING -> "检测中"
        ProbeStatus.AVAILABLE -> "可用 · $latencyText"
        ProbeStatus.TIMEOUT -> "连接超时"
        ProbeStatus.NETWORK_ERROR, ProbeStatus.TLS_ERROR, ProbeStatus.CANCELLED -> "连接失败"
        ProbeStatus.API_INCOMPATIBLE -> "接口异常"
        ProbeStatus.UNTESTED -> "未检测"
    }
    val color = when (probe) {
        ProbeStatus.AVAILABLE -> YuezhouColors.Brand
        ProbeStatus.TIMEOUT, ProbeStatus.NETWORK_ERROR, ProbeStatus.TLS_ERROR,
        ProbeStatus.API_INCOMPATIBLE, ProbeStatus.CANCELLED -> MaterialTheme.colorScheme.error
        else -> YuezhouColors.TextSecondary
    }
    return StatusText(
        if (isCurrent) "$base · 当前使用" else base,
        color,
    )
}

internal fun probeLabel(result: ProbeResult): String = when (runCatching { ProbeStatus.valueOf(result.status) }.getOrNull()) {
    ProbeStatus.AVAILABLE -> "可用 · ${result.latencyMs ?: 0}ms"
    ProbeStatus.CHECKING -> "检测中"
    ProbeStatus.TIMEOUT -> "连接超时"
    ProbeStatus.NETWORK_ERROR, ProbeStatus.TLS_ERROR, ProbeStatus.CANCELLED -> "连接失败"
    ProbeStatus.API_INCOMPATIBLE -> "接口异常"
    ProbeStatus.UNTESTED, null -> "未检测"
}

@Composable
internal fun probeColor(result: ProbeResult) = when (runCatching { ProbeStatus.valueOf(result.status) }.getOrNull()) {
    ProbeStatus.AVAILABLE -> YuezhouColors.Brand
    ProbeStatus.TIMEOUT, ProbeStatus.NETWORK_ERROR, ProbeStatus.TLS_ERROR,
    ProbeStatus.API_INCOMPATIBLE, ProbeStatus.CANCELLED -> MaterialTheme.colorScheme.error
    else -> YuezhouColors.TextSecondary
}
