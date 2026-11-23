package com.yuezhou.reader.core.speech

import com.yuezhou.reader.noveltext.NovelTextParser
import com.yuezhou.reader.noveltext.TextSource

/** 唯一策略入口。系统 TTS 或开关关闭时直接调用旧分句器，不经过新模块。 */
object SpeechTextPlanning {
    data class Result(val segments: List<SpeechSegment>, val version: Int, val fallbackReason: String? = null)

    fun plan(units: List<SpeechTextUnit>, engine: SpeechEngineType, enabled: Boolean): Result {
        if (engine != SpeechEngineType.KOKORO || !enabled) return legacy(units)
        return try {
            val chunks = NovelTextParser().parse(units.map { TextSource(it.text, it.anchor.nodeId, it.anchor.offset, it.paragraphEnd) })
            val segments = chunks.map { chunk ->
                require(chunk.sourcePoints.size == chunk.originalText.length + 1)
                val anchors = chunk.sourcePoints.map { SourceAnchor(it.nodeId, it.offset) }
                SpeechSegment(chunk.originalText, anchors.first(), anchors.last(), anchors, chunk.spokenText,
                    NovelSegmentInfo(chunk.dialogue, chunk.paragraphId, chunk.paragraphBoundaryAfter, chunk.splitReason.name))
            }
            Result(segments, NovelTextParser.VERSION)
        } catch (e: Exception) {
            legacy(units).copy(fallbackReason = e.javaClass.simpleName)
        }
    }

    private fun legacy(units: List<SpeechTextUnit>) = Result(SentencePlanner().plan(units), SpeechPlan.PLANNER_VERSION)

    /** 开关切换及内存降级都通过原文锚点重新定位，不能沿用不同策略的 segmentIndex。 */
    fun rebuild(plan: SpeechPlan, engine: SpeechEngineType, enabled: Boolean, anchor: SourceAnchor?): Pair<SpeechPlan, Int> {
        val units = plan.sourceUnits ?: return plan to plan.segments.indexOfFirst { it.startAnchor == anchor }.coerceAtLeast(0)
        val result = plan(units, engine, enabled)
        val rebuilt = plan.copy(segments = result.segments, plannerVersion = result.version,
            novelParsing = enabled && engine == SpeechEngineType.KOKORO)
        return rebuilt to (anchor?.let { rebuilt.findSegmentForAnchor(it.nodeId, it.offset) } ?: 0).coerceAtLeast(0)
    }
}
