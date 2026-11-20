package com.yuezhou.reader.core.cloud.webdav

import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode

/**
 * WebDAV 请求路径安全校验，对齐 BaiduPanSync `modules/webdav.lua` 的 `safe` / `safe_listing`。
 *
 * 请求路径（safe）：
 * - 不能含 `%`、`\`、控制字符、`?`、`#`；
 * - 不能以 `/` 开头（必须是相对根目录的路径）；
 * - 各段不能是 `.`、`..`；
 * - 不能含 `//`。
 *
 * 枚举路径（safeListing）：
 * - 不能含 `\`、控制字符，不能以 `/` 开头；
 * - 各段不能是 `.`、`..`；
 * - 不能含 `//`。
 * 字面 `%`、`?`、`#` 的条目在枚举时允许上报为「跳过」而不操作。
 */
internal object DavPath {

    private fun hasControl(s: String) = s.any { it.code < 0x20 || it.code == 0x7f }

    fun safe(path: String): Boolean {
        if (path.isEmpty() || path[0] == '/' || path.contains("//")) return false
        if (path.any { it == '%' || it == '\\' || it == '?' || it == '#' }) return false
        if (hasControl(path)) return false
        for (part in path.split('/')) {
            if (part == "." || part == "..") return false
        }
        return true
    }

    fun safeListing(path: String): Boolean {
        if (path.isEmpty() || path[0] == '/' || path.contains("//")) return false
        if (path.any { it == '\\' }) return false
        if (hasControl(path)) return false
        for (part in path.split('/')) {
            if (part == "." || part == "..") return false
        }
        return true
    }

    /** 路径段 RFC 3986 编码：保留 `A-Z a-z 0-9 - . _ ~ /`，其余百分号编码。 */
    fun encode(path: String): String {
        val sb = StringBuilder()
        for (c in path) {
            val keep = c.isLetterOrDigit() || c == '-' || c == '.' || c == '_' || c == '~' || c == '/'
            if (keep) {
                sb.append(c)
            } else {
                val bytes = c.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) sb.append('%').append(("%02X").format(b))
            }
        }
        return sb.toString()
    }

    fun forbidden(message: String): CloudException =
        CloudException(ErrorCode.FORBIDDEN, message)
}
