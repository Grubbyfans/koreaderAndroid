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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
        const val PREFETCH_LIMIT = 3
        const val CONCURRENCY = 1
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
    private var active = false
    private var stopped = false
    private var nextSynthIndex = 0
    private var speechRate = NetworkTtsSynthesizer.DEFAULT_SPEECH_RATE

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
        startSynthLoop(plan, fromIndex, token, playerGeneration)
        p.start()
    }

    override suspend fun pause() {
        stopped = true
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
        startSynthLoop(currentPlan, nextSynthIndex, token, currentPlayerGeneration)
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
                val result = try {
                    synthesizer().synthesize(
                        rule = currentRule,
                        text = text,
                        speechRate = speechRate,
                        chapterTitle = currentPlan.bookId,
                        forceRefresh = false,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isCurrent(sessionToken, playerGeneration)) {
                        stopped = true
                        player?.pause()
                        emit(SpeechPlaybackEvent.Failed(sessionToken, describe(e)))
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

    override suspend fun stop() {
        stopped = true
        active = false
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
