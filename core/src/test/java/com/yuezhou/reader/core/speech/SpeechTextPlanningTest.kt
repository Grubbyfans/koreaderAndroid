package com.yuezhou.reader.core.speech

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SpeechTextPlanningTest {
    private val units = listOf(speechUnit("“你真的要走？”她低声问道。2026年，他花了39.8元。", "n1"))
    @Test fun `默认关闭与当前分句和输入逐字段相同`() {
        val old = SentencePlanner(preserveNumericDots = true).plan(units)
        val result = SpeechTextPlanning.plan(units, SpeechEngineType.KOKORO, false)
        assertEquals(old, result.segments)
        assertEquals(SpeechPlan.PLANNER_VERSION, result.version)
        assertTrue(result.segments.all { it.spokenText == null && it.novel == null })
    }
    @Test fun `Kokoro关闭解析仍保留跨行内节点的小数日期并保持原文锚点`() {
        val source = listOf(SpeechTextUnit("价格39.", SourceAnchor("a", 0), false),
            SpeechTextUnit("8元，日期2026.11.23。结束。", SourceAnchor("b", 0)))
        val chunks = SpeechTextPlanning.plan(source, SpeechEngineType.KOKORO, false).segments
        assertEquals("价格39.8元，日期2026.11.23。", chunks.first().text)
        assertEquals(SourceAnchor("b", 0), chunks.first().charToSource[5])
        assertTrue(chunks.all { it.novel == null })
        assertEquals("结束。", chunks.last().text)
        val ended = SpeechTextPlanning.plan(listOf(speechUnit("123. 下一句。", "n")), SpeechEngineType.KOKORO, false)
        assertEquals("123.", ended.segments.first().text)
    }
    @Test fun `系统TTS即使开关开启也不经过新模块`() {
        assertEquals(SentencePlanner().plan(units), SpeechTextPlanning.plan(units, SpeechEngineType.SYSTEM_TTS, true).segments)
    }
    @Test fun `开启保留对白上下文小数并标记独立版本`() {
        val result = SpeechTextPlanning.plan(units, SpeechEngineType.KOKORO, true)
        assertEquals("“你真的要走？”她低声问道。", result.segments[0].text)
        assertTrue(result.segments.last().text.contains("39.8"))
        assertEquals(1001, result.version)
        assertEquals(units.single().text, result.segments.joinToString("") { it.text })
    }
    @Test fun `解析失败回退旧路径且不吞掉内容`() {
        val invalid = listOf(SpeechTextUnit("正常内容。", SourceAnchor("n1", -1)))
        val result = SpeechTextPlanning.plan(invalid, SpeechEngineType.KOKORO, true)
        assertNotNull(result.fallbackReason)
        assertEquals(SentencePlanner().plan(invalid), result.segments)
    }
    @Test fun `切换策略使用原文锚点而不是旧片段索引`() {
        val result = SpeechTextPlanning.plan(units, SpeechEngineType.KOKORO, true)
        val original = SpeechPlan("book", "generation", 0, result.segments, result.version, true, units)
        val anchor = result.segments.last().startAnchor
        val (legacy, from) = SpeechTextPlanning.rebuild(original, SpeechEngineType.KOKORO, false, anchor)
        assertEquals(SentencePlanner(preserveNumericDots = true).plan(units), legacy.segments)
        assertFalse(legacy.novelParsing)
        assertTrue(legacy.segments[from].charToSource.any { it.nodeId == anchor.nodeId && it.offset >= anchor.offset })
    }
    @Test fun `独立解析的句子末位边界不能抢占下一句起点`() {
        val text = listOf(speechUnit("这是一个足够长并且独立完整的第一句话。接下来是第二句话。", "n1"))
        val result = SpeechTextPlanning.plan(text, SpeechEngineType.KOKORO, true)
        val plan = SpeechPlan("book", "g", 0, result.segments, result.version, true, text)
        val next = result.segments[1].startAnchor
        assertEquals(1, plan.findSegmentForAnchor(next.nodeId, next.offset))
    }
}
