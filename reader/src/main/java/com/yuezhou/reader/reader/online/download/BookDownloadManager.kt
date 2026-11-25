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
import com.yuezhou.reader.reader.online.import.OnlineImportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 前台保活通知由 app 层注入，reader 层只依赖这个接口。 */
interface DownloadNotifier {
    fun start(title: String) {}
    fun update(title: String, received: Long, total: Long?) {}
    fun stop() {}
}

/**
 * 统一下载管理器（阶段 B）：并发 2、进度节流、安全重试、取消、导入失败重试、外部交接。
 *
 * 主进程只应存在一个实例（见 app 层 DownloadManagerHolder）。外部文件先落私有暂存目录，
 * 校验后再交现有导入协调器；进程死亡后通过交接文件与 pending 文件对账，不自动重新申请下载链接。
 */
class BookDownloadManager(
    private val registry: ProviderRegistry,
    private val repository: DownloadTaskStore,
    private val files: DownloadFileStore,
    private val importer: DownloadImporter,
    private val externalSource: ExternalDownloadSource,
    private val handoffStore: DownloadHandoffStore,
    private val localBookNames: suspend () -> Set<String>,
    private val isBookOpen: (String) -> Boolean = { false },
    private val notifier: DownloadNotifier = object : DownloadNotifier {},
    private val refreshSignal: LibraryRefreshSignal = LibraryRefreshSignal(),
    private val allowedContentAuthorities: Set<String> = setOf(ContentUriPolicy.SYSTEM_DOWNLOAD_AUTHORITY),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lastProgressAt = mutableMapOf<String, Long>()
    private val running = AtomicInteger(0)
    private val acceptLocks = ConcurrentHashMap<String, Mutex>()

    val tasks: Flow<List<OnlineDownloadTask>> = repository.observeAll()
    val activeRemoteIds: Flow<Set<String>> = repository.observeActiveRemoteIds()

    /** 导入成功后版本号自增，供 UI 即时刷新书架快照（StateFlow 保证后台完成不丢信号）。 */
    val libraryVersion: StateFlow<Long> get() = refreshSignal.version

    /**
     * 启动时恢复：临时目录直接清理；pending 文件只有在归属、可读、EPUB 校验都通过时才保留。
     * 待命名/待覆盖/导入失败状态保留给用户处理；已完成任务不重复导入。
     */
    suspend fun recoverInterrupted(): Int = withContext(Dispatchers.IO) {
        files.temporaryDir().listFiles()?.forEach { it.delete() }
        var count = 0

        // 先对账交接文件：已完成/已取消/已有有效 pending 的回执直接清理；其余尝试幂等接收。
        for (receipt in handoffStore.readAll()) {
            val taskId = taskIdFor(receipt.transferId)
            val task = repository.find(taskId)
            when {
                task == null -> {
                    val result = acceptCompletedDownload(receipt)
                    if (result is ExternalAcceptResult.Rejected || result is ExternalAcceptResult.Failed) {
                        handoffStore.delete(receipt.transferId)
                    }
                    count++
                }
                task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.CANCELLED -> {
                    handoffStore.delete(receipt.transferId)
                }
                hasValidPending(task) -> handoffStore.delete(receipt.transferId)
                else -> {
                    val result = acceptCompletedDownload(receipt)
                    if (result !is ExternalAcceptResult.Accepted && result !is ExternalAcceptResult.Duplicate) {
                        handoffStore.delete(receipt.transferId)
                    }
                    count++
                }
            }
        }

        for (task in repository.unfinished()) {
            when (task.status) {
                DownloadStatus.AWAITING_NAME, DownloadStatus.IMPORT_FAILED -> {
                    if (!hasValidPending(task)) {
                        repository.save(
                            task.copy(
                                status = DownloadStatus.FAILED,
                                localPath = null,
                                errorCode = ProviderErrorCode.IO,
                                errorMessage = "暂存文件已失效，请重新下载",
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        count++
                    }
                }
                DownloadStatus.QUEUED,
                DownloadStatus.PREPARING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.VERIFYING,
                DownloadStatus.IMPORTING,
                -> {
                    if (hasValidPending(task)) {
                        // 不自动重复导入：保留文件，交给用户显式重试，避免重复写书库/映射。
                        repository.save(
                            task.copy(
                                status = DownloadStatus.IMPORT_FAILED,
                                errorMessage = "任务被中断，可重试导入（不会重新下载）",
                                errorCode = null,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    } else {
                        deleteManagedFile(task.localPath)
                        repository.save(
                            task.copy(
                                status = DownloadStatus.FAILED,
                                localPath = null,
                                errorCode = ProviderErrorCode.IO,
                                errorMessage = "任务被中断，请重新下载",
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    count++
                }
                else -> Unit
            }
        }

        count += cleanupOrphanPending()
        count
    }

    /** 入队：文件名已知时先检查同名，未确认覆盖则不发起网络请求。 */
    suspend fun enqueue(
        request: BookDownloadRequest,
        title: String,
        overwriteApproved: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val safeName = EpubImportFiles.fileName(request.fileName)
        val exists = localBookNames().contains(safeName)
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

    /**
     * 接收网页进程交接的已完成下载。调用方只需保证 content URI 可读；
     * 大文件复制、校验和导入都在 IO 线程异步完成，不阻塞 IPC 调用线程。
     */
    suspend fun acceptCompletedDownload(receipt: ExternalDownloadReceipt): ExternalAcceptResult =
        withContext(Dispatchers.IO) {
            validateReceipt(receipt)?.let { return@withContext ExternalAcceptResult.Rejected(it) }

            val taskId = taskIdFor(receipt.transferId)
            acceptLocks.computeIfAbsent(taskId) { Mutex() }.withLock {
                val existing = repository.find(taskId)
                if (existing != null && isDuplicate(existing)) {
                    handoffStore.delete(receipt.transferId)
                    return@withLock ExternalAcceptResult.Duplicate(taskId)
                }

                if (!externalSource.verifyOwnership(receipt.contentUri, receipt.downloadId)) {
                    return@withLock ExternalAcceptResult.Rejected("下载任务不属于当前应用")
                }
                val actualLength = runCatching { externalSource.length(receipt.contentUri) }.getOrNull()
                if (actualLength != null && actualLength > MAX_EXTERNAL_DOWNLOAD_BYTES) {
                    return@withLock ExternalAcceptResult.Rejected("文件超过大小上限")
                }
                if (actualLength != null && receipt.expectedBytes != null && actualLength != receipt.expectedBytes) {
                    return@withLock ExternalAcceptResult.Rejected("文件大小与交接信息不一致")
                }

                // 回执先原子落盘，保证复制/校验中途进程死亡后仍可对账。
                handoffStore.write(receipt)

                val now = System.currentTimeMillis()
                var task = OnlineDownloadTask(
                    taskId = taskId,
                    providerId = ZLIBRARY_WEB_PROVIDER_ID,
                    accountScope = receipt.accountScope,
                    // remoteRef 是稳定的来源路径；缺失时等文件名确定后再用规范化文件名兜底，
                    // 绝不用 transferId 充当永久远端身份，避免映射表无意义累积。
                    remoteId = DownloadHandoffStore.sanitizeRemoteRef(receipt.remoteRef).orEmpty(),
                    bookTitle = receipt.suggestedFileName?.removeSuffix(".epub").orEmpty(),
                    fileName = DownloadNamePolicy.normalize(receipt.suggestedFileName) ?: "book.epub",
                    status = DownloadStatus.PREPARING,
                    totalBytes = receipt.expectedBytes ?: actualLength,
                    overwriteApproved = false,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                repository.save(task)

                var temp: File? = null
                try {
                    val created = File.createTempFile("${taskId}-", ".part", files.temporaryDir())
                    temp = created
                    val received = copyBounded(receipt, created)
                    if (received <= 0L) throw IOException("文件为空")
                    if (receipt.expectedBytes != null && received != receipt.expectedBytes) {
                        throw IOException("文件长度与交接信息不一致")
                    }
                    val pending = File(files.pendingDir(), "$taskId.epub")
                    safeMove(created, pending)
                    temp = null
                    task = task.copy(localPath = pending.path)

                    task = transit(
                        task.copy(
                            localPath = pending.path,
                            receivedBytes = received,
                            totalBytes = receipt.expectedBytes ?: actualLength ?: received,
                        ),
                        DownloadStatus.VERIFYING,
                    )

                    val invalid = importer.validate(pending)
                    if (invalid != null) {
                        pending.delete()
                        val failed = transit(
                            task.copy(localPath = null, errorCode = ProviderErrorCode.INVALID_CONFIG, errorMessage = invalid),
                            DownloadStatus.FAILED,
                        )
                        repository.save(failed)
                        handoffStore.delete(receipt.transferId)
                        return@withLock ExternalAcceptResult.Failed(taskId, invalid)
                    }

                    val title = importer.extractTitle(pending)?.trim().orEmpty()
                    val resolved = resolveFileName(receipt.suggestedFileName, title)
                    if (resolved == null) {
                        val awaiting = transit(
                            task.copy(
                                bookTitle = title.ifBlank { "网页下载" },
                                fileName = DownloadNamePolicy.normalize(receipt.suggestedFileName) ?: "book.epub",
                            ),
                            DownloadStatus.AWAITING_NAME,
                        )
                        repository.save(awaiting)
                        handoffStore.delete(receipt.transferId)
                        return@withLock ExternalAcceptResult.Accepted(taskId, awaitingName = true)
                    }

                    val displayTitle = title.ifBlank { resolved.removeSuffix(".epub") }
                    val stableRemoteId = task.remoteId.ifBlank { resolved }
                    if (localBookNames().contains(resolved)) {
                        val conflict = transit(
                            task.copy(
                                fileName = resolved,
                                bookTitle = displayTitle,
                                remoteId = stableRemoteId,
                                errorCode = null,
                            ),
                            DownloadStatus.IMPORT_FAILED,
                        ).copy(errorMessage = "本地已存在同名书籍，请确认覆盖")
                        repository.save(conflict)
                        handoffStore.delete(receipt.transferId)
                        return@withLock ExternalAcceptResult.Accepted(taskId, awaitingName = false)
                    }

                    val queued = transit(
                        task.copy(fileName = resolved, bookTitle = displayTitle, remoteId = stableRemoteId),
                        DownloadStatus.QUEUED,
                    )
                    repository.save(queued)
                    handoffStore.delete(receipt.transferId)
                    launch(queued)
                    ExternalAcceptResult.Accepted(taskId, awaitingName = false)
                } catch (cancelled: CancellationException) {
                    temp?.delete()
                    throw cancelled
                } catch (e: Exception) {
                    temp?.delete()
                    deleteManagedFile(task.localPath)
                    repository.save(
                        task.copy(
                            status = DownloadStatus.FAILED,
                            localPath = null,
                            errorCode = ProviderErrorCode.IO,
                            errorMessage = e.message ?: "外部下载接收失败",
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    handoffStore.delete(receipt.transferId)
                    ExternalAcceptResult.Failed(taskId, e.message ?: "外部下载接收失败")
                }
            }
        }

    /**
     * 记录系统下载失败/取消（阶段 D）。
     *
     * 网页任务在文件完成前不创建任务；只有确认失败时才落一条 FAILED 任务，让用户能在
     * 下载管理中看到原因并重新发起。已有任务（含已完成/活动中/待命名）一律不覆盖。
     */
    suspend fun recordExternalDownloadFailure(
        transferId: String,
        suggestedFileName: String?,
        remoteRef: String?,
        accountScope: String,
        reason: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!DownloadHandoffStore.isValidTransferId(transferId)) return@withContext false
        val taskId = taskIdFor(transferId)
        acceptLocks.computeIfAbsent(taskId) { Mutex() }.withLock {
            if (repository.find(taskId) != null) return@withLock false
            val safeName = DownloadNamePolicy.normalize(suggestedFileName) ?: "book.epub"
            val now = System.currentTimeMillis()
            repository.save(
                OnlineDownloadTask(
                    taskId = taskId,
                    providerId = ZLIBRARY_WEB_PROVIDER_ID,
                    accountScope = accountScope,
                    remoteId = DownloadHandoffStore.sanitizeRemoteRef(remoteRef).orEmpty(),
                    bookTitle = safeName.removeSuffix(".epub"),
                    fileName = safeName,
                    status = DownloadStatus.FAILED,
                    overwriteApproved = false,
                    errorCode = ProviderErrorCode.IO,
                    errorMessage = reason,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            true
        }
    }

    /** 用户确认/修改文件名后继续导入；同名冲突仍由导入前的最后一道检查处理。 */
    suspend fun confirmName(taskId: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val current = repository.find(taskId) ?: return@withContext false
        if (current.status != DownloadStatus.AWAITING_NAME && current.status != DownloadStatus.IMPORT_FAILED) {
            return@withContext false
        }
        if (!hasValidPending(current)) return@withContext false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext false
        val safeName = EpubImportFiles.fileName(trimmed)
        val next = transit(
            current.copy(
                fileName = safeName,
                remoteId = current.remoteId.ifBlank { safeName },
                bookTitle = if (current.bookTitle.isBlank() || current.bookTitle == "网页下载") {
                    safeName.removeSuffix(".epub")
                } else {
                    current.bookTitle
                },
                overwriteApproved = false,
                errorCode = null,
                errorMessage = null,
            ),
            DownloadStatus.QUEUED,
        )
        launch(next)
        true
    }

    /** 显式重试：优先复用有效 pending；网页任务无暂存文件时不重新申请下载链接。 */
    fun retry(taskId: String, overwriteApproved: Boolean = false) {
        scope.launch {
            val current = repository.find(taskId) ?: return@launch
            if (current.status.active || current.status == DownloadStatus.AWAITING_NAME) return@launch
            if (current.providerId == ZLIBRARY_WEB_PROVIDER_ID && !hasValidPending(current)) {
                repository.save(
                    current.copy(
                        status = DownloadStatus.FAILED,
                        localPath = null,
                        errorCode = ProviderErrorCode.IO,
                        errorMessage = "暂存文件已失效，请重新下载",
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                return@launch
            }
            val exists = localBookNames().contains(current.fileName)
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
        jobs.remove(taskId)?.cancel()
        scope.launch {
            val current = repository.find(taskId) ?: return@launch
            deleteManagedFile(current.localPath)
            handoffStore.readAll()
                .filter { taskIdFor(it.transferId) == taskId }
                .forEach { handoffStore.delete(it.transferId) }
            repository.save(
                current.copy(
                    status = DownloadStatus.CANCELLED,
                    localPath = null,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun clearHistory() {
        scope.launch {
            // 只清空已结束任务，不打断正在下载/导入/待命名的任务。
            repository.clearFinished()
            cleanupOrphanPending()
            if (repository.unfinished().isEmpty()) notifier.stop()
        }
    }

    /** 清理 pending 中未被任何未完成任务引用的孤儿文件；只处理超过 1 小时的文件，避免误删正在复制的新文件。 */
    suspend fun cleanupOrphanPending(): Int = withContext(Dispatchers.IO) {
        val referenced = repository.unfinished()
            .mapNotNull { task -> task.localPath?.let { runCatching { File(it).canonicalPath }.getOrNull() } }
            .toSet()
        val threshold = System.currentTimeMillis() - 60 * 60 * 1000L
        var deleted = 0
        files.pendingDir().listFiles()?.forEach { file ->
            if (!file.isFile || file.lastModified() > threshold) return@forEach
            val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
            if (canonical !in referenced && file.delete()) deleted++
        }
        deleted
    }

    private fun isDuplicate(task: OnlineDownloadTask): Boolean {
        if (task.status == DownloadStatus.COMPLETED) return true
        if (task.status.active) return true
        return (task.status == DownloadStatus.AWAITING_NAME || task.status == DownloadStatus.IMPORT_FAILED) &&
            hasPendingFile(task)
    }

    private fun validateReceipt(receipt: ExternalDownloadReceipt): String? {
        if (!DownloadHandoffStore.isValidTransferId(receipt.transferId)) return "任务标识无效"
        val parsed = ContentUriPolicy.parse(receipt.contentUri) ?: return "文件 URI 无效"
        if (parsed.authority !in allowedContentAuthorities) return "文件来源不受信任"
        if (parsed.path.isBlank()) return "文件 URI 缺少路径"
        receipt.expectedBytes?.let { if (it <= 0 || it > MAX_EXTERNAL_DOWNLOAD_BYTES) return "文件大小超出允许范围" }
        if ((receipt.suggestedFileName?.length ?: 0) > 200) return "候选文件名过长"
        return null
    }

    private fun resolveFileName(suggested: String?, title: String): String? {
        if (DownloadNamePolicy.isMeaningful(suggested)) return DownloadNamePolicy.normalize(suggested)
        if (DownloadNamePolicy.isMeaningful(title)) return DownloadNamePolicy.normalize(title)
        return null
    }

    private fun copyBounded(receipt: ExternalDownloadReceipt, target: File): Long {
        var total = 0L
        externalSource.open(receipt.contentUri).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_EXTERNAL_DOWNLOAD_BYTES) throw IOException("文件超过大小上限")
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        return total
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
                    notifier.start(task.bookTitle)

                    val pending = pendingFile(task)
                    if (!pending.isFile) {
                        // 网页任务没有可复用暂存文件时只能提示重新下载，不解析网页书源、不重新申请链接。
                        if (task.providerId == ZLIBRARY_WEB_PROVIDER_ID) {
                            throw ProviderException(ProviderErrorCode.IO, "暂存文件已失效，请重新下载")
                        }
                        val provider = registry.get(task.providerId) as? CatalogBookProvider
                            ?: throw ProviderException(ProviderErrorCode.INVALID_CONFIG, "书源不可用")
                        val temp = File.createTempFile("${task.taskId}-", ".part", files.temporaryDir())
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
                    } else {
                        // 已有 pending（只重试导入/外部交接）：补齐 PREPARING → DOWNLOADING。
                        task = transit(task.copy(localPath = pending.path), DownloadStatus.DOWNLOADING)
                        repository.save(task)
                    }

                    task = transit(task, DownloadStatus.VERIFYING)
                    repository.save(task)
                    task = transit(task, DownloadStatus.IMPORTING)
                    repository.save(task)
                    val imported = importer.import(
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
                            refreshSignal.notifyImportSucceeded()
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
                    deleteManagedFile(task.localPath)
                    repository.save(
                        task.copy(
                            status = DownloadStatus.CANCELLED,
                            localPath = null,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                    throw cancelled
                } catch (e: Exception) {
                    attempts += 1
                    val code = if (e is ProviderException) e.code else ProviderErrorCode.NETWORK
                    deleteManagedFile(task.localPath)
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

    private fun hasPendingFile(task: OnlineDownloadTask): Boolean {
        return task.localPath?.let(::isManagedPendingPath) == true
    }

    private suspend fun hasValidPending(task: OnlineDownloadTask): Boolean {
        if (!hasPendingFile(task)) return false
        val file = File(task.localPath!!)
        if (!file.isFile) return false
        return importer.validate(file) == null
    }

    private fun isManagedPendingPath(path: String): Boolean {
        return runCatching {
            val file = File(path).canonicalFile
            file.isFile && file.parentFile?.canonicalPath == files.pendingDir().canonicalPath
        }.getOrDefault(false)
    }

    private fun deleteManagedFile(path: String?) {
        if (path == null) return
        runCatching {
            val file = File(path).canonicalFile
            val parent = file.parentFile?.canonicalPath
            if (parent == files.pendingDir().canonicalPath || parent == files.temporaryDir().canonicalPath) {
                file.delete()
            }
        }
    }

    private fun pendingFile(task: OnlineDownloadTask): File =
        if (hasPendingFile(task)) File(task.localPath!!)
        else File(files.pendingDir(), "${task.taskId}.epub")

    companion object {
        const val MAX_CONCURRENT = 2

        /** 稳定 transferId → 确定性 taskId，重复交接不会生成第二条任务。 */
        fun taskIdFor(transferId: String): String =
            UUID.nameUUIDFromBytes("$ZLIBRARY_WEB_PROVIDER_ID:$transferId".toByteArray()).toString()

        /** 生产装配；单例由 app 层 DownloadManagerHolder 保证。 */
        fun create(
            context: Context,
            registry: ProviderRegistry,
            notifier: DownloadNotifier,
            isBookOpen: (String) -> Boolean,
        ): BookDownloadManager {
            val files = ContextDownloadFileStore(context)
            return BookDownloadManager(
                registry = registry,
                repository = OnlineDownloadRepository(AppDatabase.get(context)),
                files = files,
                importer = AndroidDownloadImporter(context),
                externalSource = ContentResolverExternalDownloadSource(context),
                handoffStore = DownloadHandoffStore(files.handoffDir()),
                localBookNames = {
                    LibraryStore(context).snapshot().books.map { it.fileName }.toSet()
                },
                isBookOpen = isBookOpen,
                notifier = notifier,
                allowedContentAuthorities = setOf(
                    ContentUriPolicy.SYSTEM_DOWNLOAD_AUTHORITY,
                    "${context.packageName}.zlibrary.web",
                ),
            )
        }
    }
}
