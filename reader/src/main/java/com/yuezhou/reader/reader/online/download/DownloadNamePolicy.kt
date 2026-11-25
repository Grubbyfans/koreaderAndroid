package com.yuezhou.reader.reader.online.download

import com.yuezhou.reader.reader.EpubImportFiles

/**
 * 外部下载文件名策略：正常且可识别的名称直接使用；占位/无意义名称回退到 OPF 标题；
 * 仍无法确定时交由用户确认。禁止自动追加序号，也禁止用哈希替代文件名。
 */
object DownloadNamePolicy {
    private val genericNames = setOf(
        "download", "downloads", "file", "files", "book", "books", "document", "documents",
        "attachment", "attachments", "redirect", "redirection", "index", "untitled", "unknown", "epub",
        "tmp", "temp", "new", "content", "open", "get", "default", "null",
        "下载", "文件", "书籍", "图书", "未命名", "未知", "附件", "正文",
    )

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return EpubImportFiles.fileName(trimmed)
    }

    fun isMeaningful(raw: String?): Boolean {
        val normalized = normalize(raw) ?: return false
        val base = normalized.removeSuffix(".epub").trim()
        if (base.isEmpty()) return false

        val compact = base.lowercase().replace(Regex("[\\s._\\-]+"), "")
        if (compact.isEmpty()) return false
        if (compact in genericNames) return false
        if (compact.all { it.isDigit() }) return false
        if (compact.matches(Regex("^[0-9a-f]{16,}$"))) return false
        if (compact.matches(Regex("^[0-9a-f]{8}[0-9a-f]{12,}$"))) return false
        // 单个 ASCII 字母/符号通常是下载器生成的占位名；单个汉字仍视为有效书名。
        if (base.length == 1 && base[0].code < 128) return false
        return true
    }
}
