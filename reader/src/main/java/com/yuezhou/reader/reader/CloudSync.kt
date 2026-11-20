package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.core.cloud.CloudBackend
import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.CloudService
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.cloud.ProgressPayload
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.cloud.baidu.BaiduBackend
import com.yuezhou.reader.core.cloud.webdav.WebDavBackend
import com.yuezhou.reader.core.protocol.LibraryCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * @throws CloudException 凭据格式非法（如应用目录不是 /apps/xxx、token 缺失）。
     */
    suspend fun backend(): CloudBackend? {
        val creds = credentialStore.load()
        val baiduAvailable = creds.baiduAccessToken != null && creds.baiduAppDir != null
        val webdavAvailable = creds.webdavRootUrl != null && creds.webdavUsername != null

        val useBaidu = when (creds.activeBackend) {
            "baidu" -> true
            "webdav" -> false
            else -> baiduAvailable || !webdavAvailable
        }

        return when {
            useBaidu && baiduAvailable -> {
                BaiduBackend.create(
                    appDir = creds.baiduAppDir!!,
                    accessToken = creds.baiduAccessToken!!,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                )
            }
            !useBaidu && webdavAvailable -> {
                WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl!!,
                    username = creds.webdavUsername!!,
                    password = creds.webdavPassword ?: "",
                )
            }
            // 显式选了某后端但该后端未配置 → 回退到另一侧（容错，避免误报未配置）
            else -> when {
                baiduAvailable -> BaiduBackend.create(
                    appDir = creds.baiduAppDir!!,
                    accessToken = creds.baiduAccessToken!!,
                    expiresAt = creds.baiduExpiresAt ?: 0L,
                    userId = creds.baiduUserId,
                )
                webdavAvailable -> WebDavBackend.create(
                    rootUrl = creds.webdavRootUrl!!,
                    username = creds.webdavUsername!!,
                    password = creds.webdavPassword ?: "",
                )
                else -> null
            }
        }
    }

    /**
     * 执行一次「本地 → 云端」同步：
     * 1. 上传书库所有正文（同名覆盖，保留封面与进度）；
     * 2. 上传每本书的阅读进度。
     *
     * 首版为单向推送，不做远端拉取合并（方案 §4 的完整双向同步依赖 Room 索引，后续实现）。
     *
     * @param onBookProgress 单本书上传进度的回调（用于 UI 展示进度），可为 null。
     */
    suspend fun pushLocal(onBookProgress: ((done: Int, total: Int) -> Unit)? = null): SyncOutcome =
        withContext(Dispatchers.IO) {
            try {
                val backend = backend()
                    ?: return@withContext SyncOutcome(false, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘")
                // 授权就绪检查（百度校验 token 未过期、WebDAV 校验凭据可用）
                backend.ensureAuthorized()
                val service = CloudService(backend)
                val receipts = PendingPublicationStore(context)

                val store = LibraryStore(context)
                val snapshot = store.snapshot()
                if (snapshot.books.isEmpty()) {
                    return@withContext SyncOutcome(true, "本地书库为空，无需同步")
                }

                val total = snapshot.books.size
                var done = 0
                var pending = 0
                for (entry in snapshot.books) {
                    val epub = java.io.File(EpubImporter.epubDir(context), entry.fileName)
                    if (!epub.exists()) continue
                    // 上传前先登记待发布回执（正文成功后清理；失败保留以便补全，方案 §11.6）
                    receipts.upsert(
                        PendingPublication(
                            bookId = entry.bookId,
                            fileName = entry.fileName,
                            size = entry.size,
                            coverPath = null,
                            coverVersion = null,
                            savedAt = System.currentTimeMillis(),
                        )
                    )
                    try {
                        // 上传正文（同名覆盖），封面回传本地已提取的 PNG（无封面则返回 null，不写 coverPath）
                        val coverPng = entry.coverPng
                        service.uploadBook(
                            epub,
                            entry.fileName,
                            makeCover = if (coverPng != null) {
                                { com.yuezhou.reader.core.protocol.CoverFile.encode(coverPng) }
                            } else null,
                        )
                        // 注意：进度上传不在此处进行。方案 §5.6 的进度同步是「逐书、在阅读页
                        // 由用户主动触发的两步交互」，且 CFI→XPointer 转换依赖 WebView 真实 DOM，
                        // 整库推送无 WebView 上下文。进度同步统一走阅读页「进度同步面板」。
                        // 全部成功 → 清理回执
                        receipts.remove(entry.bookId)
                    } catch (e: CloudException) {
                        if (e.code == ErrorCode.UNCERTAIN) {
                            // 正文已上传但列表发布失败 → 保留回执，供后续补全
                            pending++
                        } else {
                            // 其他失败（正文可能未传成功）也保留回执，补全时 stat 确认后再补
                            pending++
                        }
                    }
                    done++
                    onBookProgress?.invoke(done, total)
                }

                // 同步成功，云端书库已变化，失效列表缓存以便下次进入时重新拉取
                invalidateListCache()
                if (pending > 0) {
                    SyncOutcome(true, "同步完成：$done/$total 本，$pending 本待补全（可在设置中重试）")
                } else {
                    SyncOutcome(true, "同步完成：$done/$total 本")
                }
            } catch (e: CloudException) {
                SyncOutcome(false, "同步失败：${e.message}")
            } catch (e: Exception) {
                SyncOutcome(false, "同步失败：${e.message ?: e.toString()}")
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
        val pending = receipts.load()
        if (pending.isEmpty()) return@withContext 0

        val backend = backend() ?: return@withContext 0
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
                    receipts.remove(pub.bookId)
                    repaired++
                } else {
                    // 正文不在云端（上次未传成功），清除回执，交由下次同步重传
                    receipts.remove(pub.bookId)
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
        val backend = backend() ?: throw CloudException(
            ErrorCode.INVALID_INPUT, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘",
        )
        val ns = backend.backendId

        // 主动刷新走网络；否则优先命中当天缓存
        if (!force) {
            val cached = cache.readList(ns)
            if (cached != null) {
                val fromCacheBooks = decodeCachedBooks(cached.raw, backend)
                return@withContext RemoteLibrary(
                    books = fromCacheBooks,
                    backendLabel = backendLabelOf(backend),
                    fromCache = true,
                    fetchedAt = cached.fetchedAt,
                )
            }
        }

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
        result
    }

    /**
     * 「主动更新书库」：强制刷新列表并登记外部上传的 EPUB（方案 §10）。
     * 返回用户可读的结果描述；失败抛异常由 UI 提示。
     */
    suspend fun refreshLibrary(): String = withContext(Dispatchers.IO) {
        val backend = backend() ?: throw CloudException(
            ErrorCode.INVALID_INPUT, "尚未配置云后端，请先在设置页配置 WebDAV 或百度网盘",
        )
        backend.ensureAuthorized()
        val service = CloudService(backend)

        val discovered = service.discoverExternalBooks()
        // 主动更新后失效缓存，下次 listBooks 强制重拉最新列表
        invalidateListCache()

        buildString {
            append("已更新书库")
            if (discovered.added.isNotEmpty()) append("，新增 ${discovered.added.size} 本")
            if (discovered.skipped.isNotEmpty()) append("，跳过 ${discovered.skipped.size} 本（同名冲突）")
            if (discovered.added.isEmpty() && discovered.skipped.isEmpty()) append("，无变化")
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
    suspend fun downloadBook(fileName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val backend = backend() ?: return@withContext Result.failure(
                IllegalStateException("尚未配置云后端"),
            )
            backend.ensureAuthorized()

            val target = java.io.File(EpubImporter.epubDir(context), fileName)
            // 下载到临时文件，成功后原子改名，避免中断留下半成品
            val tmp = java.io.File(target.parentFile, "$fileName.downloading")
            try {
                backend.downloadTo("books/$fileName", tmp, maxBytes = 1024L * 1024 * 1024)
                if (!tmp.isFile || tmp.length() <= 0) {
                    return@withContext Result.failure(IllegalStateException("下载内容为空"))
                }
                if (!tmp.renameTo(target)) {
                    // 跨文件系统 rename 失败时回退拷贝
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            } finally {
                tmp.delete()
            }

            // 提取封面 + 书名并登记本地书库（失败不阻断正文落盘）
            val cover = CoverExtractor.extract(target)
            val title = CoverExtractor.extractTitle(target)
            val registered = LibraryStore(context).registerBook(fileName, cover, title)
            // 下载后该书 local 状态变化，失效列表缓存
            invalidateListCache()
            registered.map { fileName }
        } catch (e: Exception) {
            Result.failure(e)
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
