package com.yuezhou.reader.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.yuezhou.reader.speech.KokoroModelInfo
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** 测试版内部模型目录与加载器（试音入口、服务与模型管理共用）。 */
internal object KokoroModels {

    private val REQUIRED_FILES = listOf(
        "voices.bin",
        "tokens.txt",
        "lexicon-us-en.txt",
        "lexicon-zh.txt",
    )
    private val MODEL_FILES = listOf("model.onnx", "model.int8.onnx")

    const val ROOT_DIR = "tts-models"
    const val MANIFEST_FILE = "manifest.json"

    /** 内置模型的短 ID ↔ 目录名映射；导入模型直接用目录名作 ID。 */
    private val BUILTIN_DIRS = mapOf(
        "int8" to "kokoro-int8-multi-lang-v1_1",
        "fp32" to "kokoro-multi-lang-v1_1",
    )

    fun rootDir(filesDir: File): File = File(filesDir, ROOT_DIR)

    fun dir(filesDir: File, modelId: String): File =
        File(rootDir(filesDir), BUILTIN_DIRS[modelId] ?: modelId)

    fun isAvailable(filesDir: File, modelId: String): Boolean = isAvailable(dir(filesDir, modelId))

    fun isAvailable(dir: File): Boolean = runCatching { validateRoot(dir) == null }.getOrDefault(false)

    /** 解压/部署后目录校验；返回错误说明，通过返回 null。 */
    fun validateRoot(dir: File): String? {
        if (!dir.isDirectory) return "模型目录不存在"
        if (modelFile(dir) == null) return "缺少 model.onnx / model.int8.onnx"
        val missing = REQUIRED_FILES.filterNot { File(dir, it).isFile }
        if (missing.isNotEmpty()) return "缺少文件：${missing.joinToString()}"
        if (!File(dir, "espeak-ng-data").isDirectory) return "缺少 espeak-ng-data 目录"
        if (!File(dir, "dict").isDirectory) return "缺少 dict 目录"
        return null
    }

    /** 扫描已部署且完整的模型，按目录名排序。 */
    fun modelDirs(filesDir: File): List<File> =
        rootDir(filesDir).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && isAvailable(it) }
            ?.sortedBy { it.name }
            .orEmpty()

    fun modelId(dir: File, manifest: JSONObject? = null): String {
        BUILTIN_DIRS.entries.firstOrNull { it.value == dir.name }?.let { return it.key }
        val fromManifest = manifest?.optString("id").orEmpty()
        return fromManifest.ifBlank { dir.name }
    }

    fun modelInfo(dir: File): KokoroModelInfo {
        val manifest = readManifest(dir)
        return KokoroModelInfo(
            id = modelId(dir, manifest),
            label = manifest?.optString("label").orEmpty().ifBlank { defaultLabel(dir) },
            version = manifest?.optString("version").orEmpty().ifBlank { versionFromName(dir.name) },
            precision = manifest?.optString("precision").orEmpty().ifBlank { precisionFromName(dir.name) },
            sizeBytes = dirSize(dir),
            sha256 = manifest?.optString("sha256").orEmpty(),
        )
    }

    fun readManifest(dir: File): JSONObject? = runCatching {
        val file = File(dir, MANIFEST_FILE)
        if (!file.isFile) return null
        JSONObject(file.readText())
    }.getOrNull()

    fun writeManifest(dir: File, manifest: JSONObject) {
        File(dir, MANIFEST_FILE).writeText(manifest.toString(2))
    }

    fun modelFile(dir: File): File? =
        MODEL_FILES.map { File(dir, it) }.firstOrNull { it.isFile }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteRecursively(target: File) {
        runCatching { target.deleteRecursively() }
    }

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

    private fun defaultLabel(dir: File): String = when (dir.name) {
        BUILTIN_DIRS["int8"] -> "int8 · 推荐"
        BUILTIN_DIRS["fp32"] -> "fp32 · 对照"
        else -> dir.name
    }

    private fun versionFromName(name: String): String =
        Regex("v\\d+(?:_\\d+)*").find(name)?.value.orEmpty()

    private fun precisionFromName(name: String): String = when {
        name.contains("int8", ignoreCase = true) -> "int8"
        name.contains("fp32", ignoreCase = true) || name.contains("float32", ignoreCase = true) -> "fp32"
        else -> ""
    }
}
