package com.yuezhou.reader.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpeechPlanAnchorTest {

    private fun segment(vararg anchors: SourceAnchor): SpeechSegment {
        val text = "字".repeat((anchors.size - 1).coerceAtLeast(0))
        return SpeechSegment(
            text = text,
            startAnchor = anchors.first(),
            endAnchor = anchors.last(),
            charToSource = anchors.toList(),
        )
    }

    private fun plan(vararg segments: SpeechSegment): SpeechPlan = SpeechPlan(
        bookId = "book",
        fileGeneration = "1-1",
        chapterIndex = 0,
        segments = segments.toList(),
    )

    @Test
    fun `锚点落在片段内部返回该片段`() {
        val p = plan(
            segment(SourceAnchor("n1", 0), SourceAnchor("n1", 5), SourceAnchor("n2", 2)),
            segment(SourceAnchor("n2", 6), SourceAnchor("n3", 4)),
        )
        assertEquals(0, p.findSegmentForAnchor("n1", 3))
        assertEquals(0, p.findSegmentForAnchor("n2", 0))
        assertEquals(1, p.findSegmentForAnchor("n2", 6))
        assertEquals(1, p.findSegmentForAnchor("n3", 0))
    }

    @Test
    fun `锚点正好在片段起点返回该片段`() {
        val p = plan(
            segment(SourceAnchor("n1", 0), SourceAnchor("n1", 9)),
            segment(SourceAnchor("n2", 0), SourceAnchor("n2", 9)),
        )
        assertEquals(1, p.findSegmentForAnchor("n2", 0))
    }

    @Test
    fun `未知节点或空节点返回负一`() {
        val p = plan(segment(SourceAnchor("n1", 0), SourceAnchor("n1", 5)))
        assertEquals(-1, p.findSegmentForAnchor("", 0))
        assertEquals(-1, p.findSegmentForAnchor("n9", 0))
    }

    @Test
    fun `偏移超过该节点所有锚点返回负一`() {
        val p = plan(
            segment(SourceAnchor("n1", 0), SourceAnchor("n2", 3)),
            segment(SourceAnchor("n3", 0)),
        )
        assertEquals(-1, p.findSegmentForAnchor("n1", 10))
    }

    @Test
    fun `默认分段版本与常量一致`() {
        val p = plan(segment(SourceAnchor("n1", 0)))
        assertEquals(SpeechPlan.PLANNER_VERSION, p.plannerVersion)
    }
}
