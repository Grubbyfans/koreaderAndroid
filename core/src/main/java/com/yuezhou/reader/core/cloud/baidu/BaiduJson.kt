package com.yuezhou.reader.core.cloud.baidu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 百度 JSON 解析辅助。
 *
 * 最重要的约定：百度 `uk`（用户 ID）、`fs_id`（文件 ID）是可能超过 2^53 的
 * 十进制大整数。JSON 里它们是数字字面量，但任何浮点/长整型解析都会丢精度。
 * 因此这里用 kotlinx-serialization 的 [JsonElement] 树，把数字的**原文**读出来，
 * 需要字符串时用 [asString]，需要大整数比较时用 [java.math.BigDecimal]。
 *
 * 对齐 BaiduPanSync `modules/baidu_http.lua` 中把 `uk`/`fs_id` 加引号保精度的做法。
 */
internal object BaiduJson {

    val json: Json = Json {
        ignoreUnknownKeys = true
        // 不把数字直接解析成 Long/Double；调用方自行决定用 asString 还是 BigDecimal。
        isLenient = false
    }

    /** 解析 JSON 文本为 [JsonElement]，失败抛 CloudException(INVALID_INPUT)。 */
    fun parse(raw: String): JsonObject {
        val el = try {
            json.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw com.yuezhou.reader.core.cloud.CloudException(
                com.yuezhou.reader.core.cloud.ErrorCode.INVALID_INPUT,
                "百度返回的数据无效",
            )
        }
        if (el !is JsonObject) {
            throw com.yuezhou.reader.core.cloud.CloudException(
                com.yuezhou.reader.core.cloud.ErrorCode.INVALID_INPUT,
                "百度返回的数据无效",
            )
        }
        return el
    }

    /** 把数字/字符串统一读成字符串（大数保精度）。字符串原样返回，数字取其原文。 */
    fun asString(el: JsonElement?): String? {
        val p = el as? JsonPrimitive ?: return null
        return p.content
    }

    /** 读整数（小整数场景，如 errno、size、isdir）。 */
    fun asLong(el: JsonElement?): Long? {
        val p = el as? JsonPrimitive ?: return null
        return p.content.toLongOrNull()
    }

    /** 读布尔。 */
    fun asBool(el: JsonElement?): Boolean? {
        val p = el as? JsonPrimitive ?: return null
        if (!p.isString) return p.content == "true"
        return null
    }

    /** 取对象的字符串字段。 */
    fun strField(obj: JsonObject, key: String): String? = asString(obj[key])

    /** 取对象的整数字段。 */
    fun longField(obj: JsonObject, key: String): Long? = asLong(obj[key])

    /** 取对象下数组字段。 */
    fun arrayField(obj: JsonObject, key: String): JsonArray? = obj[key] as? JsonArray
}
