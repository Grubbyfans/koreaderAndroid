package com.yuezhou.reader.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * XHTML/HTML 文本提取器：把一章 HTML 剥离成纯文本流，
 * 供 [ChapterTextSource] 产出待朗读文本。
 *
 * 基于 jsoup 的真实 HTML 解析，替代早期正则剥离：
 *   - 正确剔除 script/style/template/head 等不可朗读块（含内联脚本、属性里的尖括号）；
 *   - 块级元素之间补换行（段落边界对应语义停顿）；
 *   - 自动解码 HTML 实体与数字实体（jsoup 内置）；
 *   - 保留文本顺序，供上层分句器与锚点映射使用。
 *
 * jsoup 对不规范 HTML 有容错（自动补全/闭合标签），满足 §7.3「后台不能依赖 WebView」的约束，
 * 且比正则方案健壮。
 */
object HtmlTextExtractor {

    /** 不可朗读的整块元素（连同其内容一并剔除）。 */
    private val SKIP_BLOCKS = setOf("script", "style", "head", "title", "noscript", "template")

    /** 块级元素：其后补换行，避免段落粘连。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "nav",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "blockquote", "pre", "table", "tr", "figcaption", "figure",
        "br", "hr",
    )

    /**
     * 从 HTML 源码提取纯文本。
     *
     * @param html 完整章节 HTML 源码。
     * @return 去标签、实体解码后的纯文本（块级边界以换行分隔）。
     */
    fun extract(html: String): String {
        // 用 jsoup 解析（非 XML，容错 HTML）。
        val doc: Document = Jsoup.parse(html)
        // 剔除不可朗读整块（script/style/head 等）。
        for (tag in SKIP_BLOCKS) {
            doc.select(tag).forEach { it.remove() }
        }
        val sb = StringBuilder()
        appendText(doc.body(), sb)
        // 归一化空白：折叠连续空白为单空格，保留换行结构。
        return normalizeWhitespace(sb.toString()).trim()
    }

    /** 深度优先遍历节点，提取文本；块级元素结束处补换行。 */
    private fun appendText(node: Node, sb: StringBuilder) {
        if (node is TextNode) {
            sb.append(node.text())
            return
        }
        if (node !is Element) {
            // 其它节点类型（注释/声明等）递归其子节点。
            for (child in node.childNodes()) appendText(child, sb)
            return
        }
        val tag = node.tagName().lowercase()
        if (tag in SKIP_BLOCKS) return
        val isBlock = tag in BLOCK_TAGS
        for (child in node.childNodes()) appendText(child, sb)
        if (isBlock) sb.append('\n')
    }

    /** 折叠空白但保留换行。 */
    private fun normalizeWhitespace(s: String): String {
        val sb = StringBuilder(s.length)
        var lastWasNewline = false
        for (c in s) {
            when {
                c == '\n' || c == '\r' -> {
                    if (!lastWasNewline) {
                        sb.append('\n')
                        lastWasNewline = true
                    }
                }
                c.isWhitespace() -> {
                    if (!lastWasNewline && sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
                }
                else -> {
                    sb.append(c)
                    lastWasNewline = false
                }
            }
        }
        return sb.toString()
    }
}
