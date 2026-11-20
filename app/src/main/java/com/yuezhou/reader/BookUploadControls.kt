package com.yuezhou.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.CloudSync.BookUploadProgress
import com.yuezhou.reader.reader.CloudSync.UploadStatus
import com.yuezhou.reader.reader.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Tied to the bookshelf rather than the settings tab so uploads continue across tab switches. */
internal class BookUploadQueue(private val sync: CloudSync, private val scope: CoroutineScope) {
    class Task(val title: String) {
        val progress = MutableStateFlow(BookUploadProgress(UploadStatus.Queued))
    }

    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun start(selection: CloudSync.UploadSelection, books: List<LibraryStore.Entry>) {
        if (busy || books.isEmpty()) return
        val byId = books.associate { it.bookId to Task(it.title) }
        tasks = byId.values.toList()
        runTask {
            sync.pushLocal(selection.backendId, books) { id, progress ->
                byId.getValue(id).progress.value = progress
            }.message
        }
    }

    fun repair() {
        runTask {
            val count = sync.repairPendingPublications()
            if (count > 0) "已补全 $count 本" else "无待补全记录"
        }
    }

    private fun runTask(action: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                message = action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "操作失败"
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun BookUploadControls(sync: CloudSync, queue: BookUploadQueue, cloudLoading: Boolean) {
    val scope = rememberCoroutineScope()
    var showSelection by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<CloudSync.UploadSelection?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showTasks by remember { mutableStateOf(false) }

    Row {
        TextButton(enabled = !queue.busy && !cloudLoading && !showSelection, onClick = {
            selection = null
            loadError = null
            showSelection = true
            scope.launch {
                try {
                    selection = sync.uploadSelection()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loadError = e.message ?: "无法读取云端书库"
                }
            }
        }) { Text("立即同步") }
        TextButton(onClick = { showTasks = true }) { Text("上传任务") }
        TextButton(enabled = !queue.busy && !cloudLoading, onClick = queue::repair) {
            Text("补全待发布")
        }
    }
    queue.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    if (showSelection) {
        val loaded = selection
        if (loaded != null) {
            UploadSelectionDialog(
                selection = loaded,
                onDismiss = { showSelection = false },
                onUpload = { books ->
                    queue.start(loaded, books)
                    showSelection = false
                    showTasks = true
                },
            )
        } else {
            val error = loadError
            AlertDialog(
                onDismissRequest = { if (error != null) showSelection = false },
                title = { Text("选择上传书籍") },
                text = { if (error == null) CircularProgressIndicator() else Text(error) },
                confirmButton = {
                    if (error != null) TextButton(onClick = { showSelection = false }) { Text("关闭") }
                },
            )
        }
    }
    if (showTasks) {
        AlertDialog(
            onDismissRequest = { showTasks = false },
            title = { Text("上传任务") },
            text = {
                if (queue.tasks.isEmpty()) {
                    Text("暂无上传任务")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(queue.tasks) { task -> UploadTaskRow(task) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTasks = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun UploadSelectionDialog(
    selection: CloudSync.UploadSelection,
    onDismiss: () -> Unit,
    onUpload: (List<LibraryStore.Entry>) -> Unit,
) {
    var selected by remember(selection) { mutableStateOf(emptySet<String>()) }
    val groups = remember(selection) {
        val (synced, unsynced) = selection.books.partition { it.bookId in selection.syncedIds }
        listOf("未同步" to unsynced, "已同步" to synced)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择上传书籍") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selection.backendLabel)
                if (selection.books.isEmpty()) {
                    Text("本地书架没有可上传的书籍")
                } else {
                    Text("按云端书库中的书名和大小分组。同名上传会覆盖云端正文。")
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        for ((label, books) in groups) {
                            item { Text("$label（${books.size}）", style = MaterialTheme.typography.titleSmall) }
                            items(books, key = { it.bookId }) { book ->
                                val checked = book.bookId in selected
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .toggleable(value = checked, role = Role.Checkbox) { enabled ->
                                            selected = if (enabled) selected + book.bookId else selected - book.bookId
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                    Text(book.title)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onUpload(selection.books.filter { it.bookId in selected }) },
            ) { Text("上传（${selected.size}）") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun UploadTaskRow(task: BookUploadQueue.Task) {
    val progress by task.progress.collectAsState()
    val label = when (progress.status) {
        UploadStatus.Queued -> "排队中"
        UploadStatus.Preparing -> "预上传"
        UploadStatus.Uploading -> "上传中"
        UploadStatus.Publishing -> "发布书库"
        UploadStatus.Done -> "已完成"
        UploadStatus.Failed -> "上传失败"
        UploadStatus.Uncertain -> "结果未知，请检查云端"
        UploadStatus.PendingPublication -> "正文已上传，待补全发布"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(task.title, style = MaterialTheme.typography.bodyMedium)
        val percent = progress.percent
        Text(if (percent == null) label else "$label $percent%", style = MaterialTheme.typography.bodySmall)
        if (percent != null) {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
        progress.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
