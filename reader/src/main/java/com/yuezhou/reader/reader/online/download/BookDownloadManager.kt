package com.yuezhou.reader.reader.online.download

import android.content.Context
import com.yuezhou.reader.core.online.download.DownloadRetryPolicy
import com.yuezhou.reader.core.online.download.DownloadStatus
import com.yuezhou.reader.core.online.download.OnlineDownloadTask
import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.DownloadResult
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.core.online.provider.CatalogBookProvider
import com.yuezhou.reader.core.online.provider.ProviderRegistry
import com.yuezhou.reader.reader.EpubImportFiles
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.online.import.OnlineImportCoordinator
import com.yuezhou.reader.reader.online.import.OnlineImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 前台保活通知由 app 层注入，reader 层只依赖这个接口。 */
interface DownloadNotifier {
    fun start(title: String) {}
    fun update(title: String, received: Long, total: Long?) {}
    fun stop() {}
}

/**
 * 统一下载管理器（阶段 E）：并发 2、进度节流、安全重试、取消、导入失败独立重试。
 *
 * 有额度限制的书源（Z-Library）在进程重启后不自动重新申请下载；被打断的任务标记为失败，
 * 由用户显式重试。取消与失败只清理临时文件，不触碰已有书籍。
 */
class BookDownloadManager(
    private val context: Context,
    private val registry: ProviderRegistry,
    private val repository: OnlineDownloadRepository = OnlineDownloadRepository(AppDatabase.get(context)),
    private val notifier: DownloadNotifier = object : DownloadNotifier {},
    private val isBookOpen: (String) -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(2)
    private val jobs = mutableMapOf<String, Job>()
    private val lastProgressAt = mutableMapOf<String, Long>()
    private val running = AtomicInteger(0)

    private val coordinator = OnlineImportCoordinator(context)
    val tasks: Flow<List<OnlineDownloadTask>> = repository.observeAll()
    val activeRemoteIds: Flow<Set<String>> = repository.observeActiveRemoteIds()

    /** 应用启动时调用：把中断的任务标记为失败，不自动消耗额度。 */
    suspend fun recoverInterrupted(): Int = withContext(Dispatchers.IO) {
        OnlineFiles.cleanTemporary(context)
        var count = 0
        repository.unfinished().forEach { task ->
            task.localPath?.let { runCatching { File(it).delete() } }
            repository.save(
                task.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "应用重启或进程退出，请手动重试（可能消耗下载额度）",
                    errorCode = null,
                    updatedAt = System.currentTimeMillis(),
                    localPath = null,
                ),
            )
            count++
        }
        count
    }

    /** 入队：文件名已知时先检查同名，未确认覆盖则不发起网络请求。 */
    suspend fun enqueue(
        request: BookDownloadRequest,
        title: String,
        overwriteApproved: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val safeName = EpubImportFiles.fileName(request.fileName)
        val exists = LibraryStore(context).snapshot().books.any { it.fileName == safeName }
        val now = System.currentTimeMillis()
        val task = OnlineDownloadTask(
            taskId = UUID.randomUUID().toString(),
            providerId = request.providerId,
            accountScope = request.accountScope,
            remoteId = request.remoteId,
            bookTitle = title.ifBlank { safeName },
            fileName = safeName,
            totalBytes = request.expectedSize,
            status = if (exists && !overwriteApproved) DownloadStatus.FAILED else DownloadStatus.QUEUED,
            errorMessage = if (exists && !overwriteApproved) "本地已存在同名书籍，请确认覆盖后再下载" else null,
            overwriteApproved = overwriteApproved,
            createdAt = now,
            updatedAt = now,
        )
        repository.save(task)
        if (task.status == DownloadStatus.QUEUED) launch(task)
        task.taskId
    }

    /** 显式重试：IMPORT_FAILED 只重试导入，不重复消耗下载额度。 */
    fun retry(taskId: String, overwriteApproved: Boolean = false) {
        scope.launch {
            val current = repository.find(taskId) ?: return@launch
            if (current.status.active) return@launch
            val exists = LibraryStore(context).snapshot().books.any { it.fileName == current.fileName }
            if (exists && !overwriteApproved) {
                repository.save(
                    current.copy(
                        status = DownloadStatus.IMPORT_FAILED,
                        errorMessage = "本地已存在同名书籍，请确认覆盖",
                        overwriteApproved = false,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                return@launch
            }
            val next = current.copy(
                status = DownloadStatus.QUEUED,
                overwriteApproved = current.overwriteApproved || overwriteApproved,
                errorCode = null,
                errorMessage = null,
                receivedBytes = if (hasPendingFile(current)) current.receivedBytes else 0L,
                updatedAt = System.currentTimeMillis(),
            )
            repository.save(next)
            launch(next)
        }
    }

    fun cancel(taskId: String) {
        jobs[taskId]?.cancel()
        scope.launch {
            val current = repository.find(taskId) ?: return@launch
            current.localPath?.let { File(it).delete() }
            repository.save(current.copy(status = DownloadStatus.CANCELLED, updatedAt = System.currentTimeMillis()))
        }
    }

    fun clearHistory() {
        scope.launch {
            // 只清空已结束任务，不打断正在下载/导入的任务。
            repository.clearFinished()
            if (repository.unfinished().isEmpty()) notifier.stop()
        }
    }

    private fun launch(task: OnlineDownloadTask) {
        jobs[task.taskId] = scope.launch { run(task) }
    }

    private suspend fun run(initial: OnlineDownloadTask) {
        running.incrementAndGet()
        semaphore.withPermit {
            var task = initial
            var attempts = task.attempts
            while (true) {
                try {
                    task = transit(task, DownloadStatus.PREPARING)
                    val provider = registry.get(task.providerId) as? CatalogBookProvider
                        ?: throw ProviderException(ProviderErrorCode.INVALID_CONFIG, "书源不可用")
                    notifier.start(task.bookTitle)

                    val pending = pendingFile(task)
                    if (!pending.isFile) {
                        val temp = OnlineFiles.newTemp(context, task.taskId)
                        task = task.copy(localPath = temp.path, receivedBytes = 0L, status = DownloadStatus.DOWNLOADING)
                        repository.save(task)
                        val result = provider.download(
                            BookDownloadRequest(
                                providerId = task.providerId,
                                accountScope = task.accountScope,
                                remoteId = task.remoteId,
                                fileName = task.fileName,
                                remotePath = temp.path,
                                expectedSize = task.totalBytes,
                            ),
                            onProgress = { received, total -> reportProgress(task, received, total) },
                            isBookOpen = isBookOpen,
                        )
                        when (result) {
                            is DownloadResult.Cancelled -> {
                                temp.delete()
                                task = transit(task, DownloadStatus.CANCELLED)
                                repository.save(task)
                                return@withPermit
                            }
                            is DownloadResult.Failed -> {
                                temp.delete()
                                throw ProviderException(result.code, result.message)
                            }
                            is DownloadResult.Success -> {
                                safeMove(temp, pending)
                                task = task.copy(localPath = pending.path, receivedBytes = pending.length())
                            }
                        }
                    }

                    task = transit(task, DownloadStatus.VERIFYING)
                    repository.save(task)
                    task = transit(task, DownloadStatus.IMPORTING)
                    repository.save(task)
                    val imported = coordinator.import(
                        pending = pending,
                        fileName = task.fileName,
                        providerId = task.providerId,
                        accountScope = task.accountScope,
                        remoteId = task.remoteId,
                        overwriteApproved = task.overwriteApproved,
                        isBookOpen = isBookOpen,
                    )
                    when (imported) {
                        is OnlineImportResult.Success -> {
                            pending.delete()
                            task = task.copy(
                                status = DownloadStatus.COMPLETED,
                                localBookId = imported.bookId,
                                localPath = null,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis(),
                            )
                            repository.save(task)
                        }
                        is OnlineImportResult.Conflict -> {
                            task = task.copy(
                                status = DownloadStatus.IMPORT_FAILED,
                                errorMessage = "本地已存在同名书籍，请确认覆盖",
                                updatedAt = System.currentTimeMillis(),
                            )
                            repository.save(task)
                        }
                        is OnlineImportResult.Failed -> {
                            task = task.copy(
                                status = DownloadStatus.IMPORT_FAILED,
                                errorMessage = imported.message,
                                updatedAt = System.currentTimeMillis(),
                            )
                            repository.save(task)
                        }
                    }
                    break
                } catch (cancelled: CancellationException) {
                    task.localPath?.let { File(it).delete() }
                    repository.save(task.copy(status = DownloadStatus.CANCELLED, localPath = null, updatedAt = System.currentTimeMillis()))
                    throw cancelled
                } catch (e: Exception) {
                    attempts += 1
                    val code = if (e is ProviderException) e.code else ProviderErrorCode.NETWORK
                    task = task.copy(
                        attempts = attempts,
                        errorCode = code,
                        errorMessage = e.message ?: "下载失败",
                        updatedAt = System.currentTimeMillis(),
                    )
                    if (DownloadRetryPolicy.shouldRetry(code, attempts)) {
                        task = task.copy(status = DownloadStatus.QUEUED, localPath = null)
                        repository.save(task)
                        continue
                    }
                    task = task.copy(status = DownloadStatus.FAILED, localPath = null)
                    repository.save(task)
                    break
                }
            }
        }
        jobs.remove(initial.taskId)
        if (running.decrementAndGet() == 0) notifier.stop()
    }

    private suspend fun transit(task: OnlineDownloadTask, next: DownloadStatus): OnlineDownloadTask {
        DownloadStatus.requireTransit(task.status, next)
        val updated = task.copy(status = next, updatedAt = System.currentTimeMillis())
        repository.save(updated)
        return updated
    }

    private fun reportProgress(base: OnlineDownloadTask, received: Long, total: Long?) {
        val now = System.currentTimeMillis()
        if (now - (lastProgressAt[base.taskId] ?: 0L) < 700L && received != total) return
        lastProgressAt[base.taskId] = now
        val updated = base.copy(
            status = DownloadStatus.DOWNLOADING,
            receivedBytes = received,
            totalBytes = total ?: base.totalBytes,
            updatedAt = now,
        )
        scope.launch { repository.save(updated) }
        notifier.update(base.bookTitle, received, total ?: base.totalBytes ?: 0L)
    }

    private fun safeMove(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun hasPendingFile(task: OnlineDownloadTask): Boolean =
        task.localPath?.let { File(it).isFile && File(it).parentFile?.name == "pending_import" } == true

    private fun pendingFile(task: OnlineDownloadTask): File =
        if (hasPendingFile(task)) File(task.localPath!!)
        else File(OnlineFiles.pendingImport(context), "${task.taskId}.epub")

    companion object {
        const val MAX_CONCURRENT = 2
    }
}
