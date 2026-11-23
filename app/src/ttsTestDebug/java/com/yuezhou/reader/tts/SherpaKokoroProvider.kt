package com.yuezhou.reader.tts

import android.content.Context
import com.yuezhou.reader.core.speech.SpeechSynthesizer
import com.yuezhou.reader.speech.KokoroEngineProvider
import com.yuezhou.reader.speech.KokoroModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 测试版 Kokoro 引擎提供者：按内部模型目录加载 sherpa 引擎。 */
internal class SherpaKokoroProvider(context: Context) : KokoroEngineProvider {

    private val filesDir: File = context.applicationContext.filesDir

    override fun availableModels(): List<KokoroModel> =
        listOf(
            KokoroModel(id = "int8", label = "int8 · 推荐"),
            KokoroModel(id = "fp32", label = "fp32 · 对照"),
        ).filter { KokoroModels.isAvailable(filesDir, it.id) }

    override val defaultModelId: String
        get() = availableModels().firstOrNull()?.id ?: "int8"

    override suspend fun createSynthesizer(modelId: String, speakerId: Int): SpeechSynthesizer =
        withContext(Dispatchers.IO) {
            check(KokoroModels.isAvailable(filesDir, modelId)) { "模型 $modelId 未部署或文件不完整" }
            SherpaKokoroSynthesizer(KokoroModels.createTts(KokoroModels.dir(filesDir, modelId)), speakerId)
        }
}
