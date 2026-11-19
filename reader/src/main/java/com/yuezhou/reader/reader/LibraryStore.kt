package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.core.protocol.CoverFile
import com.yuezhou.reader.core.protocol.LibraryBook
import com.yuezhou.reader.core.protocol.LibraryCatalog
import com.yuezhou.reader.core.protocol.LibraryCatalogData
import com.yuezhou.reader.core.protocol.ProgressRecords
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 本地书库存储，对齐方案 §4 的云端目录布局（纯本地，不涉及网络同步）：
 *
 * ```
 * files/library/
 *   library.json          # 书库列表（复用 LibraryCatalog 协议）
 *   books/<fileName>.epub # EPUB 正文
 *   covers/<bookId>.json  # 封面（复用 CoverFile 协议）
 *   progress/<bookId>.json# 阅读进度（复用 ProgressRecords 协议）
 * ```
 *
 * 注意：EPUB 正文当前仍由 [EpubImporter] 存于 `files/epub/`（供 WebViewAssetLoader 的
 * InternalStoragePathHandler 提供）。本 store 统一管理列表/封面/进度元数据，正文路径
 * 以 library.json 的 fileName 为唯一身份，避免重复复制。
 */
class LibraryStore(private val context: Context) {

    private val root: File
        get() = File(context.filesDir, "library").apply { mkdirs() }

    private val libraryFile: File get() = File(root, "library.json")
    /** 正文目录：复用 EpubImporter 的 files/epub/，与 WebViewAssetLoader 的 /epub/ handler 对齐。 */
    private val booksDir: File get() = EpubImporter.epubDir(context)
    private val coversDir: File get() = File(root, "covers").apply { mkdirs() }
    private val progressDir: File get() = File(root, "progress").apply { mkdirs() }

    /** 统一时间戳格式：秒精度、无小数秒，符合 ProtocolChecks.validTimestamp 的严格正则。 */
    private fun now(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    /** 当前内存中的书库（列表 + 封面解码缓存），供 UI 直接读取。 */
    data class Snapshot(
        val books: List<Entry>,
    )

    /** 单本书的展示信息：协议字段 + 本地展示用的封面/进度。 */
    data class Entry(
        val bookId: String,
        val fileName: String,
        val size: Long,
        val coverPng: ByteArray?,
        /** 阅读进度百分比 0..1，无进度为 null。 */
        val percent: Double?,
        /** 最近一次阅读的 XPointer/CFI，无进度为 null。 */
        val location: String?,
    )

    /** 读取本地书库快照。书库文件不存在或损坏时返回空列表（不覆盖原文件）。 */
    suspend fun snapshot(): Snapshot = withContext(Dispatchers.IO) {
        val entries = if (libraryFile.exists()) {
            try {
                val catalog = LibraryCatalog.decode(libraryFile.readText())
                catalog.books.mapNotNull { book -> toEntry(book) }
            } catch (e: Exception) {
                // 书库损坏：按方案约定保留原文件，返回空列表由上层提示，不静默清空。
                emptyList()
            }
        } else {
            emptyList()
        }
        Snapshot(entries)
    }

    private fun toEntry(book: LibraryBook): Entry? {
        // 正文必须真实存在，否则列表条目视为失效（本地删除后残留记录）。
        val epub = File(booksDir, book.fileName)
        if (!epub.exists()) return null
        val cover = book.coverPath?.let { path ->
            val coverFile = File(root, path)
            if (coverFile.exists()) {
                try { CoverFile.decode(coverFile.readText()).pngBytes } catch (e: Exception) { null }
            } else null
        }
        val progress = readProgress(book.bookId)
        return Entry(
            bookId = book.bookId,
            fileName = book.fileName,
            size = book.size,
            coverPng = cover,
            percent = progress?.first,
            location = progress?.second,
        )
    }

    /**
     * 登记一本新书：写封面、登记 library.json，返回 bookId。
     * 同名（bookId 相同）视为覆盖，保留原进度（除非传入新封面）。
     *
     * 注意：正文 EPUB 已由 [EpubImporter] 落盘到 files/epub/，本方法不重复复制，
     * 仅负责元数据（封面 + 列表）与进度目录的维护。
     *
     * @param fileName 目标文件名（含 .epub，已 sanitize，正文须已存在于 files/epub/）。
     * @param coverPng 提取出的封面 PNG（可为 null 表示无封面）。
     */
    suspend fun registerBook(
        fileName: String,
        coverPng: ByteArray?,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val target = File(booksDir, fileName)
            if (!target.exists())
                return@withContext Result.failure(IllegalStateException("正文不存在: $fileName"))

            val bookId = com.yuezhou.reader.core.protocol.BookNames.localId(fileName).first
                ?: return@withContext Result.failure(IllegalStateException("无效 EPUB 文件名: $fileName"))

            // 封面：有则写 covers/<bookId>.json，否则移除旧封面
            var coverPath: String? = null
            var coverVersion: String? = null
            if (coverPng != null && coverPng.isNotEmpty()) {
                val coverFile = File(coversDir, "$bookId.json")
                coverFile.writeText(CoverFile.encode(coverPng))
                coverPath = "covers/$bookId.json"
                coverVersion = java.util.UUID.randomUUID().toString().replace("-", "")
            } else {
                File(coversDir, "$bookId.json").delete()
            }

            // 登记 library.json
            upsertCatalog(bookId, fileName, target.length(), coverPath, coverVersion)

            Result.success(bookId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun upsertCatalog(
        bookId: String,
        fileName: String,
        size: Long,
        coverPath: String?,
        coverVersion: String?,
    ) {
        val existing: List<LibraryBook> = if (libraryFile.exists()) {
            try { LibraryCatalog.decode(libraryFile.readText()).books } catch (e: Exception) { emptyList() }
        } else emptyList()

        val now = now()
        val newRaw = buildJsonObject {
            put("schemaVersion", 2)
            put("status", "ready")
            put("bookId", bookId)
            put("title", bookId)
            put("fileName", fileName)
            put("path", "books/$fileName")
            put("size", size)
            put("uploadedAt", now)
            coverPath?.let { put("coverPath", it) }
            coverVersion?.let { put("coverVersion", it) }
        }
        val newBook = LibraryBook(
            bookId = bookId,
            fileName = fileName,
            path = "books/$fileName",
            size = size,
            uploadedAt = now,
            status = "ready",
            schemaVersion = 2,
            coverPath = coverPath,
            coverVersion = coverVersion,
            raw = newRaw,
        )

        val merged = existing.filter { it.bookId != bookId } + newBook
        val catalog = LibraryCatalogData(
            books = merged.sortedBy { it.bookId },
            coverDirectoryReady = true,
            schemaVersion = 2,
            raw = buildJsonObject { put("schemaVersion", 2); put("coverDirectoryReady", true) },
        )
        libraryFile.writeText(LibraryCatalog.encode(catalog))
    }

    /** 保存阅读进度（CFI/XPointer + 百分比），复用 ProgressRecords 协议。 */
    suspend fun saveProgress(bookId: String, location: String, percent: Double?) {
        withContext(Dispatchers.IO) {
            try {
                val size = File(booksDir, "$bookId.epub").let {
                    if (it.exists()) it.length() else readBookSize(bookId)
                }
                val now = now()
                val record = com.yuezhou.reader.core.protocol.CloudProgress(
                    bookId = bookId,
                    title = bookId,
                    size = size,
                    xpointer = location,
                    percent = percent,
                    revision = 1,
                    writeId = java.util.UUID.randomUUID().toString().replace("-", ""),
                    deviceId = deviceId(),
                    updatedAt = now,
                )
                File(progressDir, "$bookId.json").writeText(ProgressRecords.encode(record))
            } catch (e: Exception) {
                // 进度写失败不阻断阅读，仅忽略。
            }
        }
    }

    /** 按 bookId 查正文文件名（保留实际大小写）；无则返回 "$bookId.epub"。 */
    suspend fun fileNameOf(bookId: String): String = withContext(Dispatchers.IO) {
        if (libraryFile.exists()) {
            try {
                val catalog = LibraryCatalog.decode(libraryFile.readText())
                catalog.books.firstOrNull { it.bookId == bookId }?.fileName?.let { return@withContext it }
            } catch (e: Exception) { /* 忽略，走回退 */ }
        }
        "$bookId.epub"
    }

    /** 读取某本书保存的位置（CFI/XPointer）。无进度返回 null。 */
    suspend fun savedLocation(bookId: String): String? = withContext(Dispatchers.IO) {
        readProgress(bookId)?.second
    }

    private fun readProgress(bookId: String): Pair<Double?, String?>? {
        val f = File(progressDir, "$bookId.json")
        if (!f.exists()) return null
        return try {
            val p = ProgressRecords.decode(f.readText())
            p.percent to p.xpointer
        } catch (e: Exception) {
            null
        }
    }

    private fun readBookSize(bookId: String): Long {
        val f = File(booksDir, "$bookId.epub")
        return if (f.exists()) f.length() else 0
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
