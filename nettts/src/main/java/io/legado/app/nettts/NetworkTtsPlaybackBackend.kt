package io.legado.app.nettts

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 网络 TTS 播放后端：串行预合成、Media3 本地文件队列、按代次隔离事件。 */
class NetworkTtsPlaybackBackend(
    private val context: Context,
    private val cacheDir: File,
    private val ruleProvider: suspend (ruleId: Long) -> HttpTTS?,
) : SpeechPlaybackBackend {

    companion object {
        const val MAX_QUEUE_SIZE = 12

        /** 已合成待播段数：息屏/弱网下靠缓冲撑过合成间隙，3 段太浅容易断音。 */
        const val PREFETCH_LIMIT = 6
        const val CONCURRENCY = 1

        /** 合成失败退避序列；全部耗尽且超出窗口才判定不可恢复。 */
        private const val MAX_RETRY_WINDOW_MS = 5 * 60 * 1000L
        private const val PUMP_INTERVAL_MS = 2_000L
    }

    override val type: SpeechEngineType = SpeechEngineType.NETWORK_TTS

    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _events = MutableSharedFlow<SpeechPlaybackEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()
    private val _playerState = MutableStateFlow(NetworkTtsPlayer.State())

    private var synthesizer: NetworkTtsSynthesizer? = null
    private var cache: NetworkTtsAudioCache? = null
    private var player: NetworkTtsPlayer? = null
    private var token = SessionToken(0L, 0L)
    private var plan: SpeechPlan? = null
    private var rule: HttpTTS? = null

    private val queuedIndices = linkedSetOf<Int>()
    private val resultLeases = mutableMapOf<Int, NetworkTtsSynthesizer.Result>()
    private val playerTokens = mutableMapOf<Long, SessionToken>()
    private var currentPlayerGeneration = 0L

    private var synthJob: Job? = null
    private var pumpJob: Job? = null
    private var active = false
    private var stopped = false
    private var nextSynthIndex = 0
    private var speechRate = NetworkTtsSynthesizer.DEFAULT_SPEECH_RATE

    /** 最近一次播放/合成活动时间；服务侧看门狗据此判断链路是否停滞。 */
    @Volatile
    private var lastActivityAt = SystemClock.elapsedRealtime()

    override suspend fun prepare(config: SpeechEngineConfig): PrepareResult {
        val ruleId = config.networkTtsRuleId ?: return PrepareResult(false, "未选定网络 TTS 规则")
        val loaded = runCatching { ruleProvider(ruleId) }.getOrNull()
            ?: return PrepareResult(false, "规则不存在（id=$ruleId）")
        rule = loaded
        cache = NetworkTtsAudioCache(File(cacheDir, "audio"))
        val createdPlayer = NetworkTtsPlayer(context, cacheDir)
        player = createdPlayer
        installPlayerListener(createdPlayer)
        // 与试听共用同一转换入口：界面倍速 0.5~2.0（默认 1.0）→ speakSpeed 5~20（默认 10），
        // 直接作为最终请求参数传给 AnalyzeUrl，不再额外 +5。
        speechRate = NetworkTtsSynthesizer.uiRateToSpeechRate(config.rate)
        return PrepareResult(true, "已加载规则「${loaded.name}」")
    }

    private fun installPlayerListener(p: NetworkTtsPlayer) {
        scope.launch {
            p.events.collect { event ->
                val eventToken = playerTokens[event.generation] ?: return@collect
                if (event.generation != currentPlayerGeneration || eventToken != token) return@collect
                touchActivity()
                when (event) {
                    is NetworkTtsPlayer.PlayerEvent.PlayerSegmentStarted -> {
                        emit(SpeechPlaybackEvent.SegmentStarted(eventToken, event.index))
                    }

                    is NetworkTtsPlayer.PlayerEvent.PlayerSegmentCompleted -> {
                        queuedIndices.remove(event.index)
                        resultLeases.remove(event.index)?.close()
                        emit(SpeechPlaybackEvent.SegmentCompleted(eventToken, event.index))
                    }

                    is NetworkTtsPlayer.PlayerEvent.PlayerFinished -> {
                        if (nextSynthIndex >= (plan?.segments?.size ?: Int.MAX_VALUE)) {
                            emit(SpeechPlaybackEvent.SegmentCompleted(eventToken, -1))
                        }
                    }

                    is NetworkTtsPlayer.PlayerEvent.PlayerError -> {
                        emit(SpeechPlaybackEvent.Failed(eventToken, NetworkTtsLog.sanitize(event.reason)))
                    }
                }
            }
        }
        scope.launch { p.state.collect { _playerState.value = it } }
    }

    override suspend fun play(plan: SpeechPlan, fromIndex: Int, token: SessionToken) {
        val p = player ?: error("NetworkTtsPlaybackBackend 未 prepare")
        synthJob?.cancel()
        synthJob = null
        currentPlayerGeneration = 0L
        playerTokens.clear()
        this.token = token
        this.plan = plan
        stopped = false
        active = true

        val playerGeneration = p.clear()
        closeAllResults()
        queuedIndices.clear()
        currentPlayerGeneration = playerGeneration
        playerTokens[playerGeneration] = token

        if (fromIndex >= plan.segments.size) {
            emit(SpeechPlaybackEvent.Failed(token, "章节已播完"))
            return
        }
        nextSynthIndex = fromIndex
        touchActivity()
        startSynthLoop(plan, fromIndex, token, playerGeneration)
        startPump()
        p.start()
    }

    override suspend fun pause() {
        stopped = true
        stopPump()
        synthJob?.cancel()
        synthJob = null
        val removed = player?.pauseAndTruncate().orEmpty()
        removed.forEach { index ->
            queuedIndices.remove(index)
            resultLeases.remove(index)?.close()
        }
        removed.minOrNull()?.let { nextSynthIndex = it }
    }

    override suspend fun resume() {
        if (!active || !stopped) return
        val currentPlan = plan ?: return
        stopped = false
        touchActivity()
        startSynthLoop(currentPlan, nextSynthIndex, token, currentPlayerGeneration)
        startPump()
        player?.resume()
    }

    private suspend fun startSynthLoop(
        currentPlan: SpeechPlan,
        fromIndex: Int,
        sessionToken: SessionToken,
        playerGeneration: Long,
    ) {
        if (synthJob?.isActive == true) return
        val segments = currentPlan.segments
        if (fromIndex >= segments.size) return
        val currentRule = rule ?: return
        synthJob = scope.launch {
            var next = fromIndex
            while (next < segments.size && isCurrent(sessionToken, playerGeneration)) {
                ensureActive()
                while (queuedIndices.size >= PREFETCH_LIMIT && isCurrent(sessionToken, playerGeneration)) {
                    kotlinx.coroutines.delay(100)
                }
                if (!isCurrent(sessionToken, playerGeneration)) return@launch
                val text = segments[next].text.trim()
                if (text.isEmpty()) {
                    next++
                    nextSynthIndex = next
                    continue
                }
                if (queuedIndices.isEmpty()) {
                    emit(SpeechPlaybackEvent.Buffering(sessionToken, next))
                }
                touchActivity()
                val result = try {
                    synthesizeWithRetry(currentRule, text, next, currentPlan, sessionToken, playerGeneration)
                        ?: return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isCurrent(sessionToken, playerGeneration)) {
                        stopped = true
                        stopPump()
                        player?.pause()
                        // 保留会话与已缓冲音频：网络恢复后用户可直接继续，位置不丢。
                        emit(SpeechPlaybackEvent.Failed(sessionToken, describe(e), recoverable = true))
                    }
                    return@launch
                }
                var handedOff = false
                try {
                    ensureActive()
                    if (!isCurrent(sessionToken, playerGeneration)) return@launch
                    val currentPlayer = player ?: return@launch
                    val mediaItem = MediaItem.fromUri(result.file.toURI().toString())
                    while (!currentPlayer.add(mediaItem, next)) {
                        if (!isCurrent(sessionToken, playerGeneration)) return@launch
                        kotlinx.coroutines.delay(100)
                    }
                    resultLeases[next] = result
                    handedOff = true
                    queuedIndices.add(next)
                    touchActivity()
                    if (currentRule.pauseDuration > 0) {
                        currentPlayer.addSilence(currentRule.pauseDuration, next)
                    }
                    next++
                    nextSynthIndex = next
                } finally {
                    if (!handedOff) result.close()
                }
            }
        }
    }

    /**
     * 单段合成 + 退避重试。
     *
     * 息屏 / Doze / 弱网下请求会成片失败，立刻判失败会让整场朗读停止。
     * 这里在重试窗口内持续重试并发送 Buffering，网络恢复后从当前段继续；
     * 返回 null 表示会话已被取消或暂停。
     */
    private suspend fun synthesizeWithRetry(
        currentRule: HttpTTS,
        text: String,
        segIndex: Int,
        currentPlan: SpeechPlan,
        sessionToken: SessionToken,
        playerGeneration: Long,
    ): NetworkTtsSynthesizer.Result? {
        var attempt = 0
        var waitedMs = 0L
        while (isCurrent(sessionToken, playerGeneration)) {
            touchActivity()
            try {
                return synthesizer().synthesize(
                    rule = currentRule,
                    text = text,
                    speechRate = speechRate,
                    chapterTitle = currentPlan.bookId,
                    forceRefresh = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isCurrent(sessionToken, playerGeneration)) return null
                val wait = retryDelayMs(attempt)
                if (waitedMs + wait > MAX_RETRY_WINDOW_MS) throw e
                attempt++
                waitedMs += wait
                NetworkTtsLog.put("第 $attempt 次合成失败，${wait}ms 后重试：${describe(e)}")
                emit(SpeechPlaybackEvent.Buffering(sessionToken, segIndex))
                kotlinx.coroutines.delay(wait)
            }
        }
        return null
    }

    private fun retryDelayMs(attempt: Int): Long = when (attempt) {
        0 -> 2_000L
        1 -> 4_000L
        2 -> 8_000L
        3 -> 15_000L
        else -> 30_000L
    }

    /** 预取泵：合成任务意外结束而仍有未合成段时自动重启，避免断流后永久静默。 */
    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = scope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(PUMP_INTERVAL_MS)
                if (!active || stopped) continue
                val currentPlan = plan ?: continue
                if (nextSynthIndex >= currentPlan.segments.size) continue
                if (!isCurrent(token, currentPlayerGeneration)) continue
                val job = synthJob
                if (job == null || job.isCompleted) {
                    NetworkTtsLog.put("网络 TTS 预取中断，自动重启（index=$nextSynthIndex）")
                    startSynthLoop(currentPlan, nextSynthIndex, token, currentPlayerGeneration)
                }
            }
        }
    }

    private fun stopPump() {
        pumpJob?.cancel()
        pumpJob = null
    }

    override fun lastActivityElapsedMs(): Long = lastActivityAt

    override suspend fun recoverIfStalled(): Boolean {
        if (!active || stopped) return false
        val currentPlan = plan ?: return false
        val running = synthJob?.isActive == true
        if (running) {
            synthJob?.cancel()
            synthJob = null
        }
        touchActivity()
        NetworkTtsLog.put("网络 TTS 停滞恢复：重启合成（index=$nextSynthIndex）")
        startSynthLoop(currentPlan, nextSynthIndex, token, currentPlayerGeneration)
        player?.resume()
        return true
    }

    private fun touchActivity() {
        lastActivityAt = SystemClock.elapsedRealtime()
    }

    override suspend fun stop() {
        stopped = true
        active = false
        stopPump()
        synthJob?.cancel()
        synthJob = null
        currentPlayerGeneration = 0L
        playerTokens.clear()
        player?.stop()
        queuedIndices.clear()
        closeAllResults()
    }

    override suspend fun release() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            active = false
            stopped = true
            stopPump()
            synthJob?.cancel()
            synthJob = null
            currentPlayerGeneration = 0L
            playerTokens.clear()
            player?.release()
            player = null
            synthesizer = null
            cache = null
            closeAllResults()
            scope.cancel()
        }
    }

    private fun isCurrent(sessionToken: SessionToken, playerGeneration: Long): Boolean =
        active && !stopped && token == sessionToken &&
            currentPlayerGeneration == playerGeneration && playerTokens[playerGeneration] == sessionToken

    private fun closeAllResults() {
        resultLeases.values.forEach { it.close() }
        resultLeases.clear()
    }

    private fun synthesizer(): NetworkTtsSynthesizer {
        val currentCache = cache ?: error("未初始化 cache")
        return synthesizer ?: NetworkTtsSynthesizer(
            cache = currentCache,
            maxRetries = 2,
            jsTimeoutMs = 10_000,
            requestTimeoutMs = 120_000,
        ).also { synthesizer = it }
    }

    private suspend fun emit(event: SpeechPlaybackEvent) {
        if (event.token != token) return
        _events.emit(event)
    }

    private fun describe(t: Throwable): String =
        NetworkTtsLog.sanitize(t.message ?: t.javaClass.simpleName)
}
