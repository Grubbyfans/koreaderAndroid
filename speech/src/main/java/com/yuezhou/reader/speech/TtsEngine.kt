package com.yuezhou.reader.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android [TextToSpeech] 引擎封装：负责引擎生命周期、音色选择、语速与朗读。
 *
 * 由前台朗读服务持有单实例；引擎初始化是异步的，这里用挂起函数封装为可 await 的一次性就绪，
 * 避免上层在引擎未就绪时调用 [speak] 抛异常。
 *
 * 对齐技术方案 §7.1 / §7.5：
 *   - 只调用本地 [TextToSpeech]，不引入云端 TTS；
 *   - 首版不申请麦克风权限，朗读链路不发起网络请求；
 *   - 音色未安装/无离线音色时引导到系统安装页（见 [TtsVoice.isOfflineCapable]）。
 */
class TtsEngine(private val context: Context) {

    /** 引擎初始化回调，用于驱动挂起就绪。 */
    private var onInitResult: ((Int) -> Unit)? = null

    private var _tts: TextToSpeech? = null

    /** 底层引擎实例；未初始化时为 null。 */
    val tts: TextToSpeech? get() = _tts

    /** 朗读回调，由服务注入；在主线程回调。 */
    var listener: UtteranceProgressListener? = null
        set(value) {
            field = value
            _tts?.setOnUtteranceProgressListener(value)
        }

    /**
     * 初始化引擎并等待就绪。可重复调用（幂等）。
     *
     * @param language 期望朗读语言；找不到时回退系统默认。
     * @throws IllegalStateException 引擎初始化失败（[TextToSpeech.ERROR]）。
     */
    suspend fun initialize(language: Locale = Locale.SIMPLIFIED_CHINESE): Unit {
        _tts?.let { return }
        val created = TextToSpeech(context.applicationContext) { status ->
            onInitResult?.invoke(status)
            onInitResult = null
        }
        _tts = created
        val status = awaitInit(created)
        if (status == TextToSpeech.ERROR) {
            shutdown()
            throw IllegalStateException("TextToSpeech 引擎初始化失败")
        }
        if (created.setLanguage(language) == TextToSpeech.LANG_MISSING_DATA &&
            created.setLanguage(Locale.getDefault()) == TextToSpeech.LANG_MISSING_DATA
        ) {
            shutdown()
            throw IllegalStateException("无可用 TTS 语言数据：$language")
        }
    }

    /** 等待引擎初始化完成；若已就绪则立即返回成功。 */
    private suspend fun awaitInit(created: TextToSpeech): Int =
        suspendCancellableCoroutine { cont ->
            onInitResult = { status ->
                if (cont.isActive) cont.resume(status)
            }
            cont.invokeOnCancellation { onInitResult = null }
        }

    /** 查询当前引擎提供的音色，并按离线能力过滤排序。 */
    fun availableVoices(): List<TtsVoice> =
        _tts?.let { t ->
            @Suppress("DEPRECATION")
            t.voices?.map { it.toModel(t) }?.sortedWith(compareByDescending<TtsVoice> { it.isOfflineCapable }) ?: emptyList()
        } ?: emptyList()

    /** 切换到指定音色。返回是否成功。 */
    fun setVoice(voiceName: String): Boolean {
        val t = _tts ?: return false
        val voice = t.voices?.firstOrNull { it.name == voiceName } ?: return false
        return t.setVoice(voice) == TextToSpeech.SUCCESS
    }

    /** 设置语速（TextToSpeech 原生范围约 0.0..2.0，1.0 为正常）。 */
    fun setSpeechRate(rate: Float): Boolean {
        val t = _tts ?: return false
        return t.setSpeechRate(rate) == TextToSpeech.SUCCESS
    }

    /**
     * 应用朗读配置：语速 + 音色。
     *
     * @param rate 语速；非法值时跳过（保留当前）。
     * @param voiceName 音色名（[TextToSpeech.Voice.name]）；空白时保持引擎默认音色。
     */
    fun applyConfig(rate: Float, voiceName: String) {
        val t = _tts ?: return
        if (rate > 0f) t.setSpeechRate(rate)
        if (voiceName.isNotBlank()) setVoice(voiceName)
    }

    /** 朗读一段文本。@param utteranceId 稳定标识，用于回调映射。返回是否已入队。 */
    fun speak(text: String, utteranceId: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH): Boolean {
        val t = _tts ?: return false
        if (text.isBlank()) return true
        return t.speak(text, queueMode, null, utteranceId) == TextToSpeech.SUCCESS
    }

    /** 停止当前朗读并清空队列。 */
    fun stop(): Boolean {
        val t = _tts ?: return false
        return t.stop() == TextToSpeech.SUCCESS
    }

    /** 判断引擎当前是否正在朗读。 */
    fun isSpeaking(): Boolean = _tts?.isSpeaking == true

    /** 释放引擎资源。释放后可再次调用 [initialize]。 */
    fun shutdown() {
        _tts?.stop()
        _tts?.shutdown()
        _tts = null
        listener = null
    }

    private fun Voice.toModel(t: TextToSpeech): TtsVoice = TtsVoice(
        name = name ?: "",
        locale = locale ?: Locale.getDefault(),
        enginePackage = t.defaultEngine ?: "",
        isNetworkConnectionRequired = isNetworkConnectionRequired,
        isInstalled = features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == false,
    )

    companion object {
        /**
         * 一次性查询系统默认 TTS 引擎的可用音色（不保留引擎实例）。
         *
         * 供 UI 在尚未启动朗读服务时提前枚举音色；查询完成后立即释放引擎。
         * 回调在主线程触发；引擎初始化失败时返回空列表。
         */
        fun queryVoicesOnce(context: Context, onResult: (List<TtsVoice>) -> Unit) {
            var tts: TextToSpeech? = null
            var delivered = false
            tts = TextToSpeech(context.applicationContext) { status ->
                if (delivered) {
                    tts?.shutdown()
                    return@TextToSpeech
                }
                delivered = true
                val engine = tts
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    engine?.shutdown()
                    onResult(emptyList())
                    return@TextToSpeech
                }
                // 先切到中文，让引擎（如三星 SMT）返回对应语言的中文音色；
                // 否则默认语言下 getVoices() 只返回英文/日文等音色，看不到中文男女声。
                engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                @Suppress("DEPRECATION")
                val voices = engine.voices?.map { v ->
                    TtsVoice(
                        name = v.name ?: "",
                        locale = v.locale ?: Locale.getDefault(),
                        enginePackage = engine.defaultEngine ?: "",
                        isNetworkConnectionRequired = v.isNetworkConnectionRequired,
                        isInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == false,
                    )
                }?.sortedWith(compareByDescending<TtsVoice> { it.isOfflineCapable })
                    ?: emptyList()
                onResult(voices)
                engine.shutdown()
            }
        }
    }
}
