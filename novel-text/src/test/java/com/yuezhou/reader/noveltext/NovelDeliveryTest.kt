package com.yuezhou.reader.noveltext

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NovelDeliveryTest {
    private val analyzer = NovelDeliveryAnalyzer()
    private fun unit(text: String, paragraph: Int = 0, dialogue: Boolean = true) = AnalysisUnit(text, dialogue, paragraph, true)
    private fun decision(text: String) = analyzer.analyzeAll(listOf(unit(text))).single()

    @Test fun `明确低声提示只做轻微变速且使用规则ID作为证据`() {
        val d = decision("他低声说道：“请大家保持安静，我们马上离开这里。”")
        assertEquals(DeliveryHint.WHISPER, d.analysis.delivery)
        assertEquals(0.98f, d.speedMultiplier)
        assertTrue(d.analysis.confidence >= 0.8f)
        assertEquals(listOf("explicit_soft_speech"), d.analysis.evidence)
        assertTrue(d.pauseTargetMs in 0..80)
    }
    @Test fun `对白内部提到说话方式不当作实际旁白提示`() {
        val d = decision("他说：“他低声说道这几个字，是什么意思？”")
        assertEquals(1f, d.speedMultiplier)
        assertFalse("explicit_soft_speech" in d.analysis.evidence)
    }
    @Test fun `否定伪装冲突和嵌套引号都保守回退`() {
        for (text in listOf("他没有低声说道：“我们现在离开这里。”", "她假装怒吼道：“你们现在都离开这里！”",
            "他低声说道：“大家安静。”又怒喝道：“现在快走！”")) {
            assertEquals(1f, decision(text).speedMultiplier, text)
        }
        val nested = unit("“他说‘快走’，你相信吗？”").copy(nestedQuotes = true)
        assertEquals(1f, analyzer.analyzeAll(listOf(nested)).single().speedMultiplier)
    }
    @Test fun `单独问号感叹号与短对白不改变参数`() {
        for (text in listOf("“你真的相信这件事情吗？”", "“我们终于走到这里了！”", "“救命！”")) {
            val d = decision(text)
            assertEquals(1f, d.speedMultiplier)
            assertEquals(0, d.pauseTargetMs)
        }
    }
    @Test fun `悲伤只标记而不伪造声线`() {
        val d = decision("她哭着说：“我知道了，我们明天再见。”")
        assertEquals(EmotionHint.SAD, d.analysis.emotion)
        assertEquals(1f, d.speedMultiplier)
        assertEquals(0, d.pauseTargetMs)
    }
    @Test fun `上下文仅同段落且限相邻归属`() {
        val cue = unit("他低声说道：", dialogue = false)
        val dialogue = unit("“大家先不要说话，我们慢慢离开。”")
        assertEquals(DeliveryHint.WHISPER, analyzer.analyze(dialogue, cue).delivery)
        assertEquals(DeliveryHint.NARRATION, analyzer.analyze(dialogue.copy(paragraphId = 1), cue).delivery)
    }
    @Test fun `一个段落超过两次语速变化时统一回退`() {
        val units = listOf(
            unit("他低声说道：“大家先不要出声，我们马上离开。”"),
            unit("他急促地喊道：“大家现在马上离开这里。”"),
            unit("她低声说道：“先等一等，我们明天再离开。”"),
        )
        val decisions = analyzer.analyzeAll(units)
        assertTrue(decisions.all { it.speedMultiplier == 1f && it.fallbackReason == "style_switch_budget" })
    }
    @Test fun `速度有绝对限幅且不修改用户设定`() {
        val d = decision("他急促地喊道：“现在马上离开这里！”")
        assertEquals(1.02f, d.speedMultiplier)
        assertEquals(2f, d.speed(2f))
        assertTrue(d.speed(1f) in 0.95f..1.05f)
    }
    @Test fun `长对白切块后仍屏蔽引号内部伪旁白`() {
        val text = "他说：“" + "这是普通对白内容".repeat(8) + "低声说道这几个字是什么意思" + "普通对白".repeat(8) + "。”"
        val chunks = NovelTextParser(32).parse(listOf(TextSource(text, "n")))
        val target = chunks.first { it.originalText.contains("低声说道") }
        assertNotNull(target.quoteCloserAtStart)
        val result = analyzer.analyze(AnalysisUnit(target.originalText, target.dialogue, target.paragraphId, false, target.quoteCloserAtStart))
        assertFalse("explicit_soft_speech" in result.evidence)
    }
    @Test fun `已有尾静音充足时保持同一个音频数组`() {
        val samples = FloatArray(24000) { if (it < 20000) 0.2f else 0f }
        val result = NovelPauseCompensation.apply(samples, 24000, 80)
        assertSame(samples, result.samples)
        assertEquals(0, result.addedPauseMs)
    }
    @Test fun `补停顿有预算且原始音频前缀逐样本不变`() {
        val samples = FloatArray(24000) { if (it == 23999) 0f else 0.2f }
        val result = NovelPauseCompensation.apply(samples, 24000, 80)
        assertEquals(50, result.addedPauseMs)
        assertEquals(25200, result.samples.size)
        assertArrayEquals(samples, result.samples.copyOfRange(0, samples.size))
        assertTrue(result.samples.drop(samples.size).all { it == 0f })
    }
    @Test fun `波形末端非零或未请求停顿时不额外拼接`() {
        val samples = FloatArray(24000) { 0.2f }
        assertSame(samples, NovelPauseCompensation.apply(samples, 24000, 80).samples)
        assertSame(samples, NovelPauseCompensation.apply(samples, 24000, 0).samples)
    }
}
