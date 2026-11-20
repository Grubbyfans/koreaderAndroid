package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.core.cloud.CloudBackend
import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.CloudService
import com.yuezhou.reader.core.cloud.DiagnosticCollector
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.cloud.Outcome
import com.yuezhou.reader.core.cloud.ProgressPayload
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.cloud.baidu.BaiduBackend
import com.yuezhou.reader.core.cloud.webdav.WebDavBackend
import com.yuezhou.reader.core.protocol.BookNames
import com.yuezhou.reader.core.protocol.LibraryCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val downloadMutex = Mutex()

/**
 * 云同步桥接层：把本地 [CloudCredentials] 转成具体的 [CloudBackend]，
 * 再交给 core 的 [CloudService] 执行同步编排。app 层只依赖本类，不直接触碰后端工厂。
 *
 * 对齐方案 §13：凭据由 [CloudCredentialStore] 加密落盘，本层只读取、不缓存明文。
 * 后端二选一：优先百度（已授权时），否则 WebDAV（已配置时）。
 */
class CloudSync(private val context: Context) {

    private val credentialStore = CloudCredentialStore(context)

    /** 封面内存缓存：namespace/bookId -> PNG bytes（进程内加速，磁盘持久化在 [CloudCache]）。 */
    private val coverCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray?>()

    /** 封面失败防抖：namespace/bookId -> true（本轮进程内避免失败封面随重组反复请求）。 */
    private val coverFailed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 云端书库持久化缓存（列表按日历日 + 封面按版本）。 */
    private val cache = CloudCache(context)

    /** 诊断摘要持久化（最近 20 次，可导出脱敏 JSON，方案 §13.2）。 */
    private val diagnostics = DiagnosticStore(context)

    /** 同步结果：成功与否 + 用户可读消息。 */
    data class SyncOutcome(val success: Boolean, val message: String)

    /** 云端书目中的一本书（含是否已在本地）。 */
    data class RemoteBook(
        val bookId: String,
        val fileName: String,
        val size: Long,
        val coverPath: String?,
        /** 封面缓存版本（有 coverPath 时非空；用于按版本持久化缓存封面）。 */
        val coverVersion: String?,
        /** 是否已在本地书库（用于封面角标）。 */
        val local: Boolean,
        /** 封面 PNG（已从云端拉取，可能为 null 表示无封面/拉取失败）。 */
        val coverPng: ByteArray? = null,
    )

    /** 云端书库列表结果。 */
    data class RemoteLibrary(
        val books: List<RemoteBook>,
        val backendLabel: String,
        /** 是否来自本地缓存（true 表示本次未走网络）。 */
        val fromCache: Boolean = false,
        /** 最后成功从网络获取列表的时间（epoch millis；来自缓存时为缓存写入时间）。 */
        val fetchedAt: Long = 0L,
    )

    /**
     * 从凭据构造后端。返回 null 表示尚未配置任何后端。
     *
     * 选择规则：
     *   1. 用户显式指定了当前后端（[CloudCredentials.activeBackend]）→ 用指定后端；
     *   2. 未指定（null，自动）→ 优先百度（已授权），否则 WebDAV（已配置），否则 null。
     *
     * @param collector 可选的诊断收集器；非空时把其拦截器注入后端 OkHttpClient，
     * 以统计本次任务的请求数/字节/重定向（对齐方案 §13.2）。
     * @throws CloudException 凭据格式非法（如应用目录不是 /apps/xxx、token 缺失）。
     */
    suspend fun backend(collector: DiagnosticCollector? = null): CloudBackend? {
        val creds = credentialStore.load()
        val baiduAvailable = creds.baiduAccessToken != null && creds.baiduAppDir != null
        val webdavAvailable = creds.webdavRootUrl != null && creds.webdavUsername != null

        val useBaidu = when (creds.activeBackend) {
            "baidu" -> true
            "webdav" -> false
            else -> baiduAvailable || !webdavAvailable
        }

        // 诊断拦截器：把请求统计注入到后端 client
        fun withDiagnostics(base: () -> okhttp3.OkHttpClient): () -> okhttp3.OkHttpClient = {
            val client = base()
            if (collector != null) client.newBuilder().addInterceptor(collector.interceptor).build() else client
        }

        return when {
            useBaidu && baiduAvailable -> {
                BaiduBackend.create(
                    appDir = creds.baiduAppDir!!,
                    accessToken = creds.baiduAccessToken!!,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                    clientFactory = withDiagnostics { BaiduBackend.defaultClientForDiagnostics() },
                )
            }
            !useBaidu && webdavAvailable -> {
                WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl!!,
                    username = creds.webdavUsername!!,
                    password = creds.webdavPassword ?: "",
                    clientFactory = withDiagnostics { WebDavBackend.defaultClientForDiagnostics() },
                )
            }
            // 显式选了某后端但该后端未配置 → 回退到另一侧（容错，避免误报未配置）
            else -> when {
                baiduAvailable -> BaiduBackend.create(
                    appDir = creds.baiduAppDir!!,
                    accessToken = creds.baiduAccessToken!!,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                    clientFactory = withDiagnostics { BaiduBackend.defaultClientForDiagnostics() },
                )
                webdavAvailable -> WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl!!,
                    username = creds.webdavUsername!!,
                    password = creds.webdavPassword ?: "",
                    clientFactory = withDiagnostics { WebDavBackend.defaultClientForDiagnostics() },
                )
                else -> null
            }
        }
    }

    data class UploadSelection(
        val backendId: String,
        val backendLabel: String,
        val books: List<LibraryStore.Entry>,
        val syncedIds: Set<String>,
    )

    enum class UploadStatus { Queued, Preparing, Uploading, Publishing, Done, Failed, Uncertain, PendingPublication, Cancelled }

    data class BookUploadProgress(
        val status: UploadStatus,
        val percent: Int? = null,
        val message: String? = null,
    )

    suspend fun uploadSelection(): UploadSelection = withContext(Dispatchers.IO) {
        val backend = backend() ?: throw CloudException(ErrorCode.INVALID_INPUT, "请先配置云同步后端")
        backend.ensureAuthorized()
        val catalog = CloudService(backend).index().books.associateBy { it.bookId }
        val store = LibraryStore(context)
        val hidden = store.hiddenBookIds()
        val books = store.snapshot().books.filterNot { it.bookId in hidden }
        val syncedIds = books.filter { catalog[it.bookId]?.size == it.size }.map { it.bookId }.toSet()
        UploadSelection(backend.backendId, backendLabelOf(backend), books, syncedIds)
    }

    private class UploadStopped : java.io.IOException("已请求停止上传")

    /** 仅上传所选正文与封面，逐本发布书库条目。 */
    suspend fun pushLocal(
        backendId: String,
        books: List<LibraryStore.Entry>,
        isStopped: (String) -> Boolean,
        onProgress: (String, BookUploadProgress) -> Unit,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val collector = DiagnosticCollector()
        val start = System.currentTimeMillis()
        val receipts = PendingPublicationStore(context)
        var done = 0
        var failed = 0
        var uncertain = 0
        var pending = 0
        var cancelled = 0
        try {
            val backend = backend(collector)
            check(backend?.backendId == backendId) { "云后端已变更，请重新选择书籍" }
            val selectedBackend = checkNotNull(backend)
            selectedBackend.ensureAuthorized()
            val service = CloudService(selectedBackend)
            for (entry in books) {
                if (isStopped(entry.bookId)) {
                    cancelled++
                    onProgress(entry.bookId, BookUploadProgress(UploadStatus.Cancelled))
                    continue
                }
                val bookStart = System.currentTimeMillis()
                var staging: File? = null
                var size = 0L
                var uploadedFileName: String? = null
                var transferStarted = false
                onProgress(entry.bookId, BookUploadProgress(UploadStatus.Preparing))
                try {
                    val epub = entry.sourceUri?.let { uri ->
                        val temp = File.createTempFile("upload-", ".epub", context.cacheDir)
                        staging = temp
                        val resolver = context.contentResolver
                        checkNotNull(resolver.openInputStream(uri)) { "无法读取指定目录中的 EPUB" }.use { input ->
                            temp.outputStream().use { output -> input.copyTo(output) }
                        }
                        temp
                    } ?: File(EpubImporter.epubDir(context), entry.fileName)
                    size = epub.length()
                    check(epub.isFile && size > 0L) { "本地 EPUB 不存在或为空" }
                    service.uploadBook(
                        epub,
                        entry.fileName,
                        onProgress = { phase, percent ->
                            val status = when (phase) {
                                CloudService.UploadPhase.Preparing -> UploadStatus.Preparing
                                CloudService.UploadPhase.Transferring -> {
                                    transferStarted = true
                                    UploadStatus.Uploading
                                }
                                CloudService.UploadPhase.Publishing -> UploadStatus.Publishing
                            }
                            if (isStopped(entry.bookId)) throw UploadStopped()
                            onProgress(entry.bookId, BookUploadProgress(status, percent))
                        },
                        onBodyUploaded = { uploadedFileName = it },
                        makeCover = entry.coverPng?.let { png ->
                            { com.yuezhou.reader.core.protocol.CoverFile.encode(png) }
                        },
                    )
                    receipts.remove(selectedBackend.backendId, entry.bookId)
                    done++
                    onProgress(entry.bookId, BookUploadProgress(UploadStatus.Done))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val confirmedFileName = uploadedFileName
                    val status = when {
                        confirmedFileName != null -> {
                            receipts.upsert(PendingPublication(
                                backendId = selectedBackend.backendId,
                                bookId = entry.bookId,
                                fileName = confirmedFileName,
                                size = size,
                                coverPath = null,
                                coverVersion = null,
                                savedAt = System.currentTimeMillis(),
                            ))
                            pending++
                            UploadStatus.PendingPublication
                        }
                        isStopped(entry.bookId) && transferStarted -> {
                            uncertain++
                            UploadStatus.Uncertain
                        }
                        e is UploadStopped -> {
                            cancelled++
                            UploadStatus.Cancelled
                        }
                        e is CloudException && (e.code == ErrorCode.UNCERTAIN || e.outcome == Outcome.Uncertain) -> {
                            uncertain++
                            UploadStatus.Uncertain
                        }
                        else -> {
                            failed++
                            UploadStatus.Failed
                        }
                    }
                    onProgress(entry.bookId, BookUploadProgress(status, message = e.message))
                } finally {
                    staging?.delete()
                    collector.recordPhase("上传书籍", System.currentTimeMillis() - bookStart)
                }
            }

            val counts = mutableListOf("已上传 $done 本")
            if (failed > 0) counts.add("失败 $failed 本")
            if (uncertain > 0) counts.add("结果未知 $uncertain 本")
            if (pending > 0) counts.add("待发布 $pending 本")
            if (cancelled > 0) counts.add("已取消 $cancelled 本")
            if (pending + uncertain > 0) collector.markOutcome(Outcome.Uncertain)
            else if (failed > 0) collector.markOutcome(Outcome.Failed)
            SyncOutcome(done == books.size, counts.joinToString("，"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            collector.markOutcome(Outcome.Failed, (e as? CloudException)?.code)
            val message = e.message ?: "无法连接云端"
            books.forEach { onProgress(it.bookId, BookUploadProgress(UploadStatus.Failed, message = message)) }
            SyncOutcome(false, "上传失败：$message")
        } finally {
            if (done + uncertain + pending > 0) invalidateListCache()
            collector.recordPhase("总耗时", System.currentTimeMillis() - start)
            diagnostics.append("上传书籍", collector.snapshot())
        }
    }

    /**
     * 补全待发布回执：对每条回执只读取最新列表并合并条目，不重传正文（方案 §11.6）。
     * 正文在云端不存在（如上次根本没传成功）则跳过并清除该回执，交由下次同步重传。
     *
     * @return 成功补全的条数。
     */
    suspend fun repairPendingPublications(): Int = withContext(Dispatchers.IO) {
        val receipts = PendingPublicationStore(context)
        val backend = backend() ?: return@withContext 0
        val pending = receipts.load(backend.backendId)
        if (pending.isEmpty()) return@withContext 0

        backend.ensureAuthorized()
        val service = CloudService(backend)

        var repaired = 0
        for (pub in pending) {
            try {
                val record = service.repairPublication(
                    bookId = pub.bookId,
                    fileName = pub.fileName,
                    size = pub.size,
                    coverPath = pub.coverPath,
                    coverVersion = pub.coverVersion,
                )
                if (record != null) {
                    receipts.remove(backend.backendId, pub.bookId)
                    repaired++
                } else {
                    // 正文不在云端（上次未传成功），清除回执，交由下次同步重传
                    receipts.remove(backend.backendId, pub.bookId)
                }
            } catch (e: Exception) {
                // 补全失败保留回执，下次再试
            }
        }
        if (repaired > 0) invalidateListCache()
        repaired
    }

    /**
     * 读取云端书库列表（library.json），标注每本书是否已在本地。
     *
     * 对齐方案 §12：书库页只读列表、不逐书下载正文/封面；封面按需在展示时再取。
     * 未配置后端时抛 [CloudException]（由 UI 提示）。
     *
     * 缓存策略（对齐方案 §9.2）：
     * - 当天首次进入拉取最新列表并持久化缓存；当天再次进入直接返回缓存（[fromCache]=true）；
     * - [force] 为 true 时强制绕过缓存重新请求（主动更新）；
     * - 更新失败保留旧缓存，抛异常由 UI 展示失败状态。
     */
    suspend fun listBooks(force: Boolean = false): RemoteLibrary = withContext(Dispatchers.IO) {
        val collector = DiagnosticCollector()
        val start = System.currentTimeMillis()
        val backend = backend(collector) ?: throw CloudException(
            ErrorCode.INVALID_INPUT, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘",
        )
        val ns = backend.backendId

        // 主动刷新走网络；否则优先命中当天缓存
        if (!force) {
            val cached = cache.readList(ns)
            if (cached != null) {
                collector.recordCacheHit("列表")
                val fromCacheBooks = decodeCachedBooks(cached.raw, backend)
                collector.recordPhase("读列表", System.currentTimeMillis() - start)
                diagnostics.append("读云端书库", collector.snapshot())
                return@withContext RemoteLibrary(
                    books = fromCacheBooks,
                    backendLabel = backendLabelOf(backend),
                    fromCache = true,
                    fetchedAt = cached.fetchedAt,
                )
            }
        }

        try {
            backend.ensureAuthorized()
            val service = CloudService(backend)
            val index = service.index()

            // 本地已存在的 bookId 集合，用于封面角标
            val localIds = LibraryStore(context).snapshot().books.map { it.bookId }.toSet()

            val books = index.books.map { b ->
                RemoteBook(
                    bookId = b.bookId,
                    fileName = b.fileName,
                    size = b.size,
                    coverPath = b.coverPath,
                    coverVersion = b.coverVersion,
                    local = b.bookId in localIds,
                    coverPng = b.coverPath?.let { fetchCover(backend, ns, b.bookId, b.coverVersion ?: "") },
                )
            }
            val fetchedAt = System.currentTimeMillis()
            val result = RemoteLibrary(books, backendLabelOf(backend), fromCache = false, fetchedAt = fetchedAt)

            // 写回持久化缓存（只存列表原文，封面单独缓存）
            try {
                cache.writeList(ns, LibraryCatalog.encode(index), fetchedAt)
            } catch (e: Exception) {
                // 缓存写失败不影响主流程
            }
            collector.recordPhase("读列表", System.currentTimeMillis() - start)
            diagnostics.append("读云端书库", collector.snapshot())
            result
        } catch (e: CloudException) {
            collector.markOutcome(com.yuezhou.reader.core.cloud.Outcome.Failed, e.code)
            collector.recordPhase("读列表", System.currentTimeMillis() - start)
            diagnostics.append("读云端书库", collector.snapshot())
            throw e
        }
    }

    /**
     * 「主动更新书库」：强制刷新列表并登记外部上传的 EPUB（方案 §10）。
     * 返回用户可读的结果描述；失败抛异常由 UI 提示。
     */
    suspend fun refreshLibrary(): String = withContext(Dispatchers.IO) {
        val collector = DiagnosticCollector()
        val start = System.currentTimeMillis()
        val backend = backend(collector) ?: throw CloudException(
            ErrorCode.INVALID_INPUT, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘",
        )
        try {
            backend.ensureAuthorized()
            val service = CloudService(backend)

            val discovered = service.discoverExternalBooks()
            // 主动更新后失效缓存，下次 listBooks 强制重拉最新列表
            invalidateListCache()
            collector.recordPhase("主动更新", System.currentTimeMillis() - start)
            diagnostics.append("刷新书库", collector.snapshot())

            buildString {
                append("已更新书库")
                if (discovered.added.isNotEmpty()) append("，新增 ${discovered.added.size} 本")
                if (discovered.skipped.isNotEmpty()) append("，跳过 ${discovered.skipped.size} 本（同名冲突）")
                if (discovered.added.isEmpty() && discovered.skipped.isEmpty()) append("，无变化")
            }
        } catch (e: CloudException) {
            collector.markOutcome(com.yuezhou.reader.core.cloud.Outcome.Failed, e.code)
            collector.recordPhase("主动更新", System.currentTimeMillis() - start)
            diagnostics.append("刷新书库", collector.snapshot())
            throw e
        }
    }

    /** 从缓存的 library.json 原文重建 RemoteBook 列表（封面按版本走本地缓存，不联网）。 */
    private suspend fun decodeCachedBooks(raw: String, backend: CloudBackend): List<RemoteBook> {
        val index = try {
            com.yuezhou.reader.core.protocol.LibraryCatalog.decode(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val ns = backend.backendId
        val localIds = LibraryStore(context).snapshot().books.map { it.bookId }.toSet()
        return index.books.map { b ->
            RemoteBook(
                bookId = b.bookId,
                fileName = b.fileName,
                size = b.size,
                coverPath = b.coverPath,
                coverVersion = b.coverVersion,
                local = b.bookId in localIds,
                coverPng = b.coverPath?.let { fetchCover(backend, ns, b.bookId, b.coverVersion ?: "") },
            )
        }
    }

    /** 拉取单张封面（带内存 + 持久化缓存）。返回 PNG bytes 或 null。 */
    private suspend fun fetchCover(
        backend: CloudBackend,
        namespace: String,
        bookId: String,
        coverVersion: String,
    ): ByteArray? {
        val memKey = "$namespace/$bookId"
        // 内存缓存命中
        coverCache[memKey]?.let { return it }
        if (coverFailed[memKey] == true) return null

        // 持久化缓存（按版本）
        when (val c = cache.readCover(namespace, bookId, coverVersion)) {
            is CloudCache.CoverResult.Hit -> {
                coverCache[memKey] = c.pngBytes
                return c.pngBytes
            }
            CloudCache.CoverResult.Failed -> {
                coverFailed[memKey] = true
                return null
            }
            CloudCache.CoverResult.Miss -> Unit
        }

        // 联网拉取
        val bytes = try {
            val raw = backend.downloadBytes("covers/$bookId.json", 128 * 1024).toString(Charsets.UTF_8)
            com.yuezhou.reader.core.protocol.CoverFile.decode(raw).pngBytes
        } catch (e: Exception) {
            null
        }
        if (bytes != null) {
            coverCache[memKey] = bytes
            cache.writeCover(namespace, bookId, coverVersion, bytes)
        } else {
            coverFailed[memKey] = true
            cache.markCoverFailed(namespace, bookId, coverVersion)
        }
        return bytes
    }

    /** 后端显示名（中文）。 */
    private fun backendLabelOf(backend: CloudBackend): String = when (backend.kind) {
        BackendKind.Baidu -> "百度网盘"
        BackendKind.WebDav -> "WebDAV"
    }

    /**
     * 从云端下载一本书到本地并登记书库。
     *
     * 对齐方案 §11.5 下载：下载正文到 files/epub/（临时文件 → 校验 → 发布），
     * 成功后提取封面/书名登记本地书库。返回最终落盘的文件名。
     */
    suspend fun downloadBook(book: RemoteBook, isOpen: (String) -> Boolean): Result<String> = withContext(Dispatchers.IO) {
        downloadMutex.withLock {
            try {
                require(BookNames.cloudId(book.fileName).first == book.bookId) {
                    "云端 EPUB 文件名无效"
                }
                check(book.size in 1..(1024L * 1024 * 1024)) { "书库记录中的文件大小无效，请先更新书库" }
                check(!isOpen(book.bookId)) { "请关闭正在阅读的书籍后再下载" }
                val directory = DownloadDirectory(context)
                val backend = backend() ?: return@withLock Result.failure(
                    IllegalStateException("尚未配置云后端"),
                )
                backend.ensureAuthorized()

                val store = LibraryStore(context)
                val local = store.bookLocation(book.bookId)
                val destination = local?.tree ?: directory.uri
                val fileName = local?.fileName ?: book.fileName
                val target = File(EpubImporter.epubDir(context), fileName)
                check(target.parentFile?.usableSpace?.let { it >= book.size } == true) { "应用存储空间不足" }
                val tmp = File.createTempFile("download-", ".part", target.parentFile)
                try {
                    backend.downloadTo("books/${book.fileName}", tmp, maxBytes = book.size)
                    check(tmp.length() == book.size) { "下载大小与书库不符，请先更新书库" }
                    CoverExtractor.validatePackage(tmp)
                    check(!isOpen(book.bookId)) { "请关闭正在阅读的书籍后再下载" }
                    val cover = CoverExtractor.extract(tmp)
                    val title = CoverExtractor.extractTitle(tmp)
                    val source = destination?.let { directory.publish(it, tmp, fileName) }
                    if (source == null) {
                        Files.move(
                            tmp.toPath(), target.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    store.registerBook(fileName, cover, title, source, destination, book.size).getOrThrow()
                    if (source != null) target.delete()
                    invalidateListCache()
                    Result.success(fileName)
                } finally {
                    tmp.delete()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun deleteRemoteBook(bookId: String): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val backend = backend() ?: error("尚未配置云后端")
            backend.ensureAuthorized()
            try {
                CloudService(backend).deleteBook(bookId)
                SyncOutcome(true, "已删除云端书籍")
            } finally {
                PendingPublicationStore(context).remove(backend.backendId, bookId)
                invalidateListCache()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncOutcome(false, e.message ?: "无法删除云端书籍")
        }
    }

    // region 进度同步（对齐方案 §5.6，锚点暂以 CFI 直传，XPointer 桥接待专项）

    /** 进度同步的信息展示：本机与云端的对照。 */
    data class ProgressInfo(
        /** 本机位置（CFI），无则 null。 */
        val localLocation: String?,
        /** 本机进度百分比 0..1。 */
        val localPercent: Double?,
        /** 云端位置（当前直传 CFI），无则 null。 */
        val remoteLocation: String?,
        /** 云端进度百分比 0..1。 */
        val remotePercent: Double?,
        /** 云端更新时间（epoch millis），无则 null。 */
        val remoteUpdatedAt: Long?,
        /** 云端写入设备 ID，无则 null。 */
        val remoteDeviceId: String?,
        /** 当前生效后端显示名。 */
        val backendLabel: String?,
    )

    /**
     * 读取一本书的进度同步对照信息（本机 vs 云端）。
     * 不扫描书库，只读该书的本地进度与云端 `progress/<bookId>.json`。
     */
    suspend fun progressInfo(bookId: String): ProgressInfo = withContext(Dispatchers.IO) {
        val local = LibraryStore(context).snapshot().books.firstOrNull { it.bookId == bookId }
        val localLocation = local?.location
        val localPercent = local?.percent

        val backend = backend()
        val remote = if (backend != null) {
            try {
                backend.ensureAuthorized()
                CloudService(backend).downloadProgress(bookId)
            } catch (e: Exception) {
                null
            }
        } else null

        ProgressInfo(
            localLocation = localLocation,
            localPercent = localPercent,
            remoteLocation = remote?.xpointer,
            remotePercent = remote?.percent,
            remoteUpdatedAt = remote?.let { parseUpdatedAt(it.updatedAt) },
            remoteDeviceId = remote?.deviceId,
            backendLabel = backend?.let { backendLabelOf(it) },
        )
    }

    /**
     * 上传本机进度到云端（用户主动触发）。成功返回 null，失败返回错误消息。
     * 冻结当前锚点后写入；云端 revision 由 [CloudService] 自动 +1。
     */
    suspend fun uploadLocalProgress(
        bookId: String,
        location: String,
        percent: Double?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val backend = backend() ?: return@withContext "尚未配置云后端"
            backend.ensureAuthorized()
            val size = LibraryStore(context).snapshot().books
                .firstOrNull { it.bookId == bookId }?.size ?: 0L
            CloudService(backend).uploadProgress(
                bookId = bookId,
                progress = ProgressPayload(
                    xpointer = location,
                    percent = percent,
                    size = size,
                    deviceId = deviceId(),
                ),
            )
            null
        } catch (e: Exception) {
            e.message ?: "上传进度失败"
        }
    }

    /**
     * 应用云端进度：返回云端位置（当前直传 CFI）供 UI 跳转；无云端进度返回 null。
     * 不修改本地状态，跳转确认由 UI 完成后再提交。
     */
    suspend fun fetchRemoteProgressLocation(bookId: String): String? = withContext(Dispatchers.IO) {
        val backend = backend() ?: return@withContext null
        try {
            backend.ensureAuthorized()
            CloudService(backend).downloadProgress(bookId)?.xpointer
        } catch (e: Exception) {
            null
        }
    }

    private fun parseUpdatedAt(s: String): Long? = try {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    // endregion

    /**
     * 当前生效的后端种类（供 UI 展示选中态）。
     *
     * 与 [backend] 的选取规则一致：显式指定用指定后端，否则自动（百度优先）。
     * 返回 null 表示两侧均未配置。
     */
    suspend fun activeBackendKind(): BackendKind? {
        val creds = credentialStore.load()
        val baiduAvailable = creds.baiduAccessToken != null && creds.baiduAppDir != null
        val webdavAvailable = creds.webdavRootUrl != null && creds.webdavUsername != null
        return when (creds.activeBackend) {
            "baidu" -> if (baiduAvailable) BackendKind.Baidu else null
            "webdav" -> if (webdavAvailable) BackendKind.WebDav else null
            else -> when {
                baiduAvailable -> BackendKind.Baidu
                webdavAvailable -> BackendKind.WebDav
                else -> null
            }
        }
    }

    /** 失效云端书库列表缓存（同步/下载等改变云端状态后调用）。 */
    fun invalidateListCache() {
        // 清除所有命名空间的列表缓存（写操作后云端列表已变化，下次进入强制重拉）
        val root = java.io.File(context.filesDir, "cloud_cache")
        if (root.exists()) {
            root.listFiles()?.forEach { dir ->
                java.io.File(dir, "library.cache.json").delete()
            }
        }
    }

    /** 当前生效后端的显示名（中文），供同步提示使用；未配置返回 null。 */
    suspend fun activeBackendLabel(): String? = when (activeBackendKind()) {
        BackendKind.Baidu -> "百度网盘"
        BackendKind.WebDav -> "WebDAV"
        else -> null
    }

    /** 导出脱敏诊断 JSON（方案 §13.2「可主动导出脱敏 JSON」）。 */
    suspend fun exportDiagnostics(): String = diagnostics.export()

    /** 读取诊断摘要列表（时间升序），供 UI 展示。 */
    suspend fun diagnosticSummaries(): List<Pair<Long, Pair<String, com.yuezhou.reader.core.cloud.DiagnosticSummary>>> =
        diagnostics.all()

    private fun deviceId(): String {
        val sp = context.getSharedPreferences("library", Context.MODE_PRIVATE)
        var id = sp.getString("deviceId", null)
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            sp.edit().putString("deviceId", id).apply()
        }
        return id
    }
}
