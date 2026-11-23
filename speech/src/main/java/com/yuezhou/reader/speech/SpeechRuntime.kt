package com.yuezhou.reader.speech

import android.net.Uri
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

/** 模型管理展示信息（版本/精度/体积/哈希来自导入清单或现场统计）。 */
data class KokoroModelInfo(
    val id: String,
    val label: String,
    val version: String = "",
    val precision: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
)

/** 模型导入结果；[message] 直接展示给用户。 */
data class KokoroImportResult(
    val ok: Boolean,
    val modelId: String? = null,
    val message: String,
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

    /** 模型管理信息列表；默认与 [availableModels] 一致。 */
    fun modelInfos(): List<KokoroModelInfo> =
        availableModels().map { KokoroModelInfo(id = it.id, label = it.label) }

    /** 从 SAF 文件导入模型（ZIP / tar.bz2）；返回结果供 UI 展示。 */
    suspend fun importModel(uri: Uri): KokoroImportResult =
        KokoroImportResult(ok = false, message = "当前版本不支持模型导入")

    /** 删除模型目录；正在合成时拒绝。 */
    suspend fun deleteModel(modelId: String): Boolean = false

    /** 试音；成功返回 null，失败返回错误说明。 */
    suspend fun auditionModel(modelId: String, speakerId: Int, text: String): String? =
        "当前版本不支持试音"
}
