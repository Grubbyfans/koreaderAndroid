package com.yuezhou.reader.core.cloud.baidu

/**
 * 百度接口的 URL 表单编码，对齐 BaiduPanSync `modules/baidu_http.lua` 的 `escape`/`form`。
 *
 * escape：对 `[^A-Za-z0-9-._~]` 之外的字节做百分号编码（UTF-8）。
 * form：把键值对编码后按 key 排序拼接为 `k=v&k=v`。
 */
internal object BaiduForm {

    fun escape(value: String): String {
        val sb = StringBuilder()
        val bytes = value.toByteArray(Charsets.UTF_8)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            val keep = (c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '.'.code ||
                c == '_'.code || c == '~'.code
            if (keep) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }

    fun form(values: Map<String, String>): String {
        val parts = values.entries
            .sortedBy { it.key }
            .map { "${escape(it.key)}=${escape(it.value)}" }
        return parts.joinToString("&")
    }
}
