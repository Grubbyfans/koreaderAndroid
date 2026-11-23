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
    fun `连续标点和右引号留在前句不产生单字片段`() {
        val text = "“真的吗？！”他问。等一下……再说。"
        val segments = SentencePlanner().plan(listOf(speechUnit(text, "n1")))
        assertEquals(listOf("“真的吗？！”", "他问。", "等一下……", "再说。"), segments.map { it.text })
        assertEquals(text, segments.joinToString("") { it.text })
        segments.forEach { assertEquals(it.text.length + 1, it.charToSource.size) }
    }

    @Test
    fun `行内样式节点拼成完整句并保留跨节点定位`() {
        val units = listOf(
            SpeechTextUnit("他拿起", SourceAnchor("n1", 0), paragraphEnd = false),
            SpeechTextUnit("金箍棒", SourceAnchor("n2", 0), paragraphEnd = false),
            SpeechTextUnit("向前走去。下一句。", SourceAnchor("n3", 0)),
        )
        val segments = SentencePlanner().plan(units)
        assertEquals(listOf("他拿起金箍棒向前走去。", "下一句。"), segments.map { it.text })
        val plan = SpeechPlan("book", "gen", 0, segments)
        assertEquals(0, plan.findSegmentForAnchor("n2", 1))
        assertEquals(SourceAnchor("n2", 1), segments[0].anchorAt(4))
        assertEquals(1, plan.findSegmentForAnchor("n3", 6))
    }

    @Test
    fun `真实段落不会因为行内合并而串在一起`() {
        val segments = SentencePlanner().plan(listOf(speechUnit("章名", "n1"), speechUnit("正文", "n2")))
        assertEquals(listOf("章名", "正文"), segments.map { it.text })
    }

    @Test
    fun `跨节点长句仍受长度上限约束`() {
        val units = (0..9).map { SpeechTextUnit("字".repeat(30), SourceAnchor("n$it", 0), paragraphEnd = it == 9) }
        val segments = SentencePlanner(maxSegmentChars = 80).plan(units)
        assertEquals(300, segments.sumOf { it.text.length })
        assertTrue(segments.all { it.text.length <= 80 })
    }

    @Test
    fun `空输入返回空计划`() {
        assertTrue(SentencePlanner().plan(emptyList()).isEmpty())
    }
}
