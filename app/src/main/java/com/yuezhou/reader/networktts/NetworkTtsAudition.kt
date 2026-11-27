package com.yuezhou.reader.networktts

import android.content.Context
import io.legado.app.data.entities.HttpTTS
import io.legado.app.nettts.NetworkTtsRuntime
import io.legado.app.nettts.NetworkTtsSynthesizer
import io.legado.app.nettts.NetworkTtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 规则试听控制器（阶段 A 的独立播放链路）。
 *
 * 流程：合成（有限重试）→ 独立 ExoPlayer 播放。合成失败不静音跳过，
 * 直接把原因反馈到界面；停止会取消进行中的合成请求，避免继续消耗额度。
 *
 * 注意：NetworkTtsPlayer 的所有 ExoPlayer 操作都是 suspend + withContext(Main)，
 * 因此这里所有 player.* 调用都必须在 scope.launch 里执行。
 */
class NetworkTtsAudition(context: Context) {

    enum class Phase { IDLE, SYNTHESIZING, PLAYING, PAUSED, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val message: String? = null,
        val ruleId: Long? = null,
        val fromCache: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
    )

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val synthesizer: NetworkTtsSynthesizer = NetworkTtsRuntimeBridge.synthesizer(appContext)
    private val player: NetworkTtsPlayer = NetworkTtsRuntimeBridge.player(appContext)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var synthJob: Job? = null
    private var released = false

    init {
        NetworkTtsRuntime.ttsCacheClearer = {
            scope.launch { runCatching { player.clearCache() } }
        }
    }

    /** 合成并播放一段试听文本。 */
    fun audition(rule: HttpTTS, text: String, speechRate: Int) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            _state.value = State(phase = Phase.ERROR, message = "试听文本为空", ruleId = rule.id)
            return
        }
        synthJob?.cancel()
        scope.launch { player.stop() }
        _state.value = State(phase = Phase.SYNTHESIZING, message = "正在合成…", ruleId = rule.id)
        synthJob = scope.launch {
            try {
                val result = synthesizer.synthesize(
                    rule = rule,
                    text = trimmed,
                    speechRate = NetworkTtsSynthesizer.requestSpeechRate(speechRate),
                    chapterTitle = "试听",
                    forceRefresh = false,
                )
                if (released) return@launch
                player.play(listOf(result.file), silenceMs = rule.pauseDuration)
                _state.update {
                    it.copy(
                        phase = Phase.PLAYING,
                        message = if (result.fromCache) "播放（命中缓存）" else "播放",
                        fromCache = result.fromCache,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = State(
                    phase = Phase.ERROR,
                    message = e.message ?: "试听失败",
                    ruleId = rule.id,
                )
            }
        }
    }

    /** 播放/暂停切换。 */
    fun togglePlayPause() {
        val playing = player.state.value.playing
        scope.launch {
            if (playing) {
                player.pause()
                _state.update { it.copy(phase = Phase.PAUSED) }
            } else if (_state.value.phase == Phase.PAUSED) {
                player.resume()
                _state.update { it.copy(phase = Phase.PLAYING) }
            }
        }
    }

    fun stop() {
        synthJob?.cancel()
        synthJob = null
        scope.launch { player.stop() }
        _state.value = State()
    }

    fun refreshProgress() {
        val snapshot = player.state.value
        _state.update {
            it.copy(
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                phase = when {
                    snapshot.error != null -> Phase.ERROR
                    it.phase == Phase.SYNTHESIZING || it.phase == Phase.ERROR -> it.phase
                    snapshot.finished -> Phase.IDLE
                    snapshot.playing -> Phase.PLAYING
                    snapshot.totalCount > 0 -> Phase.PAUSED
                    else -> Phase.IDLE
                },
                message = snapshot.error ?: it.message,
            )
        }
    }

    fun release() {
        if (released) return
        released = true
        NetworkTtsRuntime.ttsCacheClearer = null
        synthJob?.cancel()
        scope.launch { runCatching { player.release() } }
        scope.cancel()
    }
}
