package com.yuezhou.reader.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PcmSegmentTimelineTest {
    @Test
    fun `排队和完成写入都不能触发开始`() {
        val timeline = PcmSegmentTimeline()
        timeline.append(0, 100)
        timeline.markWritten(0)
        timeline.append(1, 100)
        timeline.markWritten(1)
        assertTrue(timeline.drain(0).isEmpty())
        assertEquals(listOf(PcmSegmentTimeline.Event.Started(0)), timeline.drain(1))
        assertTrue(timeline.drain(50).isEmpty())
        assertEquals(listOf(PcmSegmentTimeline.Event.Completed(0)), timeline.drain(100))
        assertEquals(listOf(PcmSegmentTimeline.Event.Started(1)), timeline.drain(101))
    }

    @Test
    fun `长句尚未写完时可以开始高亮但不能提前完成`() {
        val timeline = PcmSegmentTimeline()
        timeline.append(7, 200)
        assertEquals(listOf(PcmSegmentTimeline.Event.Started(7)), timeline.drain(1))
        assertTrue(timeline.drain(200).isEmpty())
        timeline.markWritten(7)
        assertEquals(listOf(PcmSegmentTimeline.Event.Completed(7)), timeline.drain(200))
        assertTrue(timeline.drain(201).isEmpty())
    }

    @Test
    fun `轮询越过多个边界时开始完成按实际顺序且各一次`() {
        val timeline = PcmSegmentTimeline()
        repeat(3) { timeline.append(it, 10); timeline.markWritten(it) }
        assertEquals(listOf(
            PcmSegmentTimeline.Event.Started(0), PcmSegmentTimeline.Event.Completed(0),
            PcmSegmentTimeline.Event.Started(1), PcmSegmentTimeline.Event.Completed(1),
            PcmSegmentTimeline.Event.Started(2), PcmSegmentTimeline.Event.Completed(2),
        ), timeline.drain(30))
        assertTrue(timeline.drain(30).isEmpty())
    }

    @Test
    fun `暂停播放头不变时不重复开始也不推进下一句`() {
        val timeline = PcmSegmentTimeline()
        timeline.append(0, 100)
        timeline.markWritten(0)
        timeline.append(1, 100)
        timeline.markWritten(1)
        timeline.drain(20)
        repeat(10) { assertTrue(timeline.drain(20).isEmpty()) }
        assertEquals(listOf(PcmSegmentTimeline.Event.Completed(0), PcmSegmentTimeline.Event.Started(1)), timeline.drain(120))
    }

    @Test
    fun `重建时间线清空旧边界且marker只指向完整写入的音频`() {
        val timeline = PcmSegmentTimeline()
        timeline.append(0, 100)
        assertEquals(null, timeline.nextMarkerFrame)
        timeline.markWritten(0)
        assertEquals(100L, timeline.nextMarkerFrame)
        timeline.clear()
        timeline.append(5, 50)
        timeline.markWritten(5)
        assertEquals(50L, timeline.nextMarkerFrame)
        assertEquals(listOf(PcmSegmentTimeline.Event.Started(5)), timeline.drain(1))
    }
}
