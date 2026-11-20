package com.yuezhou.reader.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SentencePlannerTest {

    @Test
    fun `中文按句号切分`() {
        val units = listOf(
            speechUnit("第一句。第二句！第三句？", "n1")
        )
        val segments = SentencePlanner().plan(units)
        assertEquals(3, segments.size)
        assertEquals("第一句。", segments[0].text)
        assertEquals("第二句！", segments[1].text)
        assertEquals("第三句？", segments[2].text)
    }

    @Test
    fun `英文按句末标点切分`() {
        val units = listOf(
            speechUnit("Hello world. This is a test! Good.", "n1")
        )
        val segments = SentencePlanner().plan(units)
        assertEquals(3, segments.size)
        assertEquals("Hello world.", segments[0].text)
        assertEquals("This is a test!", segments[1].text)
        assertEquals("Good.", segments[2].text)
    }

    @Test
    fun `中英混合分别切分`() {
        val units = listOf(
            speechUnit("这是一个 test 句子。And this is English.", "n1")
        )
        val segments = SentencePlanner().plan(units)
        assertEquals(2, segments.size)
        assertEquals("这是一个 test 句子。", segments[0].text)
        assertEquals("And this is English.", segments[1].text)
    }

    @Test
    fun `超长句子强制切段`() {
        val long = "字".repeat(300) + "。"
        val units = listOf(speechUnit(long, "n1"))
        val segments = SentencePlanner(maxSegmentChars = 100).plan(units)
        // 300 字 + 句号，按 100 切段 → 4 段
        assertEquals(4, segments.size)
        assertTrue(segments.all { it.text.isNotEmpty() })
    }

    @Test
    fun `锚点映射正确追踪原文偏移`() {
        val units = listOf(
            speechUnit("你好。世界。", "n1")
        )
        val segments = SentencePlanner().plan(units)
        assertEquals(2, segments.size)
        // 第一句 "你好。" 锚点 nodeId=n1，偏移 0..2
        val first = segments[0]
        assertEquals("n1", first.startAnchor.nodeId)
        assertEquals(0, first.startAnchor.offset)
        // 第二句 "世界。" 起始锚点偏移应为 3（"你好。" 占 3 字符）
        assertEquals(3, segments[1].startAnchor.offset)
    }

    @Test
    fun `多文本单元保持顺序与锚点连续性`() {
        val units = listOf(
            speechUnit("第一段。", "n1"),
            speechUnit("第二段。", "n2"),
        )
        val segments = SentencePlanner().plan(units)
        assertEquals(2, segments.size)
        assertEquals("第一段。", segments[0].text)
        assertEquals("n1", segments[0].startAnchor.nodeId)
        assertEquals("第二段。", segments[1].text)
        assertEquals("n2", segments[1].startAnchor.nodeId)
    }

    @Test
    fun `空输入返回空计划`() {
        assertTrue(SentencePlanner().plan(emptyList()).isEmpty())
    }
}
