package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.AudioHealthCheck
import com.yuezhou.reader.core.speech.KokoroTextNormalizer
import com.yuezhou.reader.core.speech.PcmPlayer
import com.yuezhou.reader.core.speech.PrepareResult
import com.yuezhou.reader.core.speech.SessionToken
import com.yuezhou.reader.core.speech.SpeechEngineConfig
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlaybackBackend
import com.yuezhou.reader.core.speech.SpeechPlaybackEvent
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechStateMachine
import com.yuezhou.reader.core.speech.SpeechSynthesizer
import com.yuezhou.reader.core.speech.SynthesizedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kokoro 朗读协调器（技术方案 §5 / §6 / §7.2）：把「合成—播放—完成事件」串成有界流水线。
 *
 * - 有界预合成：最多准备当前句 + [maxLookahead] 句，且 PCM 总量不超过 [maxPcmBytes]；
 * - 完成驱动：只有 [PcmPlayer] 的完成回调（PCM 实际播完）才推进 [lastCompletedSegmentIndex]，
 *   合成完成、入队、Started 都不推进进度；
 * - 代次过滤：[timeline] 每次 play/停止/跳句/切速都会递增，播放器与合成旧回调一律丢弃；
 * - 释放安全：[release] 先取消并等待流水线结束，再调用 [SpeechSynthesizer.release]；
 * - 语速：只在合成层生效（§7.5），[setRate] 会重合成当前未完成句。
 *
 * [onChapterCompleted] 在「本章最后一句实际播完」时回调一次；回调不得再调用本协调器
 * （它在锁外执行，若需续读下一章，应在回调里返回后由服务层重新 play）。
 */
class SpeechCoordinator(
    private val synthesizer: SpeechSynthesizer,
    private val player: PcmPlayer,
    private val scope: CoroutineScope,
    private val healthCheck: AudioHealthCheck = AudioHealthCheck(),
    private val maxLookahead: Int = 2,
    private val maxPcmBytes: Long = 24L * 1024 * 1024,
    private val onChapterCompleted: (suspend (SpeechPlan) -> Unit)? = null,
) : SpeechPlaybackBackend {

    override val type: SpeechEngineType = SpeechEngineType.KOKORO

    private val eventFlow = MutableSharedFlow<SpeechPlaybackEvent>(extraBufferCapacity = 256)
    override val events: Flow<SpeechPlaybackEvent> = eventFlow.asSharedFlow()

    private val mutex = Mutex()
    private val stateMachine = SpeechStateMachine()
    private val progress = Channel<Unit>(Channel.CONFLATED)
    private val byteLock = Any()

    @Volatile private var activeToken: SessionToken? = null
    @Volatile private var activePlan: SpeechPlan? = null
    @Volatile private var timeline = 0L
    @Volatile private var speed = 1.0f

    @Volatile private var currentIndex = 0
    @Volatile private var completedIndex = -1
    @Volatile private var state = SpeechStateMachine.State.IDLE

    private var pipeline: Job? = null
    private var chapterTriggered = false
    private var pcmPendingBytes = 0L
    private val pendingBytes = HashMap<Int, Long>()
    private val inFlightSegments = java.util.concurrent.atomic.AtomicInteger(0)
    private val playerSignals = Channel<PlayerSignal>(Channel.UNLIMITED)
    private var startedIndex = -1

    /** 当前句序号（Started/seek 设置，不代表完成）。 */
    val currentSegmentIndex: Int get() = currentIndex

    /** 最后实际播完的句子序号；未完成任何句时为 -1。 */
    val lastCompletedSegmentIndex: Int get() = completedIndex

    /** 状态机当前状态快照。 */
    val playbackState: SpeechStateMachine.State get() = state

    /** 最近一次音频健康检查报告（含频谱预警），供诊断读取。 */
    @Volatile
    var lastHealthReport: AudioHealthCheck.Report? = null
        private set

    /** 当前播放头位置（毫秒）；无有效播放时为 -1。长句播放期间用它判断是否仍有进展。 */
    fun playbackPositionMs(): Long = player.positionMs()

    init {
        player.setStartListener { index, generation -> playerSignals.trySend(PlayerSignal(index, generation, true)) }
        player.setCompletionListener { index, generation -> playerSignals.trySend(PlayerSignal(index, generation, false)) }
        scope.launch {
            for (signal in playerSignals) {
                val finished = mutex.withLock {
                    if (signal.started) {
                        handleStarted(signal.index, signal.generation)
                        null
                    } else handleCompleted(signal.index, signal.generation)
                }
                if (finished != null) onChapterCompleted?.invoke(finished)
            }
        }
    }

    override suspend fun prepare(config: SpeechEngineConfig): PrepareResult {
        mutex.withLock {
            if (config.rate > 0f) speed = config.rate
        }
        return PrepareResult(true)
    }

    override suspend fun play(plan: SpeechPlan, fromIndex: Int, token: SessionToken) {
        mutex.withLock { startLocked(plan, fromIndex, token) }
    }

    /**
     * 调整语速（仅 Kokoro 合成层）。播放中/暂停中会以新语速重合成当前未完成句，
     * 已完成进度保持不变。
     */
    suspend fun setRate(rate: Float) {
        mutex.withLock {
            val target = if (rate > 0f) rate else 1f
            if (target == speed) return@withLock
            speed = target
            val plan = activePlan ?: return@withLock
            val token = activeToken ?: return@withLock
            if (state != SpeechStateMachine.State.PLAYING && state != SpeechStateMachine.State.PAUSED) {
                return@withLock
            }
            val wasPaused = state == SpeechStateMachine.State.PAUSED
            val restartIndex = maxOf(stateMachine.currentSegmentIndex, stateMachine.lastCompletedSegmentIndex)
            cancelPipelineLocked()
            stateMachine.play(restartIndex)
            startedIndex = -1
            currentIndex = stateMachine.currentSegmentIndex
            timeline++
            player.begin(timeline)
            state = SpeechStateMachine.State.PLAYING
            pipeline = scope.launch { runPipeline(plan, currentIndex, token, timeline) }
            if (wasPaused) {
                player.pause()
                stateMachine.pause()
                state = SpeechStateMachine.State.PAUSED
            }
        }
    }

    override suspend fun pause() {
        mutex.withLock {
            if (state == SpeechStateMachine.State.PLAYING) {
                stateMachine.pause()
                state = SpeechStateMachine.State.PAUSED
                player.pause()
            }
        }
    }

    override suspend fun resume() {
        mutex.withLock {
            if (state == SpeechStateMachine.State.PAUSED) {
                stateMachine.resume()
                state = SpeechStateMachine.State.PLAYING
                player.resume()
            }
        }
    }

    override suspend fun stop() {
        mutex.withLock { stopLocked() }
    }

    override suspend fun release() {
        mutex.withLock {
            stopLocked()
            synthesizer.release()
            player.release()
            playerSignals.close()
        }
    }

    private suspend fun startLocked(plan: SpeechPlan, fromIndex: Int, token: SessionToken) {
        if (plan.segments.isEmpty()) {
            eventFlow.tryEmit(SpeechPlaybackEvent.Failed(token, "朗读计划为空"))
            state = SpeechStateMachine.State.IDLE
            return
        }
        cancelPipelineLocked()
        player.stop()
        inFlightSegments.set(0)
        activePlan = plan
        activeToken = token
        chapterTriggered = false
        stateMachine.load(plan)
        stateMachine.play(fromIndex)
        startedIndex = -1
        currentIndex = stateMachine.currentSegmentIndex
        completedIndex = stateMachine.lastCompletedSegmentIndex
        timeline++
        player.begin(timeline)
        state = SpeechStateMachine.State.PLAYING
        pipeline = scope.launch { runPipeline(plan, currentIndex, token, timeline) }
    }

    private suspend fun stopLocked() {
        timeline++
        activeToken = null
        cancelPipelineLocked()
        player.stop()
        stateMachine.stop()
        state = SpeechStateMachine.State.STOPPED
        currentIndex = stateMachine.currentSegmentIndex
        completedIndex = stateMachine.lastCompletedSegmentIndex
        chapterTriggered = false
    }

    private suspend fun cancelPipelineLocked() {
        // 在停止写入前失效旧回调，防止取消中的 write 失败污染新播放。
        timeline++
        val job = pipeline ?: return
        pipeline = null
        job.cancel()
        player.stop()
        job.join()
        synchronized(byteLock) {
            pendingBytes.clear()
            pcmPendingBytes = 0
        }
        inFlightSegments.set(0)
    }

    private suspend fun runPipeline(
        plan: SpeechPlan,
        start: Int,
        token: SessionToken,
        generation: Long,
    ) {
        coroutineScope {
            val ready = Channel<Ready>(maxLookahead + 1)
            val producer = launch { produce(plan, start, token, generation, ready) }
            try {
                consume(ready, token, generation, start)
            } finally {
                producer.cancel()
                ready.cancel()
            }
        }
    }

    private suspend fun produce(
        plan: SpeechPlan,
        start: Int,
        token: SessionToken,
        generation: Long,
        out: Channel<Ready>,
    ) {
        var next = start
        while (currentCoroutineContext().isActive && isCurrent(token, generation) && next < plan.segments.size) {
            while (isCurrent(token, generation) && !canSynthesize(next, start)) {
                progress.receive()
            }
            if (!isCurrent(token, generation)) break
            val segment = plan.segments[next]
            if (segment.isBlank) {
                eventFlow.tryEmit(SpeechPlaybackEvent.Failed(token, "空片段 $next"))
                break
            }
            val audio = synthesizeChecked(segment, next, token, generation)
            if (audio == null) break
            val bytes = audio.samples.size.toLong() * Float.SIZE_BYTES
            synchronized(byteLock) {
                pendingBytes[next] = bytes
                pcmPendingBytes += bytes
            }
            if (!isCurrent(token, generation)) break
            out.send(Ready(next, audio))
            next++
        }
        out.close()
    }

    private fun canSynthesize(next: Int, start: Int): Boolean {
        val playing = maxOf(completedIndex + 1, start)
        if (next <= playing) return true
        if (next > playing + maxLookahead) return false
        return synchronized(byteLock) { pcmPendingBytes < maxPcmBytes }
    }

    private suspend fun synthesizeChecked(
        segment: com.yuezhou.reader.core.speech.SpeechSegment,
        index: Int,
        token: SessionToken,
        generation: Long,
    ): SynthesizedAudio? {
        val spokenText = KokoroTextNormalizer.normalize(segment.text)
        // 保留原片段的播放完成事件与锚点；纯标点只占短暂停顿，不触发 native 或空音频重试。
        if (spokenText.isEmpty()) return KokoroTextNormalizer.punctuationPause()
        var reason = "unknown"
        repeat(2) {
            val audio = try {
                synthesizer.synthesize(spokenText, index, speed, token)
            } catch (c: CancellationException) {
                throw c
            } catch (oom: OutOfMemoryError) {
                if (isCurrent(token, generation)) {
                    eventFlow.tryEmit(SpeechPlaybackEvent.Failed(token, "Kokoro 合成内存不足", memoryPressure = true))
                }
                return null
            } catch (t: Throwable) {
                reason = "${t.javaClass.simpleName}: ${t.message}"
                return@repeat
            }
            if (!isCurrent(token, generation)) return null
            val report = healthCheck.check(audio.samples, audio.sampleRate)
            lastHealthReport = report
            if (report.ok) return audio
            reason = report.summary()
        }
        if (isCurrent(token, generation)) {
            eventFlow.tryEmit(SpeechPlaybackEvent.Failed(token, "第 $index 句音频异常：$reason"))
        }
        return null
    }

    private suspend fun consume(
        ready: Channel<Ready>,
        token: SessionToken,
        generation: Long,
        start: Int,
    ) {
        var expected = start
        while (currentCoroutineContext().isActive && isCurrent(token, generation)) {
            val immediate = ready.tryReceive().getOrNull()
            val item = if (immediate != null) {
                immediate
            } else {
                if (state == SpeechStateMachine.State.PLAYING && inFlightSegments.get() == 0) {
                    eventFlow.tryEmit(SpeechPlaybackEvent.Buffering(token, expected))
                }
                ready.receiveCatching().getOrNull() ?: break
            }
            if (!isCurrent(token, generation)) break
            inFlightSegments.incrementAndGet()
            if (!player.enqueue(item.index, item.audio)) {
                if (isCurrent(token, generation)) {
                    eventFlow.tryEmit(
                        SpeechPlaybackEvent.Failed(token, "第 ${item.index} 句入队失败"),
                    )
                }
                break
            }
            expected = item.index + 1
        }
    }

    private fun handleStarted(index: Int, generation: Long) {
        if (generation != timeline || index <= startedIndex) return
        val plan = activePlan ?: return
        val token = activeToken ?: return
        if (index !in plan.segments.indices) return
        startedIndex = index
        stateMachine.onSegmentStarted(index)
        currentIndex = index
        eventFlow.tryEmit(SpeechPlaybackEvent.SegmentStarted(token, index))
    }

    private suspend fun handleCompleted(index: Int, generation: Long): SpeechPlan? {
        if (generation != timeline) return null
        val plan = activePlan ?: return null
        val token = activeToken ?: return null
        if (index <= completedIndex) return null
        if (index !in plan.segments.indices) return null
        stateMachine.onSegmentCompleted(index)
        currentIndex = stateMachine.currentSegmentIndex
        completedIndex = stateMachine.lastCompletedSegmentIndex
        synchronized(byteLock) {
            pcmPendingBytes = (pcmPendingBytes - (pendingBytes.remove(index) ?: 0L)).coerceAtLeast(0L)
        }
        inFlightSegments.updateAndGet { if (it > 0) it - 1 else 0 }
        eventFlow.tryEmit(SpeechPlaybackEvent.SegmentCompleted(token, index))
        progress.trySend(Unit)
        if (index == plan.segments.lastIndex && !chapterTriggered) {
            chapterTriggered = true
            return plan
        }
        return null
    }

    private fun isCurrent(token: SessionToken, generation: Long): Boolean =
        activeToken == token && timeline == generation

    private data class PlayerSignal(val index: Int, val generation: Long, val started: Boolean)

    private data class Ready(val index: Int, val audio: SynthesizedAudio)
}
