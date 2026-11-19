package com.yuezhou.reader.core.protocol

/**
 * 名称身份规则，严格对齐 BaiduPanSync `modules/book_name.lua`。
 *
 * 书籍身份 = 实际 EPUB 文件名去掉最后一个扩展名（大小写不敏感）。
 * 不 trim、不转小写、不做 Unicode 归一化、不按内容摘要判重。
 * 正文名称大小写敏感。云端发布与本地扫描的校验强度不同，不能混用。
 */
object BookNames {

    /** 校验失败原因与插件分类保持一致，便于诊断。 */
    data class Invalid(val code: String, val message: String)

    private val forbiddenCloudChars = setOf('<', '>', ':', '"', '|', '?', '*', '%')

    /** 去掉末尾 .epub（大小写不敏感）。".epub" 共 5 个字符，等价 Lua `value:sub(1,-6)`（按字节）。 */
    private fun stripEpub(value: String): String? =
        if (value.length >= 5 && value.substring(value.length - 5).equals(".epub", ignoreCase = true))
            value.substring(0, value.length - 5)
        else null

    /** 有效 UTF-8：Kotlin String 本身是 UTF-16，需排除孤立代理项与 NUL 之外的非法码点。 */
    private fun isValidUtf8(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\u0000') return false
            if (c in '\uD800'..'\uDBFF') {
                if (i + 1 >= value.length || value[i + 1] !in '\uDC00'..'\uDFFF') return false
                i += 2
            } else if (c in '\uDC00'..'\uDFFF') {
                return false
            } else {
                i++
            }
        }
        return true
    }

    private fun hasControl(value: String) = value.any { it.code < 0x20 || it.code == 0x7f }

    /**
     * 云端文件名 → bookId。对应 `cloud_id(value)`：
     * 必须是 .epub、有效 UTF-8、≤255 UTF-8 字节、不含路径分隔符/控制字符/`< > : " | ? * %`、
     * 去扩展名后非空/非点号/无首尾空白。
     */
    fun cloudId(value: String): Pair<String?, Invalid?> {
        if (stripEpub(value) == null)
            return null to Invalid("INVALID_INPUT", "不是 EPUB 文件名")
        if (!isValidUtf8(value))
            return null to Invalid("INVALID_INPUT", "文件名不是有效 UTF-8")
        if (value.toByteArray(Charsets.UTF_8).size > 255)
            return null to Invalid("INVALID_INPUT", "文件名超过 255 字节")
        if (value.any { it == '/' || it == '\\' || it.code < 0x20 || it == '\u007f' || it in forbiddenCloudChars })
            return null to Invalid("INVALID_INPUT", "文件名含不支持的路径或文件名字符")
        val id = stripEpub(value)
            ?: return null to Invalid("INVALID_INPUT", "不是 EPUB 文件名")
        if (id.isEmpty() || id == "." || id == ".." || id.first().isWhitespace() || id.last().isWhitespace())
            return null to Invalid("INVALID_INPUT", "书名为空、点号或含首尾空格")
        return id to null
    }

    /** 合法 bookId（云端规则）：`cloud_id(id + ".epub") == id`。 */
    fun valid(id: String): Boolean = cloudId("$id.epub").first == id

    /**
     * 本地扫描的文件名 → bookId，对应 `local_id(value)`：
     * 只要求 .epub、无路径分隔符/NUL、有效 UTF-8、去扩展名非空/非点号；
     * 不套用云端 255 字节与禁用字符限制（磁盘已存在的合法长名称不截断）。
     */
    fun localId(value: String): Pair<String?, Invalid?> {
        val lower = value.lowercase()
        if (!lower.endsWith(".epub") || value.contains('/') || value.contains('\\') || value.contains('\u0000') || !isValidUtf8(value))
            return null to Invalid("INVALID_INPUT", "本机 EPUB 文件名无效")
        val id = stripEpub(value)
            ?: return null to Invalid("INVALID_INPUT", "本机 EPUB 文件名无效")
        if (id.isEmpty() || id == "." || id == "..")
            return null to Invalid("INVALID_INPUT", "本机 EPUB 书名无效")
        return id to null
    }

    /** 合法本地 bookId：`local_id(id + ".epub") == id`。 */
    fun localValid(id: String): Boolean = localId("$id.epub").first == id
}
