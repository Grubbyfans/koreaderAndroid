package com.yuezhou.reader.speech

import com.yuezhou.reader.core.speech.SpeechProgressSink
import com.yuezhou.reader.core.speech.SpeechSynthesizer

/**
 * 朗读运行时的应用级装配点（技术方案 §9.1）。
 *
 * app 在 `Application.onCreate()` 注入进度写入端口；测试版还可注入 Kokoro 引擎提供者。
 * 服务只依赖这里的端口，不反向依赖 app / reader 模块。
 */
object SpeechRuntime {

    @Volatile
    var progressSink: SpeechProgressSink? = null
        private set

    @Volatile
    var kokoro: KokoroEngineProvider? = null
        private set

    fun installProgressSink(sink: SpeechProgressSink?) {
        progressSink = sink
    }

    fun installKokoro(provider: KokoroEngineProvider?) {
        kokoro = provider
    }

    /** Kokoro 是否已装配且至少有一个可用模型。 */
    val kokoroAvailable: Boolean
        get() = kokoro?.availableModels()?.isNotEmpty() == true
}

data class KokoroModel(
    val id: String,
    val label: String,
)

/**
 * Kokoro 引擎提供者：由测试版变体实现并注入，正式版不实现（用户只看到系统 TTS）。
 */
interface KokoroEngineProvider {

    /** 已部署且文件完整的模型列表。 */
    fun availableModels(): List<KokoroModel>

    /** 默认模型 ID；[availableModels] 非空时有效。 */
    val defaultModelId: String

    /** 加载模型并返回合成端口；实现方负责切换到 IO 线程，失败抛异常。 */
    suspend fun createSynthesizer(modelId: String, speakerId: Int): SpeechSynthesizer
}
