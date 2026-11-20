package com.yuezhou.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechStateMachineTest {

    private fun planOf(vararg texts: String): SpeechPlan {
        val segments = texts.mapIndexed { i, t ->
            SpeechSegment(
                text = t,
                startAnchor = SourceAnchor("n$i", 0),
                endAnchor = SourceAnchor("n$i", t.length),
                charToSource = (0..t.length).map { SourceAnchor("n$i", it) },
            )
        }
        return SpeechPlan("book1", "gen1", 0, segments)
    }

    @Test
    fun `加载计划后处于空闲状态`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。", "第二句。"))
        assertEquals(SpeechStateMachine.State.IDLE, sm.state)
        assertEquals(0, sm.confirmedSegmentIndex)
    }

    @Test
    fun `播放前进与已确认进度更新`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。", "第二句。", "第三句。"))
        val seg = sm.play().getOrThrow()
        assertEquals("第一句。", seg.text)
        assertEquals(SpeechStateMachine.State.PLAYING, sm.state)

        // TTS 回调确认已播到偏移 2
        sm.onProgress(0, 2)
        assertEquals(0, sm.confirmedSegmentIndex)
        assertEquals(2, sm.position.charOffset)
    }

    @Test
    fun `暂停恢复循环`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。", "第二句。"))
        sm.play()
        sm.pause()
        assertEquals(SpeechStateMachine.State.PAUSED, sm.state)
        val seg = sm.resume().getOrThrow()
        assertEquals("第一句。", seg.text)
        assertEquals(SpeechStateMachine.State.PLAYING, sm.state)
    }

    @Test
    fun `前后句切换`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。", "第二句。", "第三句。"))
        sm.play()
        // 下一句
        val nxt = sm.next().getOrThrow()
        assertEquals("第二句。", nxt.text)
        assertEquals(1, sm.position.segmentIndex)
        // 上一句（重播当前句，避免跳过未读）
        val prev = sm.previous().getOrThrow()
        assertEquals("第一句。", prev.text)
    }

    @Test
    fun `上一句在首句时停留首句不越界`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。", "第二句。"))
        sm.play()
        val prev = sm.previous().getOrThrow()
        assertEquals("第一句。", prev.text)
    }

    @Test
    fun `下一句在末句时停留末句不越界`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。", "第二句。"))
        sm.play()
        sm.next()
        val nxt = sm.next().getOrThrow()
        assertEquals("第二句。", nxt.text)
    }

    @Test
    fun `播完末句后前进返回空`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。"))
        sm.play()
        val advanced = sm.advanceToNext()
        assertNull(advanced)
    }

    @Test
    fun `作废计划清空状态`() {
        val sm = SpeechStateMachine()
        sm.load(planOf("第一句。"))
        sm.play()
        sm.invalidate()
        assertEquals(SpeechStateMachine.State.STOPPED, sm.state)
        assertNull(sm.plan)
    }

    @Test
    fun `无计划时播放失败`() {
        val sm = SpeechStateMachine()
        assertTrue(sm.play().isFailure)
    }
}
