package com.yuezhou.reader.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlTextExtractorTest {

    @Test
    fun `剥离标签保留正文`() {
        val html = "<html><body><p>你好，世界。</p><p>第二段。</p></body></html>"
        val text = HtmlTextExtractor.extract(html)
        assertEquals("你好，世界。\n第二段。", text)
    }

    @Test
    fun `跳过 script 与 style 内容`() {
        val html = "<html><head><style>body{color:red}</style></head>" +
            "<body><p>正文</p><script>var x=1;</script><p>结尾</p></body></html>"
        val text = HtmlTextExtractor.extract(html)
        assertEquals("正文\n结尾", text)
    }

    @Test
    fun `解码 HTML 实体`() {
        val html = "<p>a&amp;b &lt;c&gt; &#39;引号&#39; &#x201C;双引&#x201D;</p>"
        val text = HtmlTextExtractor.extract(html)
        assertEquals("a&b <c> '引号' “双引”", text)
    }

    @Test
    fun `块级元素补换行`() {
        val html = "<p>一</p><p>二</p><div>三</div>"
        val text = HtmlTextExtractor.extract(html)
        assertEquals("一\n二\n三", text)
    }

    @Test
    fun `br 自闭合补换行`() {
        val html = "<p>第一行<br/>第二行</p>"
        val text = HtmlTextExtractor.extract(html)
        assertEquals("第一行\n第二行", text)
    }

    @Test
    fun `剔除注释`() {
        val html = "<p>前<!-- 这是注释 -->后</p>"
        val text = HtmlTextExtractor.extract(html)
        assertEquals("前后", text)
    }

    @Test
    fun `空输入返回空串`() {
        assertTrue(HtmlTextExtractor.extract("").isEmpty())
        assertTrue(HtmlTextExtractor.extract("<html></html>").isEmpty())
    }
}
