package com.yuezhou.reader.core.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** 协议校验错误分类，与插件 Errors.CODES 对齐。 */
sealed class ProtocolError(val code: String, message: String) : IllegalArgumentException(message) {
    class InvalidInput(message: String) : ProtocolError("INVALID_INPUT", message)
    class Unsupported(message: String) : ProtocolError("UNSUPPORTED", message)
    class TooLarge(message: String) : ProtocolError("INVALID_INPUT", message)
}

internal object ProtocolChecks {

    fun requireBytes(raw: String, max: Long) {
        if (raw.toByteArray(Charsets.UTF_8).size > max)
            throw ProtocolError.TooLarge("内容超过 ${max} 字节上限")
    }

    fun obj(element: JsonElement?, field: String): JsonObject =
        element as? JsonObject ?: throw ProtocolError.InvalidInput("$field 必须是对象")

    fun string(obj: JsonObject, field: String, required: Boolean = true): String? {
        val el = obj[field] ?: if (required) throw ProtocolError.InvalidInput("缺少 $field") else return null
        return (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw ProtocolError.InvalidInput("$field 必须是字符串")
    }

    fun int(obj: JsonObject, field: String): Long {
        val el = obj[field] ?: throw ProtocolError.InvalidInput("缺少 $field")
        val p = el as? JsonPrimitive ?: throw ProtocolError.InvalidInput("$field 必须是数字")
        if (p.isString) throw ProtocolError.InvalidInput("$field 必须是数字")
        val d = p.content.toDoubleOrNull() ?: throw ProtocolError.InvalidInput("$field 必须是数字")
        if (d != Math.floor(d) || d < Long.MIN_VALUE || d > Long.MAX_VALUE)
            throw ProtocolError.InvalidInput("$field 必须是整数")
        return d.toLong()
    }

    fun number(obj: JsonObject, field: String): Double {
        val el = obj[field] ?: throw ProtocolError.InvalidInput("缺少 $field")
        val p = el as? JsonPrimitive ?: throw ProtocolError.InvalidInput("$field 必须是数字")
        if (p.isString) throw ProtocolError.InvalidInput("$field 必须是数字")
        val d = p.content.toDoubleOrNull() ?: throw ProtocolError.InvalidInput("$field 必须是数字")
        if (d.isNaN() || d.isInfinite()) throw ProtocolError.InvalidInput("$field 必须是有限数字")
        return d
    }

    fun schemaVersion(obj: JsonObject, expected: Int) {
        val v = int(obj, "schemaVersion")
        if (v != expected.toLong()) throw ProtocolError.Unsupported("不支持的 schemaVersion: $v")
    }

    /** 显式偏移时间戳：`YYYY-MM-DDTHH:mm:ss+08:00` 或 `Z`，对齐 book_metadata.timestamp。 */
    fun validTimestamp(value: String): Boolean {
        val re = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(.+)$")
        val m = re.matchEntire(value) ?: return false
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        val hour = m.groupValues[4].toInt()
        val minute = m.groupValues[5].toInt()
        val second = m.groupValues[6].toInt()
        if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) return false
        val zone = m.groupValues[7]
        if (zone == "Z") return true
        val zm = Regex("^[+-](\\d{2}):(\\d{2})$").matchEntire(zone) ?: return false
        return zm.groupValues[1].toInt() <= 23 && zm.groupValues[2].toInt() <= 59
    }

    fun hasControl(value: String) = value.any { it.code < 0x20 || it.code == 0x7f }

    /** 严格 UTF-8：Kotlin String 中排除孤立代理项；Lua 端还会拒绝畸形字节序列。 */
    fun isValidUtf8(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c in '\uD800'..'\uDBFF') {
                if (i + 1 >= value.length || value[i + 1] !in '\uDC00'..'\uDFFF') return false
                i += 2
            } else if (c in '\uDC00'..'\uDFFF') return false
            else i++
        }
        return true
    }
}
