package com.yuezhou.reader.core.speech

import kotlinx.coroutines.flow.Flow

/**
 * 朗读后端统一端口（技术方案 §5）。
 *
 * 系统 TTS 与 Kokoro 各自实现本接口，服务只依赖端口与事件，不感知具体引擎。
 * 所有回调事件都携带 [SessionToken]，旧代次的迟到回调由实现方丢弃。
 */
interface SpeechPlaybackBackend {

    /** 引擎类型。 */
    val type: SpeechEngineType

    /** 准备工作（加载模型/初始化引擎）。幂等。 */
    suspend fun prepare(config: SpeechEngineConfig): PrepareResult

    /**
     * 从整章计划的 [fromIndex] 句开始播放。
     *
     * [token] 由调用方在每次 seek/上一句/下一句/切章/切模型/停止时递增 generation。
     */
    suspend fun play(plan: SpeechPlan, fromIndex: Int, token: SessionToken)

    /** 暂停：保留已缓冲音频与播放帧位，不发 Completed。 */
    suspend fun pause()

    /** 从暂停位置继续。 */
    suspend fun resume()

    /** 停止：失效代次、清空队列与音频。 */
    suspend fun stop()

    /** 释放 native/引擎资源。释放后不可再播放。 */
    suspend fun release()

    /** 播放事件流；实现方保证同一代次内 Started/Completed 不交错。 */
    val events: Flow<SpeechPlaybackEvent>

    /** 最近一次播放/合成活动的时间（elapsedRealtime 毫秒）；不支持时返回 0。 */
    fun lastActivityElapsedMs(): Long = 0L

    /** 检查并尝试恢复停滞的预取/播放链路；返回是否执行了恢复动作。 */
    suspend fun recoverIfStalled(): Boolean = false
}

/** 引擎类型。 */
enum class SpeechEngineType { SYSTEM_TTS, KOKORO, NETWORK_TTS }

/**
 * 引擎准备配置。
 *
 * @param engine 目标引擎。
 * @param voiceName 音色名（系统 TTS 使用；Kokoro 可为空）。
 * @param rate 语速（1.0 为正常）。Kokoro 走合成层 speed；系统 TTS 走 setSpeechRate。
 * @param modelDir Kokoro 模型目录（绝对路径）；系统 TTS 为 null。
 * @param speakerId Kokoro 说话人 ID。
 */
data class SpeechEngineConfig(
    val engine: SpeechEngineType,
    val voiceName: String = "",
    val rate: Float = 1.0f,
    val modelDir: String? = null,
    val speakerId: Int = 0,
    /** NETWORK_TTS 使用：规则 ID；为空时 prepare 返回失败。 */
    val networkTtsRuleId: Long? = null,
)

/** 准备结果。 */
data class PrepareResult(
    val ok: Boolean,
    val message: String? = null,
)

/**
 * 会话令牌（技术方案 §5）。
 *
 * [sessionId] 标识一次服务朗读会话；[generation] 在每次 seek、前后句、切章、
 * 切模型、停止、重启队列时递增。旧 token 的事件一律丢弃。
 */
data class SessionToken(
    val sessionId: Long,
    val generation: Long,
)

/** 播放事件（技术方案 §5.1）。 */
sealed interface SpeechPlaybackEvent {
    val token: SessionToken

    /** 第 [index] 句开始播放（仅更新当前句，不代表完成）。 */
    data class SegmentStarted(
        override val token: SessionToken,
        val index: Int,
        val delivery: SegmentDelivery? = null,
        val audioMetrics: DeliveryAudioMetrics? = null,
    ) : SpeechPlaybackEvent

    /** 第 [index] 句 PCM 实际播完；每次播放恰好一次。 */
    data class SegmentCompleted(
        override val token: SessionToken,
        val index: Int,
    ) : SpeechPlaybackEvent

    /** 第 [index] 句音频未就绪；不推进游标。 */
    data class Buffering(
        override val token: SessionToken,
        val index: Int,
    ) : SpeechPlaybackEvent

    /**
     * 播放失败；保留位置并停止队列，不静默切换引擎。
     *
     * [recoverable] 为 true 表示链路的会话与缓冲仍有效（如网络中断），
     * 上层应保留会话并暂停，等待用户恢复，而不是直接结束朗读。
     */
    data class Failed(
        override val token: SessionToken,
        val reason: String,
        val memoryPressure: Boolean = false,
        val recoverable: Boolean = false,
    ) : SpeechPlaybackEvent
}

/**
 * 合成端口：协调器只依赖它，测试用纯 Kotlin 假引擎替换。
 *
 * 实现方需保证 native 调用串行（由协调器用互斥量保证），
 * 且 [release] 只能在所有 [synthesize] 返回后调用。
 */
interface SpeechSynthesizer {
    suspend fun synthesize(
        text: String,
        segmentIndex: Int,
        speed: Float,
        token: SessionToken,
    ): SynthesizedAudio

    /** 释放模型；必须等待进行中的合成结束。 */
    suspend fun release()
}

/** 一段合成音频：PCM float 单声道 + 采样率。 */
class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
)

/**
 * 播放端口：真实实现用单 AudioTrack + 累计帧边界（技术方案 §6）；
 * 测试用假播放器按顺序触发 [CompletionListener]。
 */
interface PcmPlayer {

    fun interface StartListener {
        /** 播放头越过该句第一帧时通知，不能在合成或入队时通知。 */
        fun onStarted(segmentIndex: Int, generation: Long)
    }

    fun setStartListener(listener: StartListener?)

    fun interface CompletionListener {
        /** 第 [segmentIndex] 句实际播完；[generation] 用于过滤旧队列。 */
        fun onCompleted(segmentIndex: Int, generation: Long)
    }

    fun setCompletionListener(listener: CompletionListener?)

    /** 开始新一代播放时间线（清空旧帧位与边界）。 */
    fun begin(generation: Long)

    /** 按顺序追加一句 PCM。返回是否接受。 */
    fun enqueue(segmentIndex: Int, audio: SynthesizedAudio): Boolean

    fun pause()

    fun resume()

    /** 停止并清空；之后不得再回调完成。 */
    fun stop()

    /**
     * 当前播放头位置（毫秒）；无有效播放时返回 -1。
     *
     * 供上层停滞检测使用：长句播放期间没有完成回调，但播放头会持续推进。
     */
    fun positionMs(): Long = -1L

    fun release()
}
