package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.PcmPlayer
import com.yuezhou.reader.core.speech.SpeechSegment
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechSynthesizer
import com.yuezhou.reader.core.speech.SourceAnchor
import com.yuezhou.reader.core.speech.SynthesizedAudio
import kotlinx.coroutines.CompletableDeferred

internal fun planOf(count: Int, prefix: String = "句"): SpeechPlan {
    val segments = (0 until count).map { i ->
        val text = "$prefix$i。"
        SpeechSegment(
            text = text,
            startAnchor = SourceAnchor("n$i", 0),
            endAnchor = SourceAnchor("n$i", text.length),
            charToSource = (0..text.length).map { SourceAnchor("n$i", it) },
        )
    }
    return SpeechPlan("book", "gen", 0, segments)
}

/** 假合成器：默认立即返回可通过健康检查的音频，可注入首轮异常/持续异常与合成闸门。 */
internal class FakeSynthesizer : SpeechSynthesizer {

    data class Call(val index: Int, val text: String, val speed: Float)

    val calls = mutableListOf<Call>()
    val badOnFirstAttempt = mutableSetOf<Int>()
    val alwaysBad = mutableSetOf<Int>()
    var samplesPerSegment = 240
    var sampleRate = 24_000
    var throwing = false
    var released = false
    var gate: CompletableDeferred<Unit>? = null

    private val attempts = mutableMapOf<Int, Int>()

    override suspend fun synthesize(
        text: String,
        segmentIndex: Int,
        speed: Float,
        token: com.yuezhou.reader.core.speech.SessionToken,
    ): SynthesizedAudio {
        calls += Call(segmentIndex, text, speed)
        gate?.await()
        if (throwing) throw IllegalStateException("synthesize boom")
        val attempt = attempts.merge(segmentIndex, 1, Int::plus)!!
        val bad = segmentIndex in alwaysBad ||
            (segmentIndex in badOnFirstAttempt && attempt == 1)
        val samples = FloatArray(samplesPerSegment) { 0.2f }
        if (bad) samples[5] = Float.NaN
        return SynthesizedAudio(samples, sampleRate)
    }

    override suspend fun release() {
        released = true
    }
}

/** 假播放器：按入队顺序手动触发完成回调，可伪造旧代次回调。 */
internal class FakePcmPlayer : PcmPlayer {

    private var listener: PcmPlayer.CompletionListener? = null
    private val inFlight = ArrayDeque<Pair<Int, Long>>()

    val enqueued = mutableListOf<Int>()
    var beginGeneration = -1L
        private set
    var begins = 0
        private set
    var paused = false
        private set
    var stopped = false
        private set
    var released = false
        private set

    override fun setCompletionListener(listener: PcmPlayer.CompletionListener?) {
        this.listener = listener
    }

    override fun begin(generation: Long) {
        beginGeneration = generation
        begins++
        stopped = false
        paused = false
        enqueued.clear()
        inFlight.clear()
    }

    override fun enqueue(segmentIndex: Int, audio: SynthesizedAudio): Boolean {
        if (stopped) return false
        enqueued += segmentIndex
        inFlight += segmentIndex to beginGeneration
        return true
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun stop() {
        stopped = true
        inFlight.clear()
        enqueued.clear()
    }

    override fun release() {
        released = true
    }

    /** 模拟当前队首句子播完。 */
    fun completeNext(): Boolean {
        val item = inFlight.removeFirstOrNull() ?: return false
        listener?.onCompleted(item.first, item.second)
        return true
    }

    /** 伪造指定代次的完成回调（用于旧代次竞态测试）。 */
    fun complete(segmentIndex: Int, generation: Long) {
        listener?.onCompleted(segmentIndex, generation)
    }
}
