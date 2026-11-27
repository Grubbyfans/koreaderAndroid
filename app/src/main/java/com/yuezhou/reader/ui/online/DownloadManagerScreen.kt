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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.provider.CloudBookProvider
import com.yuezhou.reader.core.online.download.DownloadStatus
import com.yuezhou.reader.core.online.download.OnlineDownloadTask
import com.yuezhou.reader.reader.online.upload.CloudUploadBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class DownloadFilter(val label: String) {
    ALL("全部"), ACTIVE("下载中"), DONE("已完成"), FAILED("失败"),
}

/** 统一下载管理页（阶段 E）：任务进度、取消/重试/阅读与同名覆盖确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(
    vm: OnlineLibraryViewModel,
    onBack: () -> Unit,
    onOpenBook: suspend (String) -> Boolean,
) {
    val tasks by vm.downloadManager.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf(DownloadFilter.ALL) }
    var confirmTask by remember { mutableStateOf<OnlineDownloadTask?>(null) }
    // 阶段 B：外部下载无法从文件确定书名时，由用户确认/修改文件名后再导入。
    var nameTask by remember { mutableStateOf<OnlineDownloadTask?>(null) }
    var nameInput by remember { mutableStateOf("") }

    // 阶段 F：手动上传只允许从已完成任务发起；两个云端分别选择、分别记结果。
    val context = LocalContext.current
    val webDownloads = remember(context) { DownloadManagerHolder.runtime(context).webDownloads }
    val webTasks by webDownloads.tasks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var uploadTask by remember { mutableStateOf<OnlineDownloadTask?>(null) }
    var uploadTargets by remember { mutableStateOf(CloudUploadBridge.Target.entries.toSet()) }
    var uploading by remember { mutableStateOf(false) }
    var uploadReport by remember { mutableStateOf<CloudUploadBridge.Report?>(null) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var openingBook by remember { mutableStateOf(false) }
    var missingBook by remember { mutableStateOf(false) }

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
                    TextButton(onClick = {
                        vm.downloadManager.clearHistory()
                    }) { Text("清空历史") }
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

            val webTaskIds = webTasks.map { it.taskId }.toSet()
            val visible = (tasks + webTasks.filter { web ->
                tasks.none { it.taskId == web.taskId }
            }).filter { task ->
                when (filter) {
                    DownloadFilter.ALL -> true
                    DownloadFilter.ACTIVE -> task.status.active
                    DownloadFilter.DONE -> task.status == DownloadStatus.COMPLETED
                    DownloadFilter.FAILED -> task.status == DownloadStatus.FAILED ||
                        task.status == DownloadStatus.IMPORT_FAILED ||
                        task.status == DownloadStatus.AWAITING_NAME ||
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
                            onCancel = {
                                if (task.taskId in webTaskIds) webDownloads.cancel(task.taskId)
                                else vm.downloadManager.cancel(task.taskId)
                            },
                            onRetry = {
                                if (task.errorMessage?.contains("同名") == true) {
                                    confirmTask = task
                                } else {
                                    vm.downloadManager.retry(task.taskId)
                                }
                            },
                            onRead = task.localBookId?.takeIf { task.status == DownloadStatus.COMPLETED }?.let { id ->
                                {
                                    if (!openingBook) {
                                        openingBook = true
                                        scope.launch {
                                            try {
                                                missingBook = !onOpenBook(id)
                                            } finally {
                                                openingBook = false
                                            }
                                        }
                                    }
                                }
                            },
                            onName = if (task.status == DownloadStatus.AWAITING_NAME) {
                                {
                                    nameInput = ""
                                    nameTask = task
                                }
                            } else {
                                null
                            },
                            onUpload = task.localBookId?.takeIf { vm.registry.get(task.providerId) !is CloudBookProvider }?.let {
                                {
                                    uploadError = null
                                    uploadReport = null
                                    uploadTargets = CloudUploadBridge.Target.entries.toSet()
                                    uploadTask = task
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (missingBook) {
        AlertDialog(
            onDismissRequest = { missingBook = false },
            title = { Text("无法打开书籍") },
            text = { Text("本地找不到这本书，文件可能已移除。可回到书源重新下载。") },
            confirmButton = { TextButton(onClick = { missingBook = false }) { Text("关闭") } },
        )
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
                TextButton(onClick = {
                    // 同名冲突只提供覆盖与取消：取消时清理暂存文件与交接回执，不自动改名或保留两份。
                    vm.downloadManager.cancel(task.taskId)
                    confirmTask = null
                }) { Text("取消并清理") }
            },
        )
    }

    val pendingName = nameTask
    if (pendingName != null) {
        AlertDialog(
            onDismissRequest = { nameTask = null },
            title = { Text("确认文件名") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text("无法从下载文件确定有效书名，请输入完整文件名（.epub 可省略）。")
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        singleLine = true,
                        label = { Text("文件名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "不会自动追加序号，也不会保留两份；若同名，导入前会再次要求确认覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = nameInput.isNotBlank(),
                    onClick = {
                        val target = pendingName
                        val name = nameInput
                        nameTask = null
                        scope.launch { vm.downloadManager.confirmName(target.taskId, name) }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.downloadManager.cancel(pendingName.taskId)
                    nameTask = null
                }) { Text("取消并清理") }
            },
        )
    }

    val pendingUpload = uploadTask
    if (pendingUpload != null && !uploading && uploadReport == null && uploadError == null) {
        AlertDialog(
            onDismissRequest = { uploadTask = null },
            title = { Text("上传云端") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Text("「${pendingUpload.fileName}」将上传所选云端；不覆盖本地书籍，也不修改阅读进度。")
                    CloudUploadBridge.Target.entries.forEach { target ->
                        val checked = target in uploadTargets
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(value = checked, role = Role.Checkbox) { enabled ->
                                    uploadTargets = if (enabled) uploadTargets + target else uploadTargets - target
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(target.label)
                        }
                    }
                    Text("未配置的云端会跳过，另一侧照常上传。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = uploadTargets.isNotEmpty(),
                    onClick = {
                        val bookId = pendingUpload.localBookId ?: return@TextButton
                        val selected = uploadTargets
                        uploadTask = null
                        uploading = true
                        scope.launch {
                            try {
                                uploadReport = CloudUploadBridge.from(context, bookId).upload(selected.toList())
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                uploadError = e.message ?: "上传失败"
                            } finally {
                                uploading = false
                            }
                        }
                    },
                ) { Text("开始上传") }
            },
            dismissButton = { TextButton(onClick = { uploadTask = null }) { Text("取消") } },
        )
    }

    if (uploading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("上传云端") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("正在上传，请勿关闭应用。")
                }
            },
            confirmButton = {},
        )
    }

    uploadReport?.let { report ->
        AlertDialog(
            onDismissRequest = { uploadReport = null },
            title = { Text(if (report.success) "上传完成" else "上传未完成") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    report.results.forEach { result ->
                        Text("${result.target.label}：${uploadStatusLabel(result.status)}")
                        Text(result.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { uploadReport = null }) { Text("关闭") } },
        )
    }

    uploadError?.let { error ->
        AlertDialog(
            onDismissRequest = { uploadError = null },
            title = { Text("上传失败") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { uploadError = null }) { Text("关闭") } },
        )
    }
}

private fun uploadStatusLabel(status: CloudUploadBridge.Status): String = when (status) {
    CloudUploadBridge.Status.UPLOADED -> "已上传"
    CloudUploadBridge.Status.FAILED -> "失败"
    CloudUploadBridge.Status.SKIPPED -> "已跳过"
}

@Composable
private fun DownloadTaskRow(
    task: OnlineDownloadTask,
    onCancel: () -> Unit,
    onRetry: (() -> Unit)?,
    onRead: (() -> Unit)?,
    onUpload: (() -> Unit)?,
    onName: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onRead != null) Modifier.clickable(onClick = onRead) else Modifier)
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
            var expanded by remember(task.taskId, message) { mutableStateOf(false) }
            com.yuezhou.reader.ui.components.StatusDetail("! 任务异常", message, true, expanded) { expanded = !expanded }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (task.status.active || task.status == DownloadStatus.AWAITING_NAME) {
                TextButton(onClick = onCancel) { Text("取消") }
            }
            onName?.let {
                Button(onClick = it) { Text("命名") }
            }
            if (onRetry != null && (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.IMPORT_FAILED)) {
                Button(onClick = onRetry) {
                    Text(if (task.status == DownloadStatus.IMPORT_FAILED) "重新导入" else "重试下载")
                }
            }
            onRead?.let {
                Button(onClick = it) { Text("打开阅读") }
            }
            if (task.status == DownloadStatus.COMPLETED) {
                onUpload?.let {
                    TextButton(onClick = it) { Text("上传云端") }
                }
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
    DownloadStatus.AWAITING_NAME -> "待命名"
    DownloadStatus.IMPORT_FAILED -> "已下载 · 导入失败"
    DownloadStatus.FAILED -> "任务失败"
    DownloadStatus.CANCELLED -> "已取消"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
    else -> "${bytes}B"
}
