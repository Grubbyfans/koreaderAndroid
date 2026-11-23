package com.yuezhou.reader.noveltext

import kotlin.math.sqrt

enum class EmotionHint { NEUTRAL, CALM, HAPPY, SAD, ANGRY, FEARFUL, SURPRISED }
enum class DeliveryHint { NARRATION, DIALOGUE, QUESTION, EXCLAMATION, WHISPER, URGENT }
data class AnalysisUnit(val text: String, val dialogue: Boolean, val paragraphId: Int,
    val paragraphBoundaryAfter: Boolean, val quoteCloserAtStart: Char? = null, val nestedQuotes: Boolean = false)
data class EmotionAnalysis(val emotion: EmotionHint, val delivery: DeliveryHint, val confidence: Float,
    val evidence: List<String>, val ruleVersion: String = NovelDeliveryAnalyzer.RULE_VERSION)
data class DeliveryDecision(val analysis: EmotionAnalysis, val speedMultiplier: Float = 1f,
    val pauseTargetMs: Int = 0, val fallbackReason: String? = null) {
    fun speed(userSpeed: Float): Float = (userSpeed * speedMultiplier).coerceIn(0.5f, 2f)
}

/** 规则分数不是统计概率；只从同段落的紧邻上下文获取线索，证据只输出规则 ID。 */
class NovelDeliveryAnalyzer {
    fun analyzeAll(units: List<AnalysisUnit>): List<DeliveryDecision> {
        val decisions = units.mapIndexed { i, unit ->
            val previous = units.getOrNull(i - 1)?.takeIf { it.paragraphId == unit.paragraphId }
            val next = units.getOrNull(i + 1)?.takeIf { it.paragraphId == unit.paragraphId }
            plan(unit, analyze(unit, previous, next))
        }.toMutableList()
        var start = 0
        while (start < units.size) {
            var end = start + 1
            while (end < units.size && units[end].paragraphId == units[start].paragraphId) end++
            var multiplier = 1f
            var changes = 0
            for (i in start until end) {
                if (decisions[i].speedMultiplier != multiplier) changes++
                multiplier = decisions[i].speedMultiplier
            }
            if (changes > 2) for (i in start until end) {
                decisions[i] = decisions[i].copy(speedMultiplier = 1f, pauseTargetMs = 0, fallbackReason = "style_switch_budget")
            }
            start = end
        }
        return decisions
    }

    fun analyze(unit: AnalysisUnit, previous: AnalysisUnit? = null, next: AnalysisUnit? = null): EmotionAnalysis {
        if (!unit.dialogue) return neutral("narration")
        if (unit.nestedQuotes) return neutral("nested_quotes")
        val narration = maskDialogue(unit)
        val contexts = mutableListOf(narration)
        if (previous?.paragraphId == unit.paragraphId && !previous.dialogue) {
            val cue = maskDialogue(previous).takeLast(32).trimEnd()
            if (cue.endsWith(':') || cue.endsWith('：')) contexts += cue
        }
        if (next?.paragraphId == unit.paragraphId && !next.dialogue) {
            val cue = maskDialogue(next).take(32)
            if (ATTRIBUTION_HEAD.containsMatchIn(cue)) contexts += cue
        }
        val matches = mutableListOf<EmotionAnalysis>()
        for ((pattern, analysis) in RULES) {
            if (contexts.any { context -> pattern.findAll(context).any { match ->
                !NEGATION_END.containsMatchIn(context.substring(maxOf(0, match.range.first - 12), match.range.first))
            } }) matches += analysis
        }
        val distinct = matches.distinctBy { it.delivery }
        if (distinct.size > 1) return neutral("conflicting_cues")
        if (distinct.isNotEmpty()) return distinct.first()
        if (!META_QUOTE.containsMatchIn(unit.text) && URGENT_WORD.containsMatchIn(unit.text)) {
            return EmotionAnalysis(EmotionHint.FEARFUL, DeliveryHint.URGENT, 0.82f, listOf("urgent_dialogue"))
        }
        return when {
            unit.text.any { it == '？' || it == '?' } -> EmotionAnalysis(EmotionHint.NEUTRAL, DeliveryHint.QUESTION, 0.65f, listOf("question_mark"))
            unit.text.any { it == '！' || it == '!' } -> EmotionAnalysis(EmotionHint.NEUTRAL, DeliveryHint.EXCLAMATION, 0.55f, listOf("exclamation_mark"))
            else -> neutral("no_reliable_cue")
        }
    }

    fun plan(unit: AnalysisUnit, analysis: EmotionAnalysis): DeliveryDecision {
        if (unit.text.count { it.isLetterOrDigit() } < 6) return DeliveryDecision(analysis, fallbackReason = "short_chunk")
        if (analysis.confidence < 0.8f) return DeliveryDecision(analysis, fallbackReason = "low_confidence")
        return when (analysis.delivery) {
            DeliveryHint.WHISPER -> DeliveryDecision(analysis, 0.98f, if (unit.paragraphBoundaryAfter) 80 else 60)
            DeliveryHint.URGENT -> DeliveryDecision(analysis, 1.02f)
            DeliveryHint.EXCLAMATION -> DeliveryDecision(analysis, 1.01f)
            else -> DeliveryDecision(analysis)
        }
    }

    private fun neutral(reason: String) = EmotionAnalysis(EmotionHint.NEUTRAL, DeliveryHint.NARRATION, 0f, listOf(reason))

    private fun maskDialogue(unit: AnalysisUnit): String = buildString {
        val quotes = ArrayDeque<Char>()
        unit.quoteCloserAtStart?.let { quotes.addLast(it) }
        for ((i, ch) in unit.text.withIndex()) {
            val apostrophe = ch in "'’" && unit.text.getOrNull(i - 1)?.isLetterOrDigit() == true && unit.text.getOrNull(i + 1)?.isLetterOrDigit() == true
            val wasQuoted = quotes.isNotEmpty()
            if (!apostrophe) {
                if (quotes.lastOrNull() == ch) quotes.removeLast()
                else PAIRS[ch]?.let { quotes.addLast(it) }
            }
            append(if (wasQuoted || quotes.isNotEmpty()) ' ' else ch)
        }
    }

    companion object {
        const val RULE_VERSION = "novel-delivery-v1"
        private val PAIRS = mapOf('“' to '”', '‘' to '’', '「' to '」', '『' to '』', '"' to '"', '\'' to '\'')
        private val NEGATION_END = Regex("(?:没有|并未|并非|不曾|不是|假装|佯装|装作)(?:真的|刻意|很)?$")
        private val META_QUOTE = Regex("这个词|这句话|台词|引用|假装|开玩笑|戏文")
        private val URGENT_WORD = Regex("(?:救命|快跑|快走)[！!]")
        private val ATTRIBUTION_HEAD = Regex("^\\s*(?:他|她|少年|少女|老者|男子|女子)")
        private val RULES = listOf(
            Regex("(?:低声|轻声|小声|耳语|喃喃)(?:地)?(?:说道|说|问道|问|道|回答)") to
                EmotionAnalysis(EmotionHint.CALM, DeliveryHint.WHISPER, 0.92f, listOf("explicit_soft_speech")),
            Regex("(?:急促|急忙|焦急)(?:地)?(?:说道|说|喊道|喊|叫道|叫)") to
                EmotionAnalysis(EmotionHint.NEUTRAL, DeliveryHint.URGENT, 0.9f, listOf("explicit_urgent_speech")),
            Regex("(?:怒吼|怒喝|咆哮)(?:道|着说|着喊)?") to
                EmotionAnalysis(EmotionHint.ANGRY, DeliveryHint.EXCLAMATION, 0.9f, listOf("explicit_angry_speech")),
            Regex("(?:哭着|哽咽着|流泪)(?:说道|说|问|道)") to
                EmotionAnalysis(EmotionHint.SAD, DeliveryHint.DIALOGUE, 0.85f, listOf("explicit_sad_speech")),
        )
    }
}

data class PauseResult(val samples: FloatArray, val trailingSilenceMs: Int, val addedPauseMs: Int)

/** 不裁剪模型音频；只在已测尾静音不足时补零，最多增加原时长的 5% 且不超过 80 ms。 */
object NovelPauseCompensation {
    fun apply(samples: FloatArray, sampleRate: Int, targetMs: Int): PauseResult {
        if (samples.isEmpty() || sampleRate <= 0 || targetMs <= 0) return PauseResult(samples, 0, 0)
        val window = maxOf(1, sampleRate / 200)
        var quietFrames = 0
        var end = samples.size
        while (end > 0) {
            val start = maxOf(0, end - window)
            var energy = 0.0
            for (i in start until end) energy += samples[i].toDouble() * samples[i]
            if (sqrt(energy / (end - start)) > 0.001) break
            quietFrames += end - start
            end = start
            if (quietFrames * 1000L / sampleRate >= targetMs) break
        }
        val trailingMs = (quietFrames * 1000L / sampleRate).toInt()
        // 波形末端远离零时不拼接静音，避免人为制造幅度突变。
        if (kotlin.math.abs(samples.last()) > 0.002f) return PauseResult(samples, trailingMs, 0)
        val addedMs = (targetMs - trailingMs).coerceAtLeast(0).coerceAtMost(80)
            .coerceAtMost((samples.size * 1000L / sampleRate / 20).toInt())
        if (addedMs == 0) return PauseResult(samples, trailingMs, 0)
        return PauseResult(samples.copyOf(samples.size + addedMs * sampleRate / 1000), trailingMs, addedMs)
    }
}
