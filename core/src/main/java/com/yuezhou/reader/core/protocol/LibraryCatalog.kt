package com.yuezhou.reader.core.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `library.json` 编解码，严格对齐 BaiduPanSync `modules/library_catalog.lua` + `book_metadata.lua` 的 schemaVersion=2 分支。
 *
 * 上限：4 MiB、10000 本；单本 size 1 B ~ 1 GiB 整数。
 * title 必须等于 bookId；path 必须等于 "books/" + fileName；fileName 必须是合法云端 EPUB 名且 cloudId == bookId。
 * 有 coverPath 必须有 coverVersion（字符串，≤128 字节）；无封面时两者都不出现。
 * contentSha256 / coverPng 字段在 v2 记录中必须不存在。
 * 未知可选字段原样保留（插件在部分写路径会丢弃，但读端不应主动破坏）。
 */
data class LibraryBook(
    val bookId: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val uploadedAt: String,
    val status: String = "ready",
    val schemaVersion: Int = 2,
    val coverPath: String? = null,
    val coverVersion: String? = null,
    /** 原始条目（含未知字段），用于写出时保留其他端写入的可选字段。 */
    val raw: JsonObject
)

data class LibraryCatalogData(
    val books: List<LibraryBook>,
    val coverDirectoryReady: Boolean? = null,
    val schemaVersion: Int = 2,
    val raw: JsonObject
)

object LibraryCatalog {
    const val MAX_BYTES = 4 * 1024 * 1024
    const val MAX_BOOKS = 10_000
    const val MAX_SIZE = 1024L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun emptyJson(): String = "{\"schemaVersion\":2,\"books\":[]}"

    /** 解码并严格校验。非法 JSON、未知版本、超限或字段不符均抛 ProtocolError。 */
    fun decode(rawText: String): LibraryCatalogData {
        ProtocolChecks.requireBytes(rawText, MAX_BYTES.toLong())
        val root = try {
            json.parseToJsonElement(rawText).jsonObject
        } catch (e: Exception) {
            throw ProtocolError.InvalidInput("library.json 不是合法 JSON 对象")
        }
        ProtocolChecks.schemaVersion(root, 2)

        val booksEl = root["books"] ?: throw ProtocolError.InvalidInput("缺少 books")
        val arr = booksEl as? kotlinx.serialization.json.JsonArray
            ?: throw ProtocolError.InvalidInput("books 必须是数组")
        if (arr.size > MAX_BOOKS) throw ProtocolError.InvalidInput("books 超过 $MAX_BOOKS 本")

        val seen = HashSet<String>()
        val books = arr.map { el ->
            val obj = ProtocolChecks.obj(el, "book")
            val book = decodeBook(obj)
            if (!seen.add(book.bookId)) throw ProtocolError.InvalidInput("bookId 重复: ${book.bookId}")
            book
        }
        val coverReady = when (val el = root["coverDirectoryReady"]) {
            null -> null
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (el.isString || el.content !in setOf("true", "false"))
                    throw ProtocolError.InvalidInput("coverDirectoryReady 必须是布尔值")
                el.content.toBoolean()
            }
            else -> throw ProtocolError.InvalidInput("coverDirectoryReady 必须是布尔值")
        }
        return LibraryCatalogData(books, coverReady, 2, root)
    }

    private fun decodeBook(obj: JsonObject): LibraryBook {
        ProtocolChecks.schemaVersion(obj, 2)
        val bookId = ProtocolChecks.string(obj, "bookId")!!
        if (BookNames.valid(bookId) != true) throw ProtocolError.InvalidInput("bookId 非法: $bookId")
        val fileName = ProtocolChecks.string(obj, "fileName")!!
        if (BookNames.cloudId(fileName).first != bookId)
            throw ProtocolError.InvalidInput("fileName 与 bookId 不一致: $fileName")
        val path = ProtocolChecks.string(obj, "path")!!
        if (path != "books/$fileName") throw ProtocolError.InvalidInput("path 必须等于 books/ + fileName")
        val title = ProtocolChecks.string(obj, "title")!!
        if (title != bookId) throw ProtocolError.InvalidInput("title 必须等于 bookId")
        val status = ProtocolChecks.string(obj, "status")!!
        if (status != "ready") throw ProtocolError.InvalidInput("status 必须为 ready")
        val size = ProtocolChecks.int(obj, "size")
        if (size < 1 || size > MAX_SIZE) throw ProtocolError.InvalidInput("size 超出 1B~1GiB")
        val uploadedAt = ProtocolChecks.string(obj, "uploadedAt")!!
        if (!ProtocolChecks.validTimestamp(uploadedAt)) throw ProtocolError.InvalidInput("uploadedAt 格式非法")

        if (obj.containsKey("contentSha256")) throw ProtocolError.InvalidInput("v2 不得含 contentSha256")
        if (obj.containsKey("coverPng")) throw ProtocolError.InvalidInput("列表条目不得含 coverPng")

        val coverPath = ProtocolChecks.string(obj, "coverPath", required = false)
        val coverVersion = ProtocolChecks.string(obj, "coverVersion", required = false)
        if (coverPath != null) {
            if (coverVersion == null) throw ProtocolError.InvalidInput("有 coverPath 必须有 coverVersion")
            if (coverVersion.toByteArray(Charsets.UTF_8).size > 128) throw ProtocolError.InvalidInput("coverVersion 超过 128 字节")
            if (coverPath != "covers/$bookId.json") throw ProtocolError.InvalidInput("coverPath 格式非法")
        } else if (coverVersion != null) {
            throw ProtocolError.InvalidInput("无 coverPath 时不得有 coverVersion")
        }
        return LibraryBook(bookId, fileName, path, size, uploadedAt, status, 2, coverPath, coverVersion, obj)
    }

    /** 编码。先完整校验全部条目，再按原始字段写出（保留未知可选字段），结果不超过 4 MiB。 */
    fun encode(catalog: LibraryCatalogData): String {
        if (catalog.books.size > MAX_BOOKS) throw ProtocolError.InvalidInput("books 超过 $MAX_BOOKS 本")
        // 复用 decode 做严格校验：以已有 raw 重建文本再校验一遍字段合法性。
        val rebuilt = buildJsonObject {
            put("schemaVersion", 2)
            catalog.coverDirectoryReady?.let { put("coverDirectoryReady", it) }
            putJsonArray("books") {
                catalog.books.forEach { book -> add(book.raw) }
            }
        }
        val text = json.encodeToString(JsonObject.serializer(), rebuilt)
        decode(text) // 严格校验
        ProtocolChecks.requireBytes(text, MAX_BYTES.toLong())
        return text
    }
}
