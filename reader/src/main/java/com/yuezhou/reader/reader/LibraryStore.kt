package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.yuezhou.reader.core.protocol.CoverFile
import com.yuezhou.reader.core.protocol.LibraryBook
import com.yuezhou.reader.core.protocol.LibraryCatalog
import com.yuezhou.reader.core.protocol.LibraryCatalogData
import com.yuezhou.reader.core.protocol.ProgressRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
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

    companion object {
        private val catalogMutex = Mutex()
        private val progressMutex = Mutex()
        private val shelfVisibilityLock = Any()
    }

    internal suspend fun <T> withCatalogMutation(block: suspend () -> T): T =
        catalogMutex.withLock { block() }

    private val root: File
        get() = File(context.filesDir, "library").apply { mkdirs() }

    private val libraryFile: File get() = File(root, "library.json")
    /** 正文目录：复用 EpubImporter 的 files/epub/，与 WebViewAssetLoader 的 /epub/ handler 对齐。 */
    private val booksDir: File by lazy { EpubImporter.epubDir(context) }
    private val coversDir: File get() = File(root, "covers").apply { mkdirs() }
    private val progressDir: File get() = File(root, "progress")
    /** 显示名映射：bookId → EPUB 元数据书名（本地展示用，不写入 library.json 以保持协议 title=bookId）。 */
    private val titlesFile: File get() = File(root, "display_titles.json")

    private val authorPrefs by lazy { context.getSharedPreferences("shelf_authors_v1", Context.MODE_PRIVATE) }
    private val authorIndex by lazy {
        ShelfAuthorIndex(
            read = { key -> authorPrefs.getString(key, null) },
            write = { entries ->
                val editor = authorPrefs.edit()
                entries.forEach { (key, value) -> editor.putString(key, value) }
                editor.apply()
            },
        )
    }

    private val shelfPrefs = context.getSharedPreferences("bookshelf_visibility", Context.MODE_PRIVATE)

    fun hiddenBookIds(): Set<String> = shelfPrefs.getStringSet("hidden", emptySet()).orEmpty().toSet()

    fun setBooksHidden(bookIds: Set<String>, hidden: Boolean) = synchronized(shelfVisibilityLock) {
        val previous = hiddenBookIds()
        shelfPrefs.edit().putStringSet("hidden", if (hidden) previous + bookIds else previous - bookIds).apply()
    }

    /** 统一时间戳格式：秒精度、无小数秒，符合 ProtocolChecks.validTimestamp 的严格正则。 */
    private fun now(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))

    /** 当前内存中的书库（列表 + 封面解码缓存），供 UI 直接读取。 */
    data class Snapshot(
        val books: List<Entry>,
        val error: String? = null,
    )

    /** 单本书的展示信息：协议字段 + 本地展示用的封面/进度/书名。 */
    data class Entry(
        val bookId: String,
        val fileName: String,
        val size: Long,
        val coverPng: ByteArray?,
        /** 展示名：优先 EPUB 元数据书名，缺失时回退 bookId。 */
        val title: String,
        /** 阅读进度百分比 0..1，无进度为 null。 */
        val percent: Double?,
        /** 最近一次阅读的 XPointer/CFI，无进度为 null。 */
        val location: String?,
        /** 最近阅读时间戳（epoch millis），用于「最近阅读」排序；无记录为 null。 */
        val lastReadAt: Long?,
        val sourceUri: Uri?,
        val localPath: String,
        val coverFile: File? = null,
        val coverVersion: String? = null,
    )

    /** 读取本地书库快照。书库文件不存在或损坏时返回空列表（不覆盖原文件）。 */
    suspend fun snapshot(includeCoverBytes: Boolean = true, includeProgress: Boolean = true): Snapshot = withContext(Dispatchers.IO) {
        val entries = if (libraryFile.exists()) {
            try {
                val catalog = LibraryCatalog.decode(libraryFile.readText())
                val titles = readTitles()
                catalog.books.mapNotNull { book ->
                    ensureActive()
                    toEntry(book, titles[book.bookId] ?: book.bookId, includeCoverBytes, includeProgress)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("LibraryStore", "无法读取本地书库", e)
                return@withContext Snapshot(emptyList(), "无法读取本地书库，原文件已保留")
            }
        } else {
            emptyList()
        }
        Snapshot(entries)
    }

    /** File presence checks only, for cloud badges, scans and download conflict checks. */
    suspend fun availableBookIds(): Set<String> = availableBookKeys(LibraryBook::bookId)

    internal suspend fun availableFileNames(): Set<String> = availableBookKeys(LibraryBook::fileName)

    private suspend fun availableBookKeys(key: (LibraryBook) -> String): Set<String> = withContext(Dispatchers.IO) {
        val catalog = try {
            readCatalog()
        } catch (e: IOException) {
            Log.w("LibraryStore", "无法读取本地书库", e)
            return@withContext emptySet()
        }
        val directory = booksDir
        catalog.books.asSequence()
            .filter { book -> sourceUri(book) != null || File(directory, book.fileName).isFile }
            .map(key)
            .toSet()
    }

    internal suspend fun uploadEntry(bookId: String): Entry? = withContext(Dispatchers.IO) {
        val book = catalogBook(bookId) ?: return@withContext null
        toEntry(book, readTitles()[bookId] ?: bookId, includeCoverBytes = false, includeProgress = false)
    }

    suspend fun shelfAuthors(books: List<Entry>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        authorIndex.resolve(books, Entry::bookId) { book ->
            ensureActive()
            val source = book.sourceUri
            val stamp = authorStamp(book.fileName, source)
            cachedOrRead(book.bookId, stamp) {
                if (source == null) CoverExtractor.extractAuthors(File(book.localPath))
                else runCatching {
                    context.contentResolver.openFileDescriptor(source, "r")?.use { descriptor ->
                        CoverExtractor.extractAuthors(File("/proc/self/fd/${descriptor.fd}"))
                    }
                }.getOrNull()
            }
        }
    }

    suspend fun authorsFor(book: Entry): List<String> = shelfAuthors(listOf(book))[book.bookId].orEmpty()

    private fun authorStamp(fileName: String, source: Uri?): String? {
        val generation = EpubFileGeneration.of(context, fileName, source)
        val modifiedAt = generation.substringAfterLast('-').toLongOrNull()
        if (modifiedAt == null || modifiedAt <= 0 || generation.contains("--")) return null
        return "${source ?: fileName}|$generation"
    }

    private fun toEntry(
        book: LibraryBook,
        title: String,
        includeCoverBytes: Boolean,
        includeProgress: Boolean,
    ): Entry? {
        val source = sourceUri(book)
        val epub = File(booksDir, book.fileName)
        if (source == null && !epub.isFile) return null
        val coverFile = book.coverPath?.let { File(root, it) }
        val cover = coverFile?.takeIf { includeCoverBytes }?.let(::readCover)
        val progress = if (includeProgress) readProgress(book.bookId) else null
        return Entry(
            bookId = book.bookId,
            fileName = book.fileName,
            size = book.size,
            coverPng = cover,
            title = title,
            percent = progress?.first,
            location = progress?.second,
            lastReadAt = progress?.third,
            sourceUri = source,
            coverFile = coverFile,
            coverVersion = book.coverVersion,
            localPath = source?.let {
                val id = DocumentsContract.getDocumentId(it)
                if (it.authority == "com.android.externalstorage.documents") id else it.toString()
            } ?: epub.path,
        )
    }

    fun readCover(file: File): ByteArray? = try {
        CoverFile.decode(file.readText()).pngBytes
    } catch (_: Exception) {
        null
    }

    private fun sourceUri(book: LibraryBook): Uri? =
        book.raw["localSourceUri"]?.jsonPrimitive?.contentOrNull?.let(Uri::parse)

    private fun readCatalog(): LibraryCatalogData =
        if (libraryFile.exists()) {
            try {
                LibraryCatalog.decode(libraryFile.readText())
            } catch (e: Exception) {
                throw IOException("无法读取本地书库，原文件已保留", e)
            }
        } else {
            LibraryCatalog.decode(LibraryCatalog.emptyJson())
        }

    private fun catalogBook(bookId: String): LibraryBook? =
        readCatalog().books.find { it.bookId == bookId }

    internal suspend fun bookSize(bookId: String): Long = withContext(Dispatchers.IO) {
        catalogBook(bookId)?.size ?: readBookSize(bookId)
    }

    data class BookLocation(val fileName: String, val uri: Uri?, val tree: Uri?)

    suspend fun bookLocation(bookId: String): BookLocation? = withContext(Dispatchers.IO) {
        val book = catalogBook(bookId) ?: return@withContext null
        BookLocation(
            fileName = book.fileName,
            uri = sourceUri(book),
            tree = book.raw["localSourceTree"]?.jsonPrimitive?.contentOrNull?.let(Uri::parse),
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
    internal fun registerBookLocked(
        fileName: String,
        coverPng: ByteArray?,
        title: String? = null,
        source: Uri? = null,
        sourceTree: Uri? = null,
        sourceSize: Long? = null,
        authors: List<String>? = null,
    ): String {
        val target = File(booksDir, fileName)
        check(source != null || target.isFile) { "正文不存在: $fileName" }
        val bookId = com.yuezhou.reader.core.protocol.BookNames.localId(fileName).first
            ?: error("无效 EPUB 文件名: $fileName")
        val catalog = readCatalog()
        val previous = catalog.books.firstOrNull { it.bookId == bookId }
        val newCover = coverPng?.takeIf { it.isNotEmpty() }?.let(CoverFile::encode)
        val coverPath = if (newCover != null) "covers/$bookId.json" else previous?.coverPath
        val coverVersion = if (newCover != null) java.util.UUID.randomUUID().toString().replace("-", "")
        else previous?.coverVersion
        val updatedCatalog = updatedCatalog(
            catalog, bookId, fileName, sourceSize ?: target.length(), coverPath, coverVersion, source, sourceTree,
        )
        if (newCover == null) {
            libraryFile.writeAtomically(updatedCatalog)
        } else {
            val coverFile = File(coversDir, "$bookId.json")
            val oldCover = coverFile.takeIf { it.exists() }?.readText()
            coverFile.writeAtomically(newCover)
            try {
                libraryFile.writeAtomically(updatedCatalog)
            } catch (e: Exception) {
                if (oldCover != null) coverFile.writeAtomically(oldCover) else coverFile.delete()
                throw e
            }
        }
        if (!title.isNullOrBlank()) writeTitle(bookId, title)
        try {
            val stamp = if (authors != null) authorStamp(fileName, source) else null
            if (stamp != null && authors != null) authorIndex.record(bookId, stamp, authors)
            else authorPrefs.edit().remove(bookId).apply()
        } catch (e: Exception) {
            Log.w("LibraryStore", "无法保存作者索引", e)
        }
        setBooksHidden(setOf(bookId), false)
        return bookId
    }

    private fun updatedCatalog(
        catalog: LibraryCatalogData,
        bookId: String,
        fileName: String,
        size: Long,
        coverPath: String?,
        coverVersion: String?,
        source: Uri?,
        sourceTree: Uri?,
    ): String {
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
            source?.let { put("localSourceUri", it.toString()) }
            sourceTree?.let { put("localSourceTree", it.toString()) }
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

        val merged = catalog.books.filter { it.bookId != bookId } + newBook
        return LibraryCatalog.encode(catalog.copy(books = merged.sortedBy { it.bookId }, coverDirectoryReady = true))
    }

    /** 保存阅读进度（CFI/XPointer + 百分比），复用 ProgressRecords 协议。 */
    suspend fun saveProgress(bookId: String, location: String, percent: Double?): Boolean =
        withContext(Dispatchers.IO) {
            progressMutex.withLock {
                try {
                    val size = runCatching { catalogBook(bookId)?.size }.getOrNull() ?: readBookSize(bookId)
                    val record = com.yuezhou.reader.core.protocol.CloudProgress(
                        bookId = bookId,
                        title = bookId,
                        size = size,
                        xpointer = location,
                        percent = percent,
                        revision = 1,
                        writeId = java.util.UUID.randomUUID().toString().replace("-", ""),
                        deviceId = deviceId(),
                        updatedAt = now(),
                    )
                    val directory = progressDir.apply { mkdirs() }
                    File(directory, "$bookId.json").writeAtomically(ProgressRecords.encode(record))
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("LibraryStore", "无法保存阅读进度", e)
                    false
                }
            }
        }

    /** 读取某本书保存的位置（CFI/XPointer）。无进度返回 null。 */
    suspend fun savedLocation(bookId: String): String? = savedProgress(bookId)?.first

    suspend fun savedProgress(bookId: String): Pair<String, Double?>? = withContext(Dispatchers.IO) {
        val progress = readProgress(bookId) ?: return@withContext null
        progress.second?.let { it to progress.first }
    }

    data class BookDeletion(val removed: List<String>, val failures: Map<String, String>)

    /** 删除登记的本机原件与书架条目，保留阅读进度。 */
    suspend fun removeBooks(bookIds: Collection<String>, isOpen: (String) -> Boolean): BookDeletion = withContext(Dispatchers.IO) {
        withCatalogMutation {
            val catalog = readCatalog()
            val existing = catalog.books
            val removed = ArrayList<String>()
            val failures = linkedMapOf<String, String>()
            for (bookId in bookIds) {
                try {
                    check(!isOpen(bookId)) { "请先关闭正在阅读的书籍" }
                    val book = existing.firstOrNull { it.bookId == bookId } ?: error("书架条目已不存在")
                    val source = sourceUri(book)
                    if (source == null) {
                        val file = File(booksDir, book.fileName)
                        check(!file.exists() || file.delete()) { "无法删除应用私有文件" }
                    } else {
                        val document = DocumentFile.fromSingleUri(context, source)
                        check(document?.delete() == true) { "无法删除所选目录中的 EPUB，请检查文件及目录授权" }
                    }
                    // 封面
                    book.coverPath?.let { File(root, it).delete() }
                    File(coversDir, "$bookId.json").delete()
                    // 显示名映射
                    removeTitle(bookId)
                    removed.add(bookId)
                } catch (e: Exception) {
                    failures[bookId] = e.message ?: "删除失败"
                }
            }
            if (removed.isNotEmpty()) {
                // 从 library.json 摘除已移除项
                val kept = existing.filter { it.bookId !in removed }
                libraryFile.writeAtomically(LibraryCatalog.encode(catalog.copy(books = kept)))
                runCatching {
                    val editor = authorPrefs.edit()
                    removed.forEach { editor.remove(it) }
                    editor.apply()
                }
                setBooksHidden(removed.toSet(), false)
            }
            BookDeletion(removed, failures)
        }
    }

    /** 读取进度：返回 (percent, location, lastReadAtMillis)。无进度返回 null。 */
    private fun readProgress(bookId: String): Triple<Double?, String?, Long?>? {
        val f = File(progressDir, "$bookId.json")
        if (!f.exists()) return null
        return try {
            val p = ProgressRecords.decode(f.readText())
            val ts = parseTimestamp(p.updatedAt)
            Triple(p.percent, p.xpointer, ts)
        } catch (e: Exception) {
            null
        }
    }

    /** 解析带偏移的 ISO-8601 时间戳为 epoch millis；失败返回 null。 */
    private fun parseTimestamp(s: String): Long? {
        return try {
            OffsetDateTime.parse(s).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    /** 读取显示名映射（bookId → 书名）。文件不存在或损坏返回空 map。 */
    private fun readTitles(): Map<String, String> {
        if (!titlesFile.exists()) return emptyMap()
        return try {
            org.json.JSONObject(titlesFile.readText()).let { jo ->
                val map = HashMap<String, String>()
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = jo.optString(k)
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 保存显示名映射（bookId → 书名）。 */
    private fun writeTitle(bookId: String, title: String) {
        try {
            val jo = if (titlesFile.exists()) {
                org.json.JSONObject(titlesFile.readText())
            } else org.json.JSONObject()
            jo.put(bookId, title)
            titlesFile.writeAtomically(jo.toString())
        } catch (e: Exception) {
            Log.w("LibraryStore", "无法更新本地书名", e)
        }
    }

    /** 从显示名映射中移除某本书。 */
    private fun removeTitle(bookId: String) {
        try {
            if (!titlesFile.exists()) return
            val jo = org.json.JSONObject(titlesFile.readText())
            if (jo.has(bookId)) jo.remove(bookId)
            titlesFile.writeAtomically(jo.toString())
        } catch (e: Exception) {
            Log.w("LibraryStore", "无法更新本地书名", e)
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
