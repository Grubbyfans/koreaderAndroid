package com.yuezhou.reader.core.cloud.baidu

import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 百度开放平台接口错误码映射，对齐 BaiduPanSync `modules/baidu_http.lua`。
 *
 * 关键：百度 `uk`（用户 ID）、`fs_id`（文件 ID）是超长十进制，可能超过 2^53，
 * 不能用 Double/Long 承载。解析时必须把它们当字符串读。见 [BaiduJson].
 */
internal object BaiduErrors {

    /** 授权相关错误码集合（数字）。字符串错误另见 [AUTH_STRINGS]。 */
    private val AUTH_CODES = setOf<Long>(
        110L, 111L, -6L, 20016L, 20017L, 31045L,
    )

    /** 授权相关 OAuth 字符串错误。 */
    private val AUTH_STRINGS = setOf("invalid_grant", "invalid_client")

    /**
     * 检查百度业务响应中的错误字段并抛出 [CloudException]。
     * `errno` / `error_code` / `error` 任一非 0、非空即视为失败。
     */
    fun checkError(obj: JsonObject) {
        val errno = obj["errno"]?.let { number(it) }
        val errorCode = obj["error_code"]?.let { number(it) }
        val error = obj["error"]?.let { strOrNum(it) }

        if (errno != null && errno != 0L) throw map(errno)
        if (errorCode != null && errorCode != 0L) throw map(errorCode)
        if (error != null && error != "0") throw map(error)
    }

    private fun map(n: Any): CloudException {
        val isAuth = n in AUTH_CODES || n in AUTH_STRINGS
        val code = when {
            isAuth -> ErrorCode.AUTH
            n == -9L || n == "-9" -> ErrorCode.NOT_FOUND
            n == -8L || n == "-8" -> ErrorCode.CONFLICT
            else -> ErrorCode.FORBIDDEN
        }
        val message = if (isAuth) "百度授权无效或已过期，请重新授权"
        else "百度接口拒绝请求（错误码 ${n}）"
        return CloudException(code, message)
    }

    private fun number(el: JsonElement): Long? =
        (el as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun strOrNum(el: JsonElement): String? {
        val p = el as? JsonPrimitive ?: return null
        return p.contentOrNull
    }
}
