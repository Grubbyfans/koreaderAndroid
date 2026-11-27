package com.yuezhou.reader.core.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 封面文件 `covers/<bookId>.json`：`{"coverPng":"<Base64 WebP/JPEG>"}`。
 * 新写入 WebP/JPEG，读取兼容历史 PNG。图片 ≤200 KiB、尺寸 ≤240×320
 * （尺寸由解码器校验，本层校验字节与 Base64）。
 * coverVersion 是列表中的不透明缓存版本，不在此文件内。
 */
object CoverFile {
    const val MAX_IMAGE_BYTES = 200 * 1024
    const val MAX_JSON_BYTES = 280 * 1024
    const val MAX_WIDTH = 240
    const val MAX_HEIGHT = 320
    private val json = Json { ignoreUnknownKeys = true }

    data class Decoded(val pngBytes: ByteArray)

    fun decode(text: String): Decoded {
        ProtocolChecks.requireBytes(text, MAX_JSON_BYTES.toLong())
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw ProtocolError.InvalidInput("封面文件不是合法 JSON 对象")
        }
        val b64 = ProtocolChecks.string(root, "coverPng")!!
        val bytes = try {
            java.util.Base64.getDecoder().decode(b64)
        } catch (e: Exception) {
            throw ProtocolError.InvalidInput("coverPng 不是合法 Base64")
        }
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES)
            throw ProtocolError.InvalidInput("封面大小超出 200 KiB")
        if (!bytes.isSupportedImage() && !bytes.isLegacyPng())
            throw ProtocolError.InvalidInput("coverPng 不是 WebP、JPEG 或 PNG")
        return Decoded(bytes)
    }

    fun encode(pngBytes: ByteArray): String {
        if (pngBytes.isEmpty() || pngBytes.size > MAX_IMAGE_BYTES)
            throw ProtocolError.InvalidInput("封面大小超出 200 KiB")
        if (!pngBytes.isSupportedImage()) throw ProtocolError.InvalidInput("数据不是 WebP 或 JPEG")
        val obj = buildJsonObject { put("coverPng", java.util.Base64.getEncoder().encodeToString(pngBytes)) }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    // 历史 PNG 只用于读取兼容，新封面仍按当前 WebP/JPEG 约定写入。
    private fun ByteArray.isLegacyPng(): Boolean =
        size >= 8 && this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
            this[2] == 0x4E.toByte() && this[3] == 0x47.toByte() &&
            this[4] == 0x0D.toByte() && this[5] == 0x0A.toByte() &&
            this[6] == 0x1A.toByte() && this[7] == 0x0A.toByte()

    private fun ByteArray.isSupportedImage(): Boolean {
        val jpeg = size >= 3 && this[0] == 0xFF.toByte() &&
            this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()
        val webp = size >= 12 && copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII)) &&
            copyOfRange(8, 12).contentEquals("WEBP".toByteArray(Charsets.US_ASCII))
        return jpeg || webp
    }
}
