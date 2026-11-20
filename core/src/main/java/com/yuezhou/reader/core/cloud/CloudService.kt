package com.yuezhou.reader.core.cloud

import com.yuezhou.reader.core.protocol.BookNames
import com.yuezhou.reader.core.protocol.LibraryCatalog
import com.yuezhou.reader.core.protocol.LibraryBook
import com.yuezhou.reader.core.protocol.LibraryCatalogData
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/**
 * 云端业务编排层，对齐 BaiduPanSync `modules/cloud_service.lua`。
 *
 * 与后端无关：只依赖 [CloudBackend]，把「读列表 / 上传书 / 同步进度 / 下载书 / 删除书」
 * 等业务拆成可复用的步骤。核心不变量：
 * - 单用户、单设备交替写入；无跨设备并发保护。
 * - 上传前先读最新列表；同名覆盖保留进度与封面。
 * - 写入结果未知（UNCERTAIN）绝不报告成功，交给调用方提示重新查询。
 */
class CloudService(private val backend: CloudBackend) {

    /** 读最新列表；不存在时返回空目录。 */
    suspend fun index(): LibraryCatalogData {
        val raw = try {
            backend.downloadBytes("library.json", LibraryCatalog.MAX_BYTES).toString(Charsets.UTF_8)
        } catch (e: CloudException) {
            if (e.code == ErrorCode.NOT_FOUND) return LibraryCatalogData(emptyList(), raw = buildJsonObject { put("schemaVersion", 2) })
            throw e
        }
        return LibraryCatalog.decode(raw)
    }

    /** 回写列表。 */
    private suspend fun saveIndex(index: LibraryCatalogData) {
        val raw = LibraryCatalog.encode(index)
        backend.uploadSmallJson("library.json", raw, Precondition.Unconditional)
    }

    /** 上传一本书（同名覆盖，进度与封面保留）。 */
    suspend fun uploadBook(
        epubFile: File,
        fileName: String,
        makeCover: (suspend (File) -> String?)? = null,
    ): UploadResult {
        val bookId = BookNames.cloudId(fileName).first
            ?: throw CloudException(ErrorCode.INVALID_INPUT, "不是合法的 EPUB 文件名")

        val index = index()
        // 首次建库：确保 books/、covers/ 目录存在
        if (index.coverDirectoryReady == null) {
            runCatching { backend.mkdir("books") }
            runCatching { backend.mkdir("covers") }
        }

        // 上传正文（同名覆盖）
        backend.upload("books/$fileName", epubFile, Precondition.Unconditional)

        // 构造列表条目，保留旧封面
        val previous = index.books.find { it.bookId == bookId }
        val coverPath = previous?.coverPath
        val coverVersion = previous?.coverVersion

        val uploadedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        var record = LibraryCatalog.buildBookEntry(
            bookId = bookId,
            fileName = fileName,
            size = epubFile.length(),
            uploadedAt = uploadedAt,
            coverPath = coverPath,
            coverVersion = coverVersion,
        )

        // 可选封面
        if (makeCover != null) {
            val cover = runCatching { makeCover(epubFile) }.getOrNull()
            if (cover != null) {
                try {
                    backend.uploadSmallJson("covers/$bookId.json", cover, Precondition.Unconditional)
                    record = LibraryCatalog.buildBookEntry(
                        bookId = bookId,
                        fileName = fileName,
                        size = epubFile.length(),
                        uploadedAt = uploadedAt,
                        coverPath = "covers/$bookId.json",
                        coverVersion = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    )
                } catch (e: CloudException) {
                    // 封面失败仅告警，不阻断
                    if (e.code == ErrorCode.CANCELLED) throw e
                }
            }
        }

        // 发布列表
        val newBooks = index.books.filter { it.bookId != bookId } + record
        val newIndex = index.copy(books = newBooks)
        try {
            saveIndex(newIndex)
        } catch (e: CloudException) {
            // 正文（和封面）已上传成功，仅列表发布失败 → 标注 UNCERTAIN，供上层保留待发布回执补全。
            throw CloudException(ErrorCode.UNCERTAIN, "正文已上传但列表发布失败：${e.message}")
        }

        return UploadResult(record, published = true)
    }

    /** 同步阅读进度（上传）。 */
    suspend fun uploadProgress(bookId: String, progress: ProgressPayload) {
        val path = "progress/$bookId.json"
        val remote = readProgress(path)

        var revision = 0L
        if (remote != null) {
            val decoded = com.yuezhou.reader.core.protocol.ProgressRecords.decode(remote)
            if (decoded.bookId != bookId) throw CloudException(ErrorCode.CONFLICT, "云端进度书名不匹配")
            revision = decoded.revision
        } else {
            // 远端尚无进度记录：确保 progress/ 目录存在再写，避免某些后端对「父目录缺失」返回 CONFLICT。
            ensureProgressDir()
        }

        val record = com.yuezhou.reader.core.protocol.CloudProgress(
            bookId = bookId,
            title = bookId,
            size = progress.size,
            xpointer = progress.xpointer,
            percent = progress.percent,
            revision = revision + 1,
            writeId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
            deviceId = progress.deviceId,
            updatedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString(),
        )
        val raw = com.yuezhou.reader.core.protocol.ProgressRecords.encode(record)
        backend.uploadSmallJson(path, raw, Precondition.Unconditional)
    }

    /** 读取远端进度。 */
    suspend fun downloadProgress(bookId: String): com.yuezhou.reader.core.protocol.CloudProgress? {
        val raw = readProgress("progress/$bookId.json") ?: return null
        return com.yuezhou.reader.core.protocol.ProgressRecords.decode(raw)
    }

    /**
     * 读远端进度正文；不存在返回 null。
     *
     * 对齐 BaiduPanSync `cloud_service.lua` 的 read()：除 NOT_FOUND 外，当访问 `progress/` 路径
     * 返回 CONFLICT（部分后端对「父目录缺失」报 409 而非 404）且 progress 目录本身不存在时，
     * 同样视为「远端不存在」。
     */
    private suspend fun readProgress(path: String): String? = try {
        backend.downloadBytes(path, 64 * 1024).toString(Charsets.UTF_8)
    } catch (e: CloudException) {
        when {
            e.code == ErrorCode.NOT_FOUND -> null
            e.code == ErrorCode.CONFLICT && path.startsWith("progress/") && !progressDirExists() -> null
            else -> throw e
        }
    }

    /** progress 目录是否存在（stat 不抛 NOT_FOUND 即视为存在）。 */
    private suspend fun progressDirExists(): Boolean = try {
        backend.stat("progress")
        true
    } catch (e: CloudException) {
        if (e.code == ErrorCode.NOT_FOUND) false else throw e
    }

    /** 确保 progress 目录存在（不存在则创建；已存在/冲突则忽略，交由后续写入判定）。 */
    private suspend fun ensureProgressDir() {
        if (progressDirExists()) return
        runCatching { backend.mkdir("progress") }
    }

    /** 删除一本书的正文、封面、进度，并更新列表。多文件操作非事务。 */
    suspend fun deleteBook(bookId: String): DeleteResult {
        val index = index()
        val record = index.books.find { it.bookId == bookId }
            ?: throw CloudException(ErrorCode.NOT_FOUND, "书库已无此书")

        val paths = mutableListOf(record.path, "progress/$bookId.json")
        if (record.coverPath != null) paths.add(record.coverPath)

        val deleted = mutableListOf<String>()
        for (p in paths) {
            try {
                backend.delete(p, Precondition.Unconditional)
                deleted.add(p)
            } catch (e: CloudException) {
                if (e.code == ErrorCode.NOT_FOUND) continue
                if (e.code == ErrorCode.CONFLICT) continue
                throw e
            }
        }

        val newIndex = index.copy(books = index.books.filter { it.bookId != bookId })
        saveIndex(newIndex)
        return DeleteResult(record, deleted)
    }

    data class UploadResult(val record: LibraryBook, val published: Boolean)
    data class DeleteResult(val record: LibraryBook, val deleted: List<String>)

    /**
     * 补全待发布条目：只读取最新列表并合并该条目，不重传正文（方案 §11.6）。
     *
     * @param bookId 待补全的 bookId。
     * @param fileName 实际 EPUB 文件名。
     * @param size 字节数。
     * @param coverPath 封面路径（可空）。
     * @param coverVersion 封面版本（有 coverPath 时必填）。
     * @return 补全后的条目；正文在云端不存在时返回 null（需重传）。
     */
    suspend fun repairPublication(
        bookId: String,
        fileName: String,
        size: Long,
        coverPath: String?,
        coverVersion: String?,
    ): LibraryBook? {
        // 先确认正文确已在云端（stat books/<fileName>），避免补全一个不存在的正文
        val exists = try {
            backend.stat("books/$fileName").type == EntryType.File
        } catch (e: CloudException) {
            if (e.code == ErrorCode.NOT_FOUND) false else throw e
        }
        if (!exists) return null

        val index = index()
        val prev = index.books.firstOrNull { it.bookId == bookId }
        val uploadedAt = java.time.OffsetDateTime.now()
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        val record = LibraryCatalog.buildBookEntry(
            bookId = bookId,
            fileName = fileName,
            size = size,
            uploadedAt = uploadedAt,
            coverPath = coverPath ?: prev?.coverPath,
            coverVersion = coverVersion ?: prev?.coverVersion,
        )
        val merged = index.books.filter { it.bookId != bookId } + record
        saveIndex(index.copy(books = merged.sortedBy { it.bookId }))
        return record
    }

    // region 外部上传发现（对齐方案 §10）

    /** 外部上传发现结果：新增/跳过/缺失的统计与明细。 */
    data class DiscoverResult(
        /** 已登记（新增或更新）的 bookId。 */
        val added: List<String>,
        /** 因同名组冲突等跳过的 bookId。 */
        val skipped: List<String>,
        /** 实际写入列表的条目数变化（正=增，0=无变化）。 */
        val changed: Int,
    )

    /**
     * 枚举 `books/` 目录一层，登记外部上传的 EPUB（不递归、不下载正文）。
     *
     * 对齐方案 §10：
     * 1. 读取并校验最新 library.json；
     * 2. 分页枚举 books/ 当前一层；
     * 3. 过滤非 EPUB、空文件、超 1 GiB 和非法云端名称；
     * 4. 同名组整组跳过，不任意覆盖；
     * 5. 合法文件按实际文件名/大小合并，保留已有封面与进度；
     * 6. 无变化不写列表。
     *
     * 注意：本方法只登记「列表」，封面仍由后续上传封面操作单独处理，不在此下载 EPUB。
     */
    suspend fun discoverExternalBooks(): DiscoverResult {
        val index = index()

        // 分页枚举 books/ 一层
        val candidates = mutableListOf<CloudEntry>()
        var page: String? = null
        do {
            val r = try {
                backend.list("books", page)
            } catch (e: CloudException) {
                if (e.code == ErrorCode.NOT_FOUND) break
                throw e
            }
            candidates += r.entries
            page = r.continuation
        } while (page != null)

        // 过滤：只保留合法 EPUB 文件，跳过目录/非 EPUB/空文件/超限/非法名
        val byBookId = linkedMapOf<String, MutableList<CloudEntry>>()
        for (entry in candidates) {
            if (entry.type != EntryType.File) continue
            val fileName = entry.path.substringAfterLast('/')
            val bookId = BookNames.cloudId(fileName).first ?: continue
            if (entry.size < 1 || entry.size > LibraryCatalog.MAX_SIZE) continue
            byBookId.getOrPut(bookId) { mutableListOf() }.add(entry)
        }

        val existingIds = index.books.map { it.bookId }.toSet()
        val added = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val newBooks = mutableListOf<LibraryBook>()

        for ((bookId, entries) in byBookId) {
            // 同名组（>1 个不同文件同名）整组跳过，不任意覆盖
            if (entries.size > 1) {
                skipped.add(bookId)
                continue
            }
            val entry = entries.single()
            val fileName = entry.path.substringAfterLast('/')
            // 已在列表且文件名/大小一致 → 无变化，跳过
            val existing = index.books.firstOrNull { it.bookId == bookId }
            if (existing != null && existing.fileName == fileName && existing.size == entry.size) {
                continue
            }
            // 保留已有封面引用
            val prev = index.books.firstOrNull { it.bookId == bookId }
            val uploadedAt = java.time.OffsetDateTime.now()
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
            newBooks.add(
                LibraryCatalog.buildBookEntry(
                    bookId = bookId,
                    fileName = fileName,
                    size = entry.size,
                    uploadedAt = uploadedAt,
                    coverPath = prev?.coverPath,
                    coverVersion = prev?.coverVersion,
                )
            )
            added.add(bookId)
        }

        val changed = newBooks.size
        if (changed > 0) {
            val merged = index.books.filter { it.bookId !in added } + newBooks
            val newIndex = index.copy(books = merged.sortedBy { it.bookId })
            saveIndex(newIndex)
        }

        return DiscoverResult(added, skipped, changed)
    }

    // endregion
}

/** 进度上传载荷。 */
data class ProgressPayload(
    val xpointer: String,
    val percent: Double? = null,
    val size: Long = 0,
    val deviceId: String,
)
