package com.yuezhou.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.speech.KokoroModelInfo
import com.yuezhou.reader.speech.KokoroImportProgress
import com.yuezhou.reader.speech.SpeechDiagnostics

/** Kokoro 模型管理对话框：导入 ZIP/tar.bz2、查看版本/精度/体积/哈希、试音与删除。 */
@Composable
internal fun KokoroModelManagerDialog(
    models: List<KokoroModelInfo>,
    diagnostics: SpeechDiagnostics,
    busy: Boolean,
    progress: KokoroImportProgress?,
    message: String?,
    onImport: () -> Unit,
    onAudition: (KokoroModelInfo) -> Unit,
    onDelete: (KokoroModelInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Kokoro 模型管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (progress?.running == true) {
                    Text(progress.stage, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    val fraction = progress.fraction
                    if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    val detail = buildList {
                        if (fraction != null) add("${(fraction * 100).toInt()}%")
                        if (progress.processedBytes > 0) add("已处理 ${formatSize(progress.processedBytes)}")
                        progress.totalBytes?.let { add("共 ${formatSize(it)}") }
                    }.joinToString(" · ")
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                }
                if (models.isEmpty()) {
                    Text("未部署可用模型。请导入 ZIP 或 tar.bz2 模型包。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    models.forEach { info ->
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(info.label, style = MaterialTheme.typography.titleSmall)
                            val meta = buildList {
                                if (info.version.isNotBlank()) add(info.version)
                                if (info.precision.isNotBlank()) add(info.precision)
                                add(formatSize(info.sizeBytes))
                                if (info.sha256.isNotBlank()) add("sha256 ${info.sha256.take(12)}…")
                            }.joinToString(" · ")
                            Text(meta, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { onAudition(info) }, enabled = !busy) { Text("试音") }
                                TextButton(onClick = { onDelete(info) }, enabled = !busy) { Text("删除") }
                            }
                        }
                    }
                }
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
                if (diagnostics.engine.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("朗读诊断", style = MaterialTheme.typography.titleSmall)
                    val line = buildList {
                        add(diagnostics.engine)
                        if (diagnostics.modelId.isNotBlank()) add(diagnostics.modelId)
                        add("sid=${diagnostics.speakerId}")
                        add("rate=${diagnostics.rate}")
                        if (diagnostics.modelLoadMs >= 0) add("加载=${diagnostics.modelLoadMs}ms")
                        if (diagnostics.firstSoundMs >= 0) add("首声=${diagnostics.firstSoundMs}ms")
                        add("缓冲=${diagnostics.bufferingCount}")
                    }.joinToString(" · ")
                    Text(line, style = MaterialTheme.typography.bodySmall)
                    Text(if (diagnostics.textParserEnabled) "小说解析：开启 · v${diagnostics.plannerVersion}" else "小说解析：关闭（原路径）",
                        style = MaterialTheme.typography.bodySmall)
                    if (diagnostics.deliveryHint.isNotBlank()) {
                        Text("语气：${diagnostics.deliveryHint} · 置信度 ${"%.2f".format(diagnostics.deliveryConfidence)} · 系数 ${diagnostics.speedMultiplier}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("规则：${diagnostics.deliveryRuleVersion} · ${diagnostics.deliveryRuleIds} · 补停顿 ${diagnostics.addedPauseMs}ms",
                            style = MaterialTheme.typography.bodySmall)
                        if (diagnostics.deliveryFallback.isNotBlank()) Text("中性回退：${diagnostics.deliveryFallback}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (diagnostics.healthSummary.isNotBlank()) {
                        Text("音频：${diagnostics.healthSummary}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (diagnostics.lastError.isNotBlank()) {
                        Text("最近错误：${diagnostics.lastError}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport, enabled = !busy) { Text("导入模型") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GiB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.0f MiB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.0f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

