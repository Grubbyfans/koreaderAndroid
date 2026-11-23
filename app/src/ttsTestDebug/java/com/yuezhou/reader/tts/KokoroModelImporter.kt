package com.yuezhou.reader.tts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.yuezhou.reader.speech.KokoroImportResult
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject

/**
 * Kokoro 模型安全导入（方案 §8.1 / §8.2）。
 *
 * 流程：识别 ZIP / tar.bz2 → 解压到私有 staging → 单顶层目录归一 → 必备文件与
 * 清单 SHA-256 校验 → 真实试音合成 → 原子切换目录 → 清理 staging。
 *
 * 防护：路径逃逸（.. / 绝对路径）、符号链接与硬链接、压缩炸弹（条目数/单文件/总量上限）、
 * 磁盘空间（声明体积 2 倍，至少 300 MiB）、试音失败不落地。
 */
internal object KokoroModelImporter {

    private const val MAX_ENTRIES = 20_000
    private const val MAX_ENTRY_BYTES = 800L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 1_500L * 1024 * 1024
    private const val MIN_FREE_BYTES = 300L * 1024 * 1024
    private const val TRIAL_TEXT = "你好，这是模型试音。"

    suspend fun import(context: Context, uri: Uri): KokoroImportResult = withContext(Dispatchers.IO) {
        val filesDir = context.applicationContext.filesDir
        val rootDir = KokoroModels.rootDir(filesDir).apply { mkdirs() }
        val declared = querySize(context, uri) ?: -1L
        if (declared > MAX_TOTAL_BYTES) {
            return@withContext failure("压缩包过大（> ${MAX_TOTAL_BYTES / 1024 / 1024} MiB）")
        }
        val required = maxOf(MIN_FREE_BYTES, declared.coerceAtLeast(0L) * 2)
        if (rootDir.usableSpace < required) {
            return@withContext failure("存储空间不足：需要约 ${required / 1024 / 1024} MiB")
        }
        val staging = File(rootDir, ".import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val format = unpack(context, uri, staging)
            val modelRoot = normalizeRoot(staging)
            KokoroModels.validateRoot(modelRoot)?.let { return@withContext failure(it) }
            val sourceManifest = KokoroModels.readManifest(modelRoot)
            verifyManifestHashes(modelRoot, sourceManifest)
            trialSynthesis(modelRoot)
            val modelId = resolveModelId(modelRoot, staging, sourceManifest)
            val target = File(rootDir, modelId)
            switchAtomically(staging, modelRoot, target)
            writeManifest(target, modelId, sourceManifest)
            KokoroImportResult(
                ok = true,
                modelId = modelId,
                message = "已导入 ${modelId}（${format.uppercase()}，试音通过）",
            )
        } catch (e: Exception) {
            failure(e.message ?: e.javaClass.simpleName)
        } finally {
            KokoroModels.deleteRecursively(staging)
        }
    }

    fun delete(filesDir: File, modelId: String): Boolean = runCatching {
        val dir = KokoroModels.dir(filesDir, modelId)
        if (!dir.isDirectory) return false
        val tomb = File(KokoroModels.rootDir(filesDir), ".deleted-${dir.name}-${System.currentTimeMillis()}")
        if (!dir.renameTo(tomb)) return false
        tomb.deleteRecursively()
        true
    }.getOrDefault(false)

    private fun failure(message: String) = KokoroImportResult(ok = false, message = message)

    private fun querySize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val index = c.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !c.isNull(index)) c.getLong(index) else null
            } else null
        }
    }.getOrNull()

    /** 按魔数识别格式并解压；返回格式标识。 */
    private fun unpack(context: Context, uri: Uri, dest: File): String {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val head = ByteArray(4)
        val read = buffered.read(head)
        buffered.reset()
        val format = when {
            read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> "zip"
            read >= 3 && head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() &&
                head[2] == 'h'.code.toByte() -> "tar.bz2"
            else -> throw IOException("无法识别格式：仅支持 ZIP 或 tar.bz2")
        }
        buffered.use {
            when (format) {
                "zip" -> unpackZip(it, dest)
                else -> unpackTarBz2(it, dest)
            }
        }
        return format
    }

    private fun unpackZip(input: InputStream, dest: File) {
        val budget = Budget()
        ZipArchiveInputStream(input).use { zin ->
            while (true) {
                val entry = zin.nextZipEntry ?: break
                if (ignoreEntry(entry.name)) continue
                budget.takeEntry(entry.name)
                if (entry.isDirectory) {
                    resolveSafe(dest, entry.name).mkdirs()
                    continue
                }
                if (entry.isUnixSymlink) throw IOException("压缩包包含符号链接：${entry.name}")
                val out = resolveSafe(dest, entry.name)
                out.parentFile?.mkdirs()
                copyLimited(zin, out, budget)
            }
        }
    }

    private fun unpackTarBz2(input: InputStream, dest: File) {
        val budget = Budget()
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tin ->
            while (true) {
                val entry = tin.nextTarEntry ?: break
                if (ignoreEntry(entry.name)) continue
                budget.takeEntry(entry.name)
                if (entry.isSymbolicLink || entry.isLink) {
                    throw IOException("压缩包包含链接：${entry.name}")
                }
                if (entry.isDirectory) {
                    resolveSafe(dest, entry.name).mkdirs()
                    continue
                }
                if (!entry.isFile) continue
                val out = resolveSafe(dest, entry.name)
                out.parentFile?.mkdirs()
                copyLimited(tin, out, budget)
            }
        }
    }

    private fun ignoreEntry(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        return name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")
    }

    private fun resolveSafe(dest: File, name: String): File {
        if (name.startsWith('/') || name.startsWith('\\') || name.contains('\u0000')) {
            throw IOException("非法路径：$name")
        }
        val out = File(dest, name)
        val base = dest.canonicalPath + File.separator
        if (!out.canonicalPath.startsWith(base)) throw IOException("路径逃逸：$name")
        return out
    }

    private fun copyLimited(input: InputStream, out: File, budget: Budget) {
        FileOutputStream(out).use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                budget.takeBytes(n.toLong(), out.name)
                output.write(buffer, 0, n)
            }
        }
    }

    private fun normalizeRoot(staging: File): File {
        val entries = staging.listFiles().orEmpty()
        return if (entries.size == 1 && entries[0].isDirectory) entries[0] else staging
    }

    private fun verifyManifestHashes(root: File, manifest: JSONObject?) {
        val files = manifest?.optJSONObject("files") ?: return
        for (key in files.keys()) {
            val expected = files.optString(key)
            if (expected.isBlank()) continue
            val file = resolveSafe(root, key)
            if (!file.isFile) throw IOException("清单文件缺失：$key")
            val actual = KokoroModels.sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IOException("文件校验失败：$key")
            }
        }
    }

    private fun trialSynthesis(root: File) {
        val tts = KokoroModels.createTts(root)
        try {
            val audio = tts.generate(TRIAL_TEXT, 3, 1.0f)
            if (audio.samples.isEmpty()) throw IOException("试音合成结果为空")
        } finally {
            runCatching { tts.release() }
        }
    }

    private fun resolveModelId(root: File, staging: File, manifest: JSONObject?): String {
        val raw = manifest?.optString("id").orEmpty().ifBlank {
            if (root != staging) root.name else "kokoro-import-${System.currentTimeMillis()}"
        }
        return sanitizeId(raw)
    }

    private fun sanitizeId(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "-").trim('.', '-').take(64)
        if (cleaned.isBlank() || cleaned == "." || cleaned == "..") {
            throw IOException("模型 ID 非法：$raw")
        }
        return cleaned
    }

    private fun switchAtomically(staging: File, root: File, target: File) {
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, ".backup-${target.name}-${System.currentTimeMillis()}")
        if (target.exists() && !target.renameTo(backup)) {
            throw IOException("无法替换已有模型目录")
        }
        val moved = root.renameTo(target)
        if (!moved) {
            if (backup.exists()) backup.renameTo(target)
            throw IOException("无法写入模型目录")
        }
        backup.deleteRecursively()
    }

    private fun writeManifest(target: File, modelId: String, source: JSONObject?) {
        val modelFile = KokoroModels.modelFile(target) ?: return
        val manifest = JSONObject().apply {
            put("id", modelId)
            put(
                "label",
                source?.optString("label").orEmpty().ifBlank { modelId },
            )
            put("version", source?.optString("version").orEmpty())
            put("precision", source?.optString("precision").orEmpty())
            put("modelFile", modelFile.name)
            put("sha256", KokoroModels.sha256(modelFile))
            put("importedAt", System.currentTimeMillis())
        }
        KokoroModels.writeManifest(target, manifest)
    }

    private class Budget {
        private var entries = 0
        private var total = 0L

        fun takeEntry(name: String) {
            entries++
            if (entries > MAX_ENTRIES) throw IOException("压缩包条目过多：$name")
        }

        fun takeBytes(bytes: Long, name: String) {
            if (bytes > MAX_ENTRY_BYTES) throw IOException("单个文件过大：$name")
            total += bytes
            if (total > MAX_TOTAL_BYTES) throw IOException("解压总量超限（> ${MAX_TOTAL_BYTES / 1024 / 1024} MiB）")
        }
    }
}
