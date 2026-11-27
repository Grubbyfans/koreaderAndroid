package com.yuezhou.reader.reader

import android.content.Context
import android.util.LruCache
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
import com.yuezhou.reader.core.online.model.CloudCoverReference
import com.yuezhou.reader.core.protocol.BookNames
import com.yuezhou.reader.core.protocol.CloudProgress
import com.yuezhou.reader.core.protocol.LibraryCatalog
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File


/**
 * 云同步桥接层：把本地 [CloudCredentials] 转成具体的 [CloudBackend]，
 * 再交给 core 的 [CloudService] 执行同步编排。app 层只依赖本类，不直接触碰后端工厂。
 *
 * 对齐方案 §13：凭据由 [CloudCredentialStore] 加密落盘，本层只读取、不缓存明文。
 * 后端二选一：优先百度（已授权时），否则 WebDAV（已配置时）。
 */
class CloudSync(private val context: Context) {

    private val credentialStore = CloudCredentialStore(context)

    private val coverCache = object : LruCache<CloudCoverReference, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: CloudCoverReference, value: ByteArray): Int = value.size
    }
    private val coverLocks = List(16) { Mutex() }
    private val coverRequests = Semaphore(3)

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
        val cloudCover: CloudCoverReference? = null,
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
    suspend fun backend(
        collector: DiagnosticCollector? = null,
        /** 指定书源对应的后端；null 表示沿用原有的「显式选择 / 自动优先百度」规则。 */
        forced: BackendKind? = null,
    ): CloudBackend? {
        val creds = credentialStore.load()
        val baiduAvailable = creds.baiduAccessToken != null && creds.baiduAppDir != null
        val webdavAvailable = creds.webdavRootUrl != null && creds.webdavUsername != null

        // 指定后端未配置时不回退到另一侧，避免「选了 WebDAV 却操作百度」。
        if (forced == BackendKind.Baidu && !baiduAvailable) return null
        if (forced == BackendKind.WebDav && !webdavAvailable) return null

        val useBaidu = forced?.let { it == BackendKind.Baidu } ?: when (creds.activeBackend) {
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
                    appDir = creds.baiduAppDir,
                    accessToken = creds.baiduAccessToken,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                    clientFactory = withDiagnostics { BaiduBackend.defaultClientForDiagnostics() },
                )
            }
            !useBaidu && webdavAvailable -> {
                WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl,
                    username = creds.webdavUsername,
                    password = creds.webdavPassword ?: "",
                    clientFactory = withDiagnostics { WebDavBackend.defaultClientForDiagnostics() },
                )
            }
            // 显式选了某后端但该后端未配置 → 回退到另一侧（容错，避免误报未配置）
            else -> when {
                baiduAvailable -> BaiduBackend.create(
                    appDir = creds.baiduAppDir,
                    accessToken = creds.baiduAccessToken,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                    clientFactory = withDiagnostics { BaiduBackend.defaultClientForDiagnostics() },
                )
                webdavAvailable -> WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl,
                    username = creds.webdavUsername,
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

    suspend fun uploadSelection(forced: BackendKind? = null): UploadSelection = withContext(Dispatchers.IO) {
        val backend = backend(forced = forced) ?: throw CloudException(ErrorCode.INVALID_INPUT, "请先配置云同步后端")
        backend.ensureAuthorized()
        val catalog = CloudService(backend).index().books.associateBy { it.bookId }
        val store = LibraryStore(context)
        val hidden = store.hiddenBookIds()
        val books = store.snapshot(includeCoverBytes = false, includeProgress = false).books.filterNot { it.bookId in hidden }
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
        forced: BackendKind? = null,
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
            val backend = backend(collector, forced)
            check(backend?.backendId == backendId) { "云后端已变更，请重新选择书籍" }
            val selectedBackend = checkNotNull(backend)
            selectedBackend.ensureAuthorized()
            val service = CloudService(selectedBackend)
            val store = LibraryStore(context)
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
                    val cover = entry.coverPng ?: entry.coverFile?.let(store::readCover)
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
                        makeCover = cover?.let { png ->
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
    suspend fun listBooks(force: Boolean = false, forced: BackendKind? = null): RemoteLibrary = withContext(Dispatchers.IO) {
        val collector = DiagnosticCollector()
        val start = System.currentTimeMillis()
        val backend = backend(collector, forced) ?: throw CloudException(
            ErrorCode.INVALID_INPUT, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘",
        )
        val ns = backend.backendId

        // 主动刷新走网络；否则优先命中当天缓存
        if (!force) {
            val cached = cache.readList(ns)
            if (cached != null) {
                collector.recordCacheHit("列表")
                val t0 = System.currentTimeMillis()
                val fromCacheBooks = decodeCachedBooks(cached.raw, ns)
                Log.d("CloudSync", "listBooks cache hit: decode=${System.currentTimeMillis() - t0}ms, books=${fromCacheBooks.size}")
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
            val localIds = LibraryStore(context).availableBookIds()

            val books = index.books.map { b ->
                RemoteBook(
                    bookId = b.bookId,
                    fileName = b.fileName,
                    size = b.size,
                    coverPath = b.coverPath,
                    coverVersion = b.coverVersion,
                    local = b.bookId in localIds,
                    cloudCover = b.coverPath?.let { CloudCoverReference(it, b.coverVersion.orEmpty(), ns) },
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
     * 发现并登记外部上传的 EPUB（方案 §10）。返回实际新增/更新的条目数（0=无变化）。
     * 不失效列表缓存，由调用方决定是否强制重拉。失败抛异常。
     */
    suspend fun discoverExternalBooks(forced: BackendKind? = null): Int = withContext(Dispatchers.IO) {
        val backend = backend(forced = forced) ?: throw CloudException(
            ErrorCode.INVALID_INPUT, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘",
        )
        backend.ensureAuthorized()
        CloudService(backend).discoverExternalBooks().changed
    }

    /**
     * 「主动更新书库」：强制刷新列表并登记外部上传的 EPUB（方案 §10）。
     * 返回用户可读的结果描述；失败抛异常由 UI 提示。
     */
    suspend fun refreshLibrary(forced: BackendKind? = null): String = withContext(Dispatchers.IO) {
        val collector = DiagnosticCollector()
        val start = System.currentTimeMillis()
        val backend = backend(collector, forced) ?: throw CloudException(
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
    private suspend fun decodeCachedBooks(raw: String, ns: String): List<RemoteBook> {
        val index = try {
            com.yuezhou.reader.core.protocol.LibraryCatalog.decode(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val localIds = LibraryStore(context).availableBookIds()
        return index.books.map { b ->
            RemoteBook(
                bookId = b.bookId,
                fileName = b.fileName,
                size = b.size,
                coverPath = b.coverPath,
                coverVersion = b.coverVersion,
                local = b.bookId in localIds,
                cloudCover = b.coverPath?.let { CloudCoverReference(it, b.coverVersion.orEmpty(), ns) },
            )
        }
    }

    /** Loads a visible cover for the same account and version that supplied its catalog row. */
    suspend fun loadCover(reference: CloudCoverReference, forced: BackendKind): ByteArray? = withContext(Dispatchers.IO) {
        val bookId = reference.path.removePrefix("covers/").removeSuffix(".json")
        if (reference.path != "covers/$bookId.json" || !BookNames.valid(bookId)) return@withContext null

        val lock = coverLocks[(reference.backendId.hashCode() xor bookId.hashCode()) and (coverLocks.size - 1)]
        lock.withLock {
            val backend = backend(forced = forced)?.takeIf { it.backendId == reference.backendId } ?: return@withLock null
            coverCache.get(reference)?.let { return@withLock it }
            when (val cached = cache.readCover(reference.backendId, bookId, reference.version)) {
                is CloudCache.CoverResult.Hit -> cached.pngBytes?.let {
                    coverCache.put(reference, it)
                    return@withLock it
                }
                CloudCache.CoverResult.Failed -> return@withLock null
                CloudCache.CoverResult.Miss -> Unit
            }

            coverRequests.withPermit {
                val bytes = try {
                    val raw = backend.downloadBytes(reference.path, com.yuezhou.reader.core.protocol.CoverFile.MAX_JSON_BYTES).toString(Charsets.UTF_8)
                    com.yuezhou.reader.core.protocol.CoverFile.decode(raw).pngBytes
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (bytes != null) {
                    coverCache.put(reference, bytes)
                    cache.writeCover(reference.backendId, bookId, reference.version, bytes)
                } else {
                    cache.markCoverFailed(reference.backendId, bookId, reference.version)
                }
                bytes
            }
        }
    }

    /** 后端显示名（中文）。 */
    private fun backendLabelOf(backend: CloudBackend): String = when (backend.kind) {
        BackendKind.Baidu -> "百度网盘"
        BackendKind.WebDav -> "WebDAV"
    }

    /** 只传输到下载队列的暂存文件，正文发布和书库登记由同一个导入入口完成。 */
    internal suspend fun downloadBookTo(
        book: RemoteBook,
        target: File,
        backend: CloudBackend,
        onProgress: (received: Long, total: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(BookNames.cloudId(book.fileName).first == book.bookId) { "云端 EPUB 文件名无效" }
        backend.ensureAuthorized()
        val stat = try {
            backend.stat("books/${book.fileName}")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val size = stat?.size?.takeIf { it > 0 } ?: book.size
        if (size !in 1..LibraryCatalog.MAX_SIZE) throw CloudException(ErrorCode.INVALID_INPUT, "云端文件大小无效")
        if (target.parentFile?.usableSpace?.let { it >= size } != true) {
            throw java.io.IOException("应用存储空间不足")
        }
        backend.downloadTo("books/${book.fileName}", target, maxBytes = size) { received, _ ->
            onProgress(received, size)
        }
        if (target.length() != size) throw java.io.IOException("下载大小与云端不符，请先更新书库")

        if (book.size != size) {
            // 保留云端大小纠正，但仅在完整 EPUB 已确认后发布。
            CoverExtractor.validatePackage(target)
            try {
                CloudService(backend).repairPublication(
                    bookId = book.bookId,
                    fileName = book.fileName,
                    size = size,
                    coverPath = book.coverPath,
                    coverVersion = book.coverVersion,
                )
                invalidateListCache()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CloudSync", "正文已下载，无法修正云端书籍大小", e)
            }
        }
    }

    suspend fun deleteRemoteBook(bookId: String, forced: BackendKind? = null): SyncOutcome = withContext(Dispatchers.IO) {
        try {
            val backend = backend(forced = forced) ?: error("尚未配置云后端")
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

    // region 进度同步（对齐方案 §5.6）

    /** 进度同步的信息展示：本机与云端的对照。 */
    data class ProgressInfo(
        /** 本机位置（CFI），无则 null。 */
        val localLocation: String?,
        /** 本机进度百分比 0..1。 */
        val localPercent: Double?,
        /** 云端位置（XPointer），无则 null。 */
        val remoteLocation: String?,
        /** 云端进度百分比 0..1。 */
        val remotePercent: Double?,
        /** 云端更新时间（epoch millis），无则 null。 */
        val remoteUpdatedAt: Long?,
        /** 云端写入设备 ID，无则 null。 */
        val remoteDeviceId: String?,
        /** 当前生效后端显示名。 */
        val backendLabel: String?,
        val remoteError: String?,
    )

    /**
     * 读取一本书的进度同步对照信息（本机 vs 云端）。
     * 不扫描书库，只读该书的本地进度与云端 `progress/<bookId>.json`。
     */
    suspend fun progressInfo(bookId: String): ProgressInfo = withContext(Dispatchers.IO) {
        val local = LibraryStore(context).savedProgress(bookId)
        var backendLabel: String? = null
        var remoteError: String? = null
        val remote = try {
            val backend = requireProgressBackend()
            backendLabel = backendLabelOf(backend)
            readRemoteProgress(backend, bookId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            remoteError = e.message ?: "无法读取云端进度"
            null
        }

        ProgressInfo(
            localLocation = local?.first,
            localPercent = local?.second,
            remoteLocation = remote?.xpointer,
            remotePercent = remote?.percent,
            remoteUpdatedAt = remote?.let { parseUpdatedAt(it.updatedAt) },
            remoteDeviceId = remote?.deviceId,
            backendLabel = backendLabel,
            remoteError = remoteError,
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
            val size = LibraryStore(context).bookSize(bookId)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "上传进度失败"
        }
    }

    suspend fun fetchRemoteProgress(bookId: String): CloudProgress? = withContext(Dispatchers.IO) {
        readRemoteProgress(requireProgressBackend(), bookId)
    }

    private suspend fun requireProgressBackend(): CloudBackend =
        checkNotNull(backend()) { "尚未配置云后端" }

    private suspend fun readRemoteProgress(backend: CloudBackend, bookId: String): CloudProgress? {
        backend.ensureAuthorized()
        return CloudService(backend).downloadProgress(bookId)?.also { progress ->
            if (progress.bookId != bookId) throw CloudException(ErrorCode.CONFLICT, "云端进度书名不匹配")
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
