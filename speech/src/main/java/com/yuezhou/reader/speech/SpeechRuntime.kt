package com.yuezhou.reader.speech

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.yuezhou.reader.core.speech.SpeechProgressSink
import com.yuezhou.reader.core.speech.SpeechSynthesizer

/**
 * 朗读运行时的应用级装配点（技术方案 §9.1）。
 *
 * app 在 `Application.onCreate()` 注入进度写入端口；独立实验构建可注入 Kokoro 提供者。
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
 * Kokoro 引擎提供者供独立实验构建使用。
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

    /** 固定自编文本的对照试听，不读取或导出用户书籍。 */
    suspend fun auditionNovelText(modelId: String, speakerId: Int, rate: Float, optimized: Boolean): String? =
        "当前版本不支持小说解析试听"

    /** 试音；成功返回 null，失败返回错误说明。 */
    suspend fun auditionModel(modelId: String, speakerId: Int, text: String): String? =
        "当前版本不支持试音"
}

/** 导入进度在进程内共享；界面重建后仍能显示，百分比只针对当前可测量的阶段。 */
data class KokoroImportProgress(
    val stage: String,
    val fraction: Float? = null,
    val processedBytes: Long = 0,
    val totalBytes: Long? = null,
    val running: Boolean = true,
)

object KokoroImportState {
    private val mutable = MutableStateFlow<KokoroImportProgress?>(null)
    val progress = mutable.asStateFlow()
    fun update(progress: KokoroImportProgress) { mutable.value = progress }
}
