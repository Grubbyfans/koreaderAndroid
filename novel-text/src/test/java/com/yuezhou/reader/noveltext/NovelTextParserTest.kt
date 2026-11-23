package com.yuezhou.reader.noveltext

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NovelTextParserTest {
    private fun parse(text: String, max: Int = 120) = NovelTextParser(max, minOf(12, max)).parse(listOf(TextSource(text, "n1")))

    @Test fun `保留说话提示与完整对白上下文`() {
        val text = "韩立低声说道：“别出声！他们就在门外。”她点了点头。"
        val chunks = parse(text)
        assertEquals(listOf("韩立低声说道：“别出声！他们就在门外。”", "她点了点头。"), chunks.map { it.originalText })
        assertTrue(chunks[0].dialogue)
        assertFalse(chunks[1].dialogue)
    }
    @Test fun `疑问对白与后置说话提示一起分析`() {
        val chunks = parse("“你真的要走？”她低声问道。屋外的雨越下越大。")
        assertEquals("“你真的要走？”她低声问道。", chunks[0].originalText)
    }
    @Test fun `小数缩写和日期不会从点号中间断开`() {
        val text = "Dr. Li 在 U.S.A. 花了39.8元，日期是2026.11.23。随后他离开了。"
        assertEquals(text.substringBefore("随后"), parse(text)[0].originalText)
    }
    @Test fun `逗号冒号分号和省略号不一律切开`() {
        val text = "他想了想……说道：先等一等，天快亮了；我们再走。"
        assertEquals(listOf(text), parse(text).map { it.originalText })
    }
    @Test fun `跨节点位置映射用原文UTF16偏移`() {
        val chunks = NovelTextParser().parse(listOf(TextSource("他说：“", "n1", 7, false), TextSource("别走！”", "n2", 10)))
        assertEquals("他说：“别走！”", chunks.single().originalText)
        assertEquals(TextPoint("n2", 10), chunks.single().sourcePoints[4])
        assertEquals(SourceSpan("n1", 7, 11), chunks.single().sourceSpans[0])
        assertEquals(SourceSpan("n2", 10, 14), chunks.single().sourceSpans[1])
        assertEquals(TextPoint("n2", 14), chunks.single().sourcePoints.last())
    }
    @Test fun `嵌套引号英文撇号括号和单字对白不丢失`() {
        for (text in listOf("“他说‘别怕’，你信吗？”她问道。", "don't stop. 他答道：“好！”", "他看了看（小声说：别动！）便走了。", "“啊！”", "“未闭合的对白……下一行\n继续")) {
            assertEquals(text, parse(text).joinToString("") { it.originalText })
        }
    }
    @Test fun `长句按软边界回退并保留全部字符`() {
        val text = "甲".repeat(15) + "；" + "乙".repeat(15) + "，" + "丙".repeat(20) + "。"
        val chunks = parse(text, 32)
        assertEquals(SplitReason.SEMICOLON, chunks[0].splitReason)
        assertEquals(text, chunks.joinToString("") { it.originalText })
        assertTrue(chunks.all { it.originalText.length <= 32 })
    }
    @Test fun `超长无标点和emoji不漏字重复或拆开代理对`() {
        val text = "中文😀abc".repeat(150)
        val chunks = parse(text, 31)
        assertEquals(text, chunks.joinToString("") { it.originalText })
        assertTrue(chunks.all { !Character.isHighSurrogate(it.originalText.last()) && !Character.isLowSurrogate(it.originalText.first()) })
    }
    @Test fun `清洗仅影响朗读文本不修改原文位置`() {
        val chunk = parse("他\u200B说：  你好。\n").single()
        assertEquals("他说：  你好。\n", chunk.originalText.replace("\u200B", ""))
        assertEquals("他说： 你好。", chunk.spokenText)
        assertEquals(chunk.originalText.length + 1, chunk.sourcePoints.size)
    }
    @Test fun `段落独立且空输入安全`() {
        val chunks = NovelTextParser().parse(listOf(TextSource("章名", "n1"), TextSource("正文", "n2")))
        assertEquals(listOf(0, 1), chunks.map { it.paragraphId })
        assertTrue(chunks.all { it.paragraphBoundaryAfter })
        assertTrue(NovelTextParser().parse(emptyList()).isEmpty())
    }
}
