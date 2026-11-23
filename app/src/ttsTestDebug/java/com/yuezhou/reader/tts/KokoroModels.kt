package com.yuezhou.reader.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File

/** 测试版内部模型目录与加载器（试音入口与服务共用）。 */
internal object KokoroModels {

    private val REQUIRED_FILES = listOf(
        "voices.bin",
        "tokens.txt",
        "lexicon-us-en.txt",
        "lexicon-zh.txt",
    )

    fun dir(filesDir: File, modelId: String): File {
        val dirName = when (modelId) {
            "int8" -> "kokoro-int8-multi-lang-v1_1"
            "fp32" -> "kokoro-multi-lang-v1_1"
            else -> modelId
        }
        return File(filesDir, "tts-models/$dirName")
    }

    fun isAvailable(filesDir: File, modelId: String): Boolean = runCatching {
        val dir = dir(filesDir, modelId)
        dir.isDirectory &&
            modelFile(dir) != null &&
            REQUIRED_FILES.all { File(dir, it).isFile } &&
            File(dir, "espeak-ng-data").isDirectory &&
            File(dir, "dict").isDirectory
    }.getOrDefault(false)

    fun createTts(dir: File): OfflineTts {
        val modelFile = modelFile(dir)
            ?: error("缺少 model.onnx / model.int8.onnx")
        return OfflineTts(
            null,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    kokoro = OfflineTtsKokoroModelConfig(
                        model = modelFile.absolutePath,
                        voices = File(dir, "voices.bin").absolutePath,
                        tokens = File(dir, "tokens.txt").absolutePath,
                        dataDir = File(dir, "espeak-ng-data").absolutePath,
                        lexicon = listOf("lexicon-us-en.txt", "lexicon-zh.txt")
                            .joinToString(",") { File(dir, it).absolutePath },
                        lang = "en",
                        dictDir = File(dir, "dict").absolutePath,
                        lengthScale = 1.0f,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                ruleFsts = "",
                ruleFars = "",
                maxNumSentences = 1,
                silenceScale = 0.2f,
            ),
        )
    }

    private fun modelFile(dir: File): File? =
        listOf("model.onnx", "model.int8.onnx")
            .map { File(dir, it) }
            .firstOrNull { it.isFile }
}
