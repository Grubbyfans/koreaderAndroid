package com.yuezhou.reader.core.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 云端阅读进度，严格对齐 BaiduPanSync `modules/progress_record.lua` 的 schemaVersion=2 分支。
 *
 * v2 用名称身份：book.id 必须是合法 bookId；**book.contentSha256 必须完全不存在**。
 * updatedAt 必须是 `YYYY-MM-DDTHH:mm:ss±HH:mm`（不接受 Z），最多 64 字节。
 * progress.value 非空、≤8 KiB、无 NUL；percent 可省，范围 0..1。
 * sync.revision 为正整数；writeId/deviceId 非空、≤128 字节、无控制字符。
 * 整体 JSON ≤64 KiB。deviceId 为安装级稳定 ID。
 */
data class CloudProgress(
    val bookId: String,
    val title: String,
    val size: Long,
    val xpointer: String,
    val percent: Double?,
    val revision: Long,
    val writeId: String,
    val deviceId: String,
    val updatedAt: String,
    val raw: JsonObject? = null
)

object ProgressRecords {
    const val MAX_BYTES = 64 * 1024
    const val MAX_XPOINTER_BYTES = 8 * 1024
    const val MAX_TEXT_BYTES = 512
    const val MAX_ID_BYTES = 128
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String): CloudProgress {
        ProtocolChecks.requireBytes(text, MAX_BYTES.toLong())
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolError.InvalidInput("进度记录不是合法 JSON 对象")
        }
        val schema = ProtocolChecks.int(root, "schemaVersion")
        if (schema != 2L) throw ProtocolError.Unsupported("不支持的进度 schemaVersion: $schema")

        val book = ProtocolChecks.obj(root["book"], "book")
        val id = ProtocolChecks.string(book, "id")!!
        if (BookNames.valid(id) != true) throw ProtocolError.InvalidInput("v2 进度 book.id 必须是合法 bookId")
        if (book.containsKey("contentSha256")) throw ProtocolError.InvalidInput("v2 进度不得含 contentSha256")
        val format = ProtocolChecks.string(book, "format")!!
        if (format != "epub") throw ProtocolError.InvalidInput("book.format 必须为 epub")
        val size = ProtocolChecks.int(book, "size")
        if (size < 0) throw ProtocolError.InvalidInput("book.size 必须为非负整数")
        val title = ProtocolChecks.string(book, "title")!!
        if (title.isEmpty() || title.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ||
            ProtocolChecks.hasControl(title) || !ProtocolChecks.isValidUtf8(title))
            throw ProtocolError.InvalidInput("book.title 非法")

        val progress = ProtocolChecks.obj(root["progress"], "progress")
        if (ProtocolChecks.string(progress, "type") != "xpointer")
            throw ProtocolError.InvalidInput("progress.type 必须为 xpointer")
        val value = ProtocolChecks.string(progress, "value")!!
        if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_XPOINTER_BYTES ||
            value.contains('\u0000') || !ProtocolChecks.isValidUtf8(value))
            throw ProtocolError.InvalidInput("progress.value 非法")
        val percentEl = progress["percent"]
        if (percentEl is kotlinx.serialization.json.JsonPrimitive && percentEl.isString)
            throw ProtocolError.InvalidInput("progress.percent 必须是数字")
        val percent = percentEl?.let { ProtocolChecks.number(progress, "percent") }
        if (percent != null && (percent < 0.0 || percent > 1.0))
            throw ProtocolError.InvalidInput("progress.percent 必须在 0..1")

        val sync = ProtocolChecks.obj(root["sync"], "sync")
        val revision = ProtocolChecks.int(sync, "revision")
        if (revision < 1) throw ProtocolError.InvalidInput("sync.revision 必须为正整数")
        val writeId = validOpaqueId(sync, "writeId")
        val deviceId = validOpaqueId(sync, "deviceId")
        val updatedAt = ProtocolChecks.string(sync, "updatedAt")!!
        if (updatedAt.toByteArray(Charsets.UTF_8).size > 64 || ProtocolChecks.hasControl(updatedAt) ||
            !Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}$").matches(updatedAt))
            throw ProtocolError.InvalidInput("sync.updatedAt 必须是带显式偏移的 ISO-8601")

        return CloudProgress(id, title, size, value, percent, revision, writeId, deviceId, updatedAt, root)
    }

    private fun validOpaqueId(sync: JsonObject, field: String): String {
        val v = ProtocolChecks.string(sync, field)!!
        if (v.isEmpty() || v.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES ||
            ProtocolChecks.hasControl(v) || !ProtocolChecks.isValidUtf8(v))
            throw ProtocolError.InvalidInput("sync.$field 非法")
        return v
    }

    /** 编码并回校验，确保写出的 JSON 不违反插件严格校验。 */
    fun encode(record: CloudProgress): String {
        if (record.title.isEmpty() || record.title.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ||
            ProtocolChecks.hasControl(record.title) || !ProtocolChecks.isValidUtf8(record.title))
            throw ProtocolError.InvalidInput("book.title 非法")
        if (record.xpointer.isEmpty() || record.xpointer.toByteArray(Charsets.UTF_8).size > MAX_XPOINTER_BYTES ||
            record.xpointer.contains('\u0000')) throw ProtocolError.InvalidInput("progress.value 非法")
        if (record.percent != null && (record.percent < 0.0 || record.percent > 1.0))
            throw ProtocolError.InvalidInput("progress.percent 非法")
        if (record.revision < 1) throw ProtocolError.InvalidInput("sync.revision 非法")
        for ((k, v) in listOf("writeId" to record.writeId, "deviceId" to record.deviceId)) {
            if (v.isEmpty() || v.toByteArray(Charsets.UTF_8).size > MAX_ID_BYTES || ProtocolChecks.hasControl(v))
                throw ProtocolError.InvalidInput("sync.$k 非法")
        }
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}$").matches(record.updatedAt))
            throw ProtocolError.InvalidInput("sync.updatedAt 非法")

        val obj = buildJsonObject {
            put("schemaVersion", 2)
            put("book", buildJsonObject {
                put("id", record.bookId)
                put("title", record.title)
                put("format", "epub")
                put("size", record.size)
                // 关键：不写 contentSha256
            })
            put("progress", buildJsonObject {
                put("type", "xpointer")
                put("value", record.xpointer)
                record.percent?.let { put("percent", it) }
            })
            put("sync", buildJsonObject {
                put("revision", record.revision)
                put("writeId", record.writeId)
                put("deviceId", record.deviceId)
                put("updatedAt", record.updatedAt)
            })
        }
        val text = json.encodeToString(JsonObject.serializer(), obj)
        decode(text) // 回校验
        ProtocolChecks.requireBytes(text, MAX_BYTES.toLong())
        return text
    }
}
