package com.yuezhou.reader.reader

/**
 * 轻量 XHTML/HTML 文本提取器：把一章 HTML 剥离成纯文本流，
 * 供 [ChapterTextSource] 产出待朗读文本。纯 Kotlin，无 Android 依赖，可后台运行。
 *
 * 提取规则对齐技术方案 §7.2：
 *   - 不朗读脚本（script）、样式（style）、head、注释及隐藏装饰；
 *   - 块级元素结束处补换行（段落边界对应语义停顿）；
 *   - HTML 实体解码（&amp; &lt; &gt; &quot; &#39; 及数字实体）；
 *   - 保留文本顺序，供上层分句器与锚点映射使用。
 *
 * 说明：EPUB 的 XHTML 结构相对规范（无 script 动态注入），正则剥离足够可靠；
 * 若后续遇到不规范 HTML，再评估引入 jsoup。此处刻意不依赖 WebView DOM，
 * 满足 §7.3「后台不能依赖 WebView」的硬约束。
 */
object HtmlTextExtractor {

    /** 不可朗读的整块元素（连同其内容一并剔除）。 */
    private val SKIP_BLOCKS = setOf("script", "style", "head", "title", "noscript", "template")

    /** 块级元素：结束标签后补换行，避免段落粘连。 */
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "nav",
        "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "blockquote", "pre", "table", "tr", "figcaption", "figure",
        "br", "hr",
    )

    private val COMMENT_REGEX = Regex("<!--[\\s\\S]*?-->")
    private val TAG_REGEX = Regex("<[^>]*>")

    /**
     * 从 HTML 源码提取纯文本。
     *
     * @param html 完整章节 HTML 源码。
     * @return 去标签、实体解码后的纯文本（块级边界以换行分隔）。
     */
    fun extract(html: String): String {
        var s = html

        // 1) 剔除注释。
        s = COMMENT_REGEX.replace(s, "")

        // 2) 剔除不可朗读整块（script/style/head 等）。
        for (tag in SKIP_BLOCKS) {
            s = s.replace(Regex("<$tag[^>]*>[\\s\\S]*?</$tag>", RegexOption.IGNORE_CASE), "")
        }

        // 3) 块级元素结束标签后补换行（含自闭合 <br/> 与 <hr/>）。
        for (tag in BLOCK_TAGS) {
            s = s.replace(Regex("</$tag\\s*>", RegexOption.IGNORE_CASE), "\n")
            s = s.replace(Regex("<$tag\\s*/>", RegexOption.IGNORE_CASE), "\n")
        }

        // 4) 剥离剩余所有标签。
        s = TAG_REGEX.replace(s, "")

        // 5) 实体解码（先数字实体，再命名实体）。
        s = decodeNumericEntities(s)
        s = decodeNamedEntities(s)

        // 6) 归一化空白：连续空白折叠为单空格，但保留换行结构。
        s = normalizeWhitespace(s)

        return s.trim()
    }

    /** 数字实体：`&#123;` 与 `&#x1F;`（十进制 / 十六进制）。 */
    private fun decodeNumericEntities(s: String): String {
        var out = s
        val hexRegex = Regex("&#[xX]([0-9a-fA-F]+);")
        val decRegex = Regex("&#(\\d+);")
        out = hexRegex.replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { cp -> cp.toCharOrReplacement().toString() } ?: m.value
        }
        out = decRegex.replace(out) { m ->
            m.groupValues[1].toIntOrNull(10)?.let { cp -> cp.toCharOrReplacement().toString() } ?: m.value
        }
        return out
    }

    /** 常见命名实体（覆盖 EPUB 正文高频出现项）。 */
    private fun decodeNamedEntities(s: String): String {
        var out = s
        out = out.replace("&amp;", "&")
        out = out.replace("&lt;", "<")
        out = out.replace("&gt;", ">")
        out = out.replace("&quot;", "\"")
        out = out.replace("&apos;", "'")
        out = out.replace("&nbsp;", "\u00A0")
        out = out.replace("&hellip;", "…")
        out = out.replace("&mdash;", "—")
        out = out.replace("&ndash;", "–")
        out = out.replace("&ldquo;", "\u201C")
        out = out.replace("&rdquo;", "\u201D")
        out = out.replace("&lsquo;", "\u2018")
        out = out.replace("&rsquo;", "\u2019")
        return out
    }

    /** 把单个码点转成字符；孤立代理项/控制符返回替换符。 */
    private fun Int.toCharOrReplacement(): Char =
        if (this in 0x20..0xD7FF || this in 0xE000..0xFFFD || this in 0x10000..0x10FFFF) {
            // 注意：Char 只能容纳 BMP；补充平面字符此处保留为码点无法直接转单 Char，
            // 朗读场景极少出现，用替换符兜底。
            if (this <= 0xFFFF) this.toChar() else '\uFFFD'
        } else '\uFFFD'

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
