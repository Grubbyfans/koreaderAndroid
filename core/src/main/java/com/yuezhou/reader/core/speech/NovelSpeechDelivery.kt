package com.yuezhou.reader.core.speech

import com.yuezhou.reader.noveltext.AnalysisUnit
import com.yuezhou.reader.noveltext.NovelDeliveryAnalyzer
import com.yuezhou.reader.noveltext.NovelPauseCompensation

/** 给播放层的有限参数，不向模型传入情绪标签，不修改用户音色。 */
data class SegmentDelivery(
    val emotion: String,
    val delivery: String,
    val confidence: Float,
    val ruleIds: List<String>,
    val speedMultiplier: Float,
    val pauseTargetMs: Int,
    val fallbackReason: String?,
    val ruleVersion: String,
)
data class DeliveryAudioMetrics(val trailingSilenceMs: Int, val addedPauseMs: Int)

object NovelSpeechDelivery {
    fun plan(plan: SpeechPlan): List<SegmentDelivery> {
        if (!plan.novelParsing) return emptyList()
        if (plan.segments.any { it.novel == null }) return neutral(plan, "parser_fallback")
        return try {
            NovelDeliveryAnalyzer().analyzeAll(plan.segments.map { segment ->
                val meta = requireNotNull(segment.novel)
                AnalysisUnit(segment.text, meta.dialogue, meta.paragraphId, meta.paragraphBoundaryAfter,
                    meta.quoteCloserAtStart, meta.nestedQuotes)
            }).map { decision ->
                val a = decision.analysis
                SegmentDelivery(a.emotion.name, a.delivery.name, a.confidence, a.evidence, decision.speedMultiplier,
                    decision.pauseTargetMs, decision.fallbackReason, a.ruleVersion)
            }
        } catch (_: Exception) { neutral(plan, "analysis_failed") }
    }

    private fun neutral(plan: SpeechPlan, reason: String) = plan.segments.map {
        SegmentDelivery("NEUTRAL", "NARRATION", 0f, emptyList(), 1f, 0, reason, NovelDeliveryAnalyzer.RULE_VERSION)
    }

    fun compensate(audio: SynthesizedAudio, delivery: SegmentDelivery): Pair<SynthesizedAudio, DeliveryAudioMetrics> {
        val result = NovelPauseCompensation.apply(audio.samples, audio.sampleRate, delivery.pauseTargetMs)
        return SynthesizedAudio(result.samples, audio.sampleRate) to DeliveryAudioMetrics(result.trailingSilenceMs, result.addedPauseMs)
    }
}
