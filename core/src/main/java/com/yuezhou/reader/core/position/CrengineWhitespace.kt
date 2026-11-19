package com.yuezhou.reader.core.position

/**
 * CREngine 空白折叠规则。
 *
 * 独立实现，依据 CREngine 引擎的已知行为：
 *   - 文本节点内连续空白（空格/制表/换行/回车/换页）折叠为单个空格。
 *   - 块级元素开头的纯空白文本节点被丢弃（除非在 pre/code 等保留空白的标签内）。
 *   - 偏移按折叠后的文本计数。
 *
 * 这是 CFI ↔ XPointer 偏移映射正确性的关键：CFI 基于原始 DOM 偏移，
 * XPointer 基于 CREngine 折叠后偏移。
 */
object CrengineWhitespace {

    private fun isWhitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    /** 保留空白的标签（CREngine 内置 white-space: pre）。 */
    private val PRE_TAGS = setOf(
        "pre", "code", "listing", "plaintext", "xmp", "textarea",
    )

    /** 块级容器：CREngine 丢弃其开头的纯空白文本节点。 */
    val BLOCK_TAGS = setOf(
        "body", "div", "p", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd", "blockquote", "pre",
        "section", "article", "aside", "header", "footer", "nav", "main",
        "figure", "figcaption", "table", "thead", "tbody", "tfoot",
        "tr", "td", "th", "caption", "address", "details", "summary",
        "form", "fieldset", "center",
    )

    fun preservesWhitespace(tag: String): Boolean = tag in PRE_TAGS

    /**
     * 原始 DOM 偏移 → CREngine 折叠后偏移。
     * 连续空白折叠为单个空格：折叠后只对“运行起点”计数。
     */
    fun toCollapsedOffset(text: String, rawOffset: Int): Int {
        var collapsed = 0
        var i = 0
        val end = minOf(rawOffset, text.length)
        while (i < end) {
            if (!isRunContinuation(text, i)) collapsed++
            i++
        }
        // 超出文本长度的偏移按长度补齐
        return collapsed + maxOf(0, rawOffset - text.length)
    }

    /**
     * CREngine 折叠后偏移 → 原始 DOM 偏移。
     * 找到第 collapsedOffset 个“运行起点”的位置。
     */
    fun toRawOffset(text: String, collapsedOffset: Int): Int {
        var collapsed = 0
        var i = 0
        while (i < text.length) {
            if (isRunContinuation(text, i)) {
                i++
                continue
            }
            if (collapsed == collapsedOffset) return i
            collapsed++
            i++
        }
        return text.length
    }

    /** 位置 i 是否为空白运行的延续（前一个字符也是空白）。 */
    private fun isRunContinuation(text: String, i: Int): Boolean =
        i > 0 && isWhitespace(text[i]) && isWhitespace(text[i - 1])

    /** 判断文本是否全为空白。 */
    fun isBlank(text: String): Boolean = text.all { isWhitespace(it) }
}
