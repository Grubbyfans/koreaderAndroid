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
        saveIndex(newIndex)

        return UploadResult(record, published = true)
    }

    /** 同步阅读进度（上传）。 */
    suspend fun uploadProgress(bookId: String, progress: ProgressPayload) {
        val path = "progress/$bookId.json"
        val remote = try {
            backend.downloadBytes(path, 64 * 1024).toString(Charsets.UTF_8)
        } catch (e: CloudException) {
            if (e.code == ErrorCode.NOT_FOUND) null else throw e
        }

        var revision = 0L
        if (remote != null) {
            val decoded = com.yuezhou.reader.core.protocol.ProgressRecords.decode(remote)
            if (decoded.bookId != bookId) throw CloudException(ErrorCode.CONFLICT, "云端进度书名不匹配")
            revision = decoded.revision
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
        val path = "progress/$bookId.json"
        val raw = try {
            backend.downloadBytes(path, 64 * 1024).toString(Charsets.UTF_8)
        } catch (e: CloudException) {
            if (e.code == ErrorCode.NOT_FOUND) return null else throw e
        }
        return com.yuezhou.reader.core.protocol.ProgressRecords.decode(raw)
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
}

/** 进度上传载荷。 */
data class ProgressPayload(
    val xpointer: String,
    val percent: Double? = null,
    val size: Long = 0,
    val deviceId: String,
)
