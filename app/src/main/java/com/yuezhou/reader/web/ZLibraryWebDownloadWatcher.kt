package com.yuezhou.reader.web

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.yuezhou.reader.core.online.download.OnlineDownloadTask
import com.yuezhou.reader.core.online.download.DownloadStatus
import androidx.core.content.ContextCompat
import com.yuezhou.reader.reader.online.download.BookDownloadManager
import com.yuezhou.reader.reader.online.download.DownloadManagerGateway
import com.yuezhou.reader.reader.online.download.ExternalAcceptResult
import com.yuezhou.reader.reader.online.download.ExternalDownloadReceipt
import com.yuezhou.reader.reader.online.download.SystemDownloadState
import com.yuezhou.reader.reader.online.download.WebDownloadRecord
import com.yuezhou.reader.reader.online.download.WebDownloadRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * 主进程观察网页进程发起的系统下载（阶段 D）。
 *
 * 完成判定只以 DownloadManager 查询为准：广播/Provider 登记只用于尽快触发查询，不作为成功依据。
 * 重复广播、重复登记通过 downloadId 去重；进程死亡后由 [recover] 扫描共享登记文件继续对账。
 */
class ZLibraryWebDownloadWatcher(
    private val context: Context,
    private val manager: BookDownloadManager,
    private val registry: WebDownloadRegistry,
    private val gateway: DownloadManagerGateway,
    private val scope: CoroutineScope,
) {
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private val resolving = ConcurrentHashMap.newKeySet<Long>()
    private var pollJob: Job? = null
    private val downloads = MutableStateFlow<List<OnlineDownloadTask>>(emptyList())
    val tasks = downloads.asStateFlow()

    fun cancel(taskId: String) {
        scope.launch {
            registry.readAll().firstOrNull { BookDownloadManager.taskIdFor(it.transferId) == taskId }?.let { record ->
                gateway.remove(record.downloadId)
                resolve(record.downloadId)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (downloadId > 0L) scope.launch { resolve(downloadId) }
        }
    }

    fun start() {
        runCatching {
            // DownloadManager 的完成广播来自系统 DownloadProvider；Android 14+ 用
            // RECEIVER_NOT_EXPORTED 会收不到。广播里只有 downloadId，完成与否仍以
            // gateway.query 的结果为准，伪造广播最多触发一次无害查询。
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
        scope.launch { recover() }
    }

    /** Provider 登记：只表示「开始观察」，立即查询一次当前状态。 */
    fun onEnqueued(downloadId: Long) {
        if (downloadId <= 0L) return
        pending += downloadId
        scope.launch { resolve(downloadId) }
    }

    /** 启动/重启对账：扫描共享登记文件，按系统状态继续观察或记录失败。 */
    suspend fun recover() {
        registry.readAll().forEach { record ->
            pending += record.downloadId
            resolve(record.downloadId)
        }
        ensurePolling()
    }

    private suspend fun resolve(downloadId: Long) {
        if (!resolving.add(downloadId)) return
        try {
            val record = registry.read(downloadId) ?: run {
                pending -= downloadId
                return
            }
            when (val state = gateway.query(downloadId)) {
                is SystemDownloadState.Ready -> finishSuccess(record, state)
                is SystemDownloadState.Failed -> finishFailure(record, "下载失败：${state.reason}")
                SystemDownloadState.Unknown -> finishFailure(record, "下载已取消或文件已被移除，请重新发起下载")
                SystemDownloadState.Pending -> {
                    showProgress(record, 0L, record.expectedBytes)
                    pending += downloadId
                    ensurePolling()
                }
                is SystemDownloadState.Running -> {
                    showProgress(record, state.receivedBytes, state.totalBytes)
                    pending += downloadId
                    ensurePolling()
                }
            }
        } finally {
            resolving -= downloadId
        }
    }

    private fun showProgress(record: WebDownloadRecord, received: Long, total: Long?) {
        val task = OnlineDownloadTask(
            taskId = BookDownloadManager.taskIdFor(record.transferId),
            providerId = "zlibrary-web",
            accountScope = record.accountScope,
            remoteId = record.remoteRef.orEmpty(),
            bookTitle = record.suggestedFileName ?: "网页下载",
            fileName = record.suggestedFileName.orEmpty(),
            status = DownloadStatus.DOWNLOADING,
            receivedBytes = received,
            totalBytes = total,
            createdAt = record.createdAt,
        )
        downloads.update { current -> current.filterNot { it.taskId == task.taskId } + task }
    }

    private fun removeProgress(record: WebDownloadRecord) {
        downloads.update { tasks -> tasks.filterNot { it.taskId == BookDownloadManager.taskIdFor(record.transferId) } }
    }

    private suspend fun finishSuccess(record: WebDownloadRecord, state: SystemDownloadState.Ready) {
        val receipt = ExternalDownloadReceipt(
            transferId = record.transferId,
            contentUri = state.contentUri,
            expectedBytes = state.totalBytes ?: record.expectedBytes,
            suggestedFileName = record.suggestedFileName,
            remoteRef = record.remoteRef,
            accountScope = record.accountScope,
            downloadId = record.downloadId,
        )
        when (val result = manager.acceptCompletedDownload(receipt)) {
            is ExternalAcceptResult.Rejected ->
                manager.recordExternalDownloadFailure(
                    record.transferId, record.suggestedFileName, record.remoteRef, record.accountScope,
                    "下载文件未被接受：${result.reason}",
                )
            is ExternalAcceptResult.Failed ->
                manager.recordExternalDownloadFailure(
                    record.transferId, record.suggestedFileName, record.remoteRef, record.accountScope,
                    "下载文件校验失败：${result.reason}",
                )
            is ExternalAcceptResult.Accepted, is ExternalAcceptResult.Duplicate -> Unit
        }
        // 主进程已复制到私有 pending；系统下载及其临时文件不再需要。
        gateway.remove(record.downloadId)
        registry.delete(record.downloadId)
        pending -= record.downloadId
        removeProgress(record)
    }

    private suspend fun finishFailure(record: WebDownloadRecord, reason: String) {
        manager.recordExternalDownloadFailure(
            record.transferId, record.suggestedFileName, record.remoteRef, record.accountScope, reason,
        )
        gateway.remove(record.downloadId)
        registry.delete(record.downloadId)
        pending -= record.downloadId
        removeProgress(record)
    }

    /** 广播可能因进程未启动而丢失：有未决下载时低频兜底轮询，全部处理完自动停止。 */
    private fun ensurePolling() {
        if (pending.isEmpty()) return
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (pending.isNotEmpty()) {
                delay(POLL_INTERVAL_MS)
                pending.toList().forEach { resolve(it) }
            }
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
