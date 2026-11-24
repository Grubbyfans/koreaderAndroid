package com.yuezhou.reader.ui.online

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.download.DownloadStatus
import com.yuezhou.reader.core.online.download.OnlineDownloadTask

private enum class DownloadFilter(val label: String) {
    ALL("全部"), ACTIVE("下载中"), DONE("已完成"), FAILED("失败"),
}

/** 统一下载管理页（阶段 E）：任务进度、取消/重试/阅读与同名覆盖确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    vm: OnlineLibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenLegacyDownloads: () -> Unit,
) {
    val tasks by vm.downloadManager.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var confirmTask by remember { mutableStateOf<OnlineDownloadTask?>(null) }

    Scaffold(
        containerColor = YuezhouColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("下载管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.downloadManager.clearHistory() }) { Text("清空历史") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = YuezhouColors.Surface,
                    titleContentColor = YuezhouColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                DownloadFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { filter = option },
                        label = { Text(option.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

            val visible = tasks.filter { task ->
                when (filter) {
                    DownloadFilter.ALL -> true
                    DownloadFilter.ACTIVE -> task.status.active
                    DownloadFilter.DONE -> task.status == DownloadStatus.COMPLETED
                    DownloadFilter.FAILED -> task.status == DownloadStatus.FAILED ||
                        task.status == DownloadStatus.IMPORT_FAILED ||
                        task.status == DownloadStatus.CANCELLED
                }
            }

            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("暂无符合条件的下载任务", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onOpenLegacyDownloads) { Text("查看云端下载任务") }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    items(visible, key = { it.taskId }) { task ->
                        DownloadTaskRow(
                            task = task,
                            onCancel = { vm.downloadManager.cancel(task.taskId) },
                            onRetry = {
                                if (task.status == DownloadStatus.IMPORT_FAILED &&
                                    task.errorMessage?.contains("同名") == true
                                ) {
                                    confirmTask = task
                                } else {
                                    vm.downloadManager.retry(task.taskId)
                                }
                            },
                            onRead = task.localBookId?.let { id -> { onOpenBook(id) } },
                        )
                    }
                    item {
                        TextButton(onClick = onOpenLegacyDownloads) { Text("查看云端下载任务") }
                    }
                }
            }
        }
    }

    confirmTask?.let { task ->
        AlertDialog(
            onDismissRequest = { confirmTask = null },
            title = { Text("书籍已存在") },
            text = {
                Text(
                    "本地书库中已存在同名书籍「${task.fileName}」，是否覆盖？" +
                        "覆盖将替换原有书籍文件，并保留原有阅读进度和书签。如果新旧书籍版本不同，阅读位置可能存在偏差。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.downloadManager.retry(task.taskId, overwriteApproved = true)
                    confirmTask = null
                }) { Text("确认覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTask = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DownloadTaskRow(
    task: OnlineDownloadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRead: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (task.status == DownloadStatus.COMPLETED) onRead?.invoke() }
            .padding(12.dp),
    ) {
        Text(task.bookTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${task.fileName} · ${statusLabel(task.status)}",
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
        )
        if (task.status.active) {
            Spacer(Modifier.height(6.dp))
            val total = task.totalBytes ?: 0L
            LinearProgressIndicator(
                progress = {
                    if (total > 0) (task.receivedBytes.toFloat() / total).coerceIn(0f, 1f) else 0f
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (total > 0) "${formatBytes(task.receivedBytes)} / ${formatBytes(total)}" else "已下载 ${formatBytes(task.receivedBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
        }
        task.errorMessage?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (task.status.active) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
            if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.IMPORT_FAILED) {
                Button(onClick = onRetry) { Text(if (task.status == DownloadStatus.IMPORT_FAILED) "重试导入" else "重试") }
            }
            onRead?.let {
                TextButton(onClick = it) { Text("阅读") }
            }
        }
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.PREPARING -> "准备中"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.VERIFYING -> "校验中"
    DownloadStatus.IMPORTING -> "导入中"
    DownloadStatus.COMPLETED -> "已导入"
    DownloadStatus.IMPORT_FAILED -> "导入失败"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELLED -> "已取消"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
    else -> "${bytes}B"
}
