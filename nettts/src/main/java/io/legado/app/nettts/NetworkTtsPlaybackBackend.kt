package io.legado.app.nettts

import android.content.Context
import androidx.media3.common.MediaItem
import com.yuezhou.reader.core.speech.PrepareResult
import com.yuezhou.reader.core.speech.SessionToken
import com.yuezhou.reader.core.speech.SpeechEngineConfig
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlaybackBackend
import com.yuezhou.reader.core.speech.SpeechPlaybackEvent
import com.yuezhou.reader.core.speech.SpeechPlan
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 网络 TTS 播放后端（阶段 C 核心实现）。
 *
 * 队列策略：
 *  - MAX_QUEUE_SIZE=12，实际预取上限 3；
 *  - 合成并发 1（串行），每合成一段入队一段，播放器自动按 MediaItem 顺序播放；
 *  - 段间停顿：规则 pauseDuration > 0 时在每个媒体项后追加静音 WAV MediaItem；静音项不触发 SegmentCompleted。
 *
 * 事件过滤：每条事件都带 token，服务侧用 generation 丢弃旧代次回调；本类保证
 * SegmentStarted / SegmentCompleted 不交错（单 MediaSource 串行）。
 *
 * 阶段 D 流式模式：`streamReadAloudAudio=false` 先落盘、`true` 用 InputStreamDataSource；
 * 首版只实现落盘，流式作为开关预留。
 */
class NetworkTtsPlaybackBackend(
    private val context: Context,
    private val cacheDir: File,
    private val ruleProvider: suspend (ruleId: Long) -> HttpTTS?,
) : SpeechPlaybackBackend {

    companion object {
        /** 播放队列上限（与原版一致）。 */
        const val MAX_QUEUE_SIZE = 12
        /** 实际预取上限（超过即等待队列腾出位置）。 */
        const val PREFETCH_LIMIT = 3
        /** 合成并发（默认 1，阶段 D 可开多）。 */
        const val CONCURRENCY = 1
    }

    override val type: SpeechEngineType = SpeechEngineType.NETWORK_TTS

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _events = MutableSharedFlow<SpeechPlaybackEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    private val _playerState = MutableStateFlow<NetworkTtsPlayer.State>(NetworkTtsPlayer.State())

    private var synthesizer: NetworkTtsSynthesizer? = null
    private var cache: NetworkTtsAudioCache? = null
    private var player: NetworkTtsPlayer? = null

    /** 代次；play 时每次重新生成。 */
    private var token: SessionToken = SessionToken(0L, 0L)

    /** 当前要播放的 plan；[prepare] 里加载。 */
    private var plan: SpeechPlan? = null

    /** 当前规则（prepare 时加载）。 */
    private var rule: HttpTTS? = null

    /** 已排队的 MediaItem 原始索引（对应 SpeechPlan 的 segment 下标）。 */
    private val queuedIndices = linkedSetOf<Int>()

    /** 已报 Started 的 segment，防止重复。 */
    private val startedIndices = linkedSetOf<Int>()

    /** 已报 Completed 的 segment。 */
    private val completedIndices = linkedSetOf<Int>()

    private var synthJob: Job? = null
    private var active = false
    private var stopped = false
    private var nextSynthIndex = 0
    private var speechRate = 5

    override suspend fun prepare(config: SpeechEngineConfig): PrepareResult {
        val ruleId = config.networkTtsRuleId
            ?: return PrepareResult(false, "未选定网络 TTS 规则")
        val r = runCatching { ruleProvider(ruleId) }.getOrNull()
            ?: return PrepareResult(false, "规则不存在（id=$ruleId）")
        rule = r
        cache = NetworkTtsAudioCache(File(cacheDir, "audio"))
        val p = NetworkTtsPlayer(context, cacheDir)
        player = p
        installPlayerListener(p)
        // SpeechEngineConfig.rate: 1.0 默认；Network TTS 规则里 rate 字段也是 1.0。合入 rate=0.5~2.0。
        speechRate = when {
            config.rate <= 0.5f -> 0
            config.rate >= 2.0f -> 20
            else -> ((config.rate - 0.5f) / 1.5f * 20).toInt().coerceIn(0, 20)
        }
        return PrepareResult(true, "已加载规则「${r.name}」")
    }

    private fun installPlayerListener(p: NetworkTtsPlayer) {
        scope.launch {
            p.events.collect { event ->
                when (event) {
                    is NetworkTtsPlayer.PlayerEvent.PlayerSegmentStarted -> {
                        emit(SpeechPlaybackEvent.SegmentStarted(token, event.index))
                    }
                    is NetworkTtsPlayer.PlayerEvent.PlayerSegmentCompleted -> {
                        queuedIndices.remove(event.index)
                        emit(SpeechPlaybackEvent.SegmentCompleted(token, event.index))
                    }
                    is NetworkTtsPlayer.PlayerEvent.PlayerFinished -> {
                        // 整章播完；发一个特殊 index=-1 的 Completed 让 Service 进入下一章。
                        if (nextSynthIndex >= (plan?.segments?.size ?: Int.MAX_VALUE)) {
                            emit(SpeechPlaybackEvent.SegmentCompleted(token, -1))
                        }
                    }
                    is NetworkTtsPlayer.PlayerEvent.PlayerError -> {
                        emit(SpeechPlaybackEvent.Failed(token, NetworkTtsLog.sanitize(event.reason)))
                    }
                }
            }
        }
        // 同步 state 流
        scope.launch {
            p.state.collect { state ->
                _playerState.value = state
            }
        }
    }

    override suspend fun play(plan: SpeechPlan, fromIndex: Int, token: SessionToken) {
        val p = player ?: error("NetworkTtsPlaybackBackend 未 prepare")
        this.token = token
        this.plan = plan
        stopped = false
        active = true
        queuedIndices.clear()
        startedIndices.clear()
        completedIndices.clear()
        synthJob?.cancel()
        synthJob = null

        p.clear()

        if (fromIndex >= plan.segments.size) {
            emit(SpeechPlaybackEvent.Failed(token, "章节已播完"))
            return
        }

        nextSynthIndex = fromIndex
        startSynthLoop(plan, fromIndex)
        p.start()
    }

    override suspend fun pause() {
        stopped = true
        synthJob?.cancel()
        synthJob = null
        player?.pause()
    }

    override suspend fun resume() {
        player?.resume()
        // 重启合成循环（pause 时 stopped=true 让它退出了）
        val plan = this.plan ?: return
        if (active && !stopped) return
        stopped = false
        startSynthLoop(plan, nextSynthIndex)
    }

    private suspend fun startSynthLoop(plan: SpeechPlan, fromIndex: Int) {
        if (synthJob?.isActive == true) return
        val segments = plan.segments
        val total = segments.size
        if (fromIndex >= total) return
        val r = rule ?: return
        synthJob = scope.launch {
            var next = fromIndex
            while (next < total && active && !stopped) {
                ensureActive()
                while (queuedIndices.size >= PREFETCH_LIMIT) {
                    ensureActive()
                    kotlinx.coroutines.delay(100)
                }
                val segment = segments[next]
                val text = segment.text.trim()
                if (text.isEmpty()) {
                    completedIndices.add(next)
                    next++
                    nextSynthIndex = next
                    continue
                }
                val file = try {
                    synthesizer().synthesize(
                        rule = r,
                        text = text,
                        speechRate = speechRate,
                        chapterTitle = plan.bookId,
                        forceRefresh = false,
                    ).file
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    stopped = true
                    player?.pause()
                    emit(SpeechPlaybackEvent.Failed(token, describe(e)))
                    return@launch
                }
                ensureActive()
                if (!active || stopped) return@launch
                val np = player ?: return@launch
                if (!np.add(MediaItem.fromUri(file.toURI().toString()), next)) {
                    kotlinx.coroutines.delay(100)
                    continue
                }
                queuedIndices.add(next)
                if (r.pauseDuration > 0) {
                    np.addSilence(r.pauseDuration, next)
                }
                next++
                nextSynthIndex = next
            }
        }
    }

    override suspend fun stop() {
        stopped = true
        synthJob?.cancel()
        synthJob = null
        player?.stop()
        queuedIndices.clear()
        startedIndices.clear()
        completedIndices.clear()
        active = false
    }

    override suspend fun release() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            active = false
            stopped = true
            synthJob?.cancel()
            synthJob = null
            player?.release()
            player = null
            synthesizer = null
            cache = null
            scope.cancel()
        }
    }

    private fun synthesizer(): NetworkTtsSynthesizer {
        val c = cache ?: error("未初始化 cache")
        return NetworkTtsSynthesizer(
            cache = c,
            maxRetries = 0,
            jsTimeoutMs = 10_000,
            requestTimeoutMs = 120_000,
        )
    }

    private suspend fun emit(event: SpeechPlaybackEvent) {
        // 旧代次直接丢弃（服务已换 session/generation）。
        val expected = this.token
        val actual = when (event) {
            is SpeechPlaybackEvent.SegmentStarted -> event.token
            is SpeechPlaybackEvent.SegmentCompleted -> event.token
            is SpeechPlaybackEvent.Failed -> event.token
            is SpeechPlaybackEvent.Buffering -> event.token
        }
        if (actual.sessionId != expected.sessionId || actual.generation != expected.generation) {
            return
        }
        _events.emit(event)
    }

    private fun describe(t: Throwable): String =
        NetworkTtsLog.sanitize(t.message ?: t.javaClass.simpleName)
}
