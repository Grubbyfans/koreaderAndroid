package com.yuezhou.reader.tts

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import com.yuezhou.reader.core.speech.AudioHealthCheck
import com.yuezhou.reader.core.speech.KokoroMemoryPolicy
import com.yuezhou.reader.core.speech.ModelArchiveBudget
import com.yuezhou.reader.core.speech.ModelImportSafety
import com.yuezhou.reader.speech.KokoroImportProgress
import com.yuezhou.reader.speech.KokoroImportResult
import com.yuezhou.reader.speech.KokoroImportState
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

/** 租约由 provider 持有：导入、验证和替换全过程不能与加载、朗读或删除模型并发。 */
internal object KokoroModelImporter {
    private const val MAX_ARCHIVE_BYTES = 1_500L * 1024 * 1024
    private const val RESERVE_BYTES = 64L * 1024 * 1024
    private const val TRIAL_TEXT = "你好，这是模型试音。"

    suspend fun import(context: Context, uri: Uri): KokoroImportResult = withContext(Dispatchers.IO) {
        val rootDir = KokoroModels.rootDir(context.filesDir).apply { mkdirs() }
        val staging = File(rootDir, ".import-${UUID.randomUUID()}")
        val reporter = Reporter()
        try {
            reporter.stage("检查存储空间")
            val declared = querySize(context, uri)
            if (declared != null && declared > MAX_ARCHIVE_BYTES) throw IOException("压缩包超过 1500 MiB 上限")
            val required = maxOf(300L * 1024 * 1024, (declared ?: 0L) * 2)
            if (rootDir.usableSpace < required) throw IOException("存储空间不足，需要至少 ${required / 1024 / 1024} MiB")
            check(staging.mkdirs()) { "无法创建导入临时目录" }
            val format = unpack(context, uri, staging, declared, reporter)
            reporter.stage("检查模型资源")
            val entries = staging.listFiles().orEmpty()
            val modelRoot = if (entries.size == 1 && entries[0].isDirectory) entries[0] else staging
            KokoroModels.validateRoot(modelRoot)?.let { throw IOException(it) }
            val sourceFile = File(modelRoot, KokoroModels.MANIFEST_FILE)
            if (sourceFile.length() > 1024 * 1024) throw IOException("模型清单过大")
            val source = if (sourceFile.isFile) JSONObject(sourceFile.readText()) else null
            val modelId = sanitizeId(source?.optString("id").orEmpty().ifBlank {
                if (modelRoot != staging) modelRoot.name else "kokoro-import-${System.currentTimeMillis()}"
            })
            // 新清单先在 staging 中完成，写失败不会触碰旧模型。
            val manifest = buildManifest(modelRoot, modelId, source, reporter)
            reporter.stage("验证模型并试合成，请稍候")
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            KokoroMemoryPolicy.requireAvailable(memory.availMem, memory.threshold, memory.lowMemory,
                KokoroModels.modelFile(modelRoot)!!.length())
            withContext(NonCancellable) { trialSynthesis(modelRoot) }
            currentCoroutineContext().ensureActive()
            manifest.put("validated", true)
            manifest.put("sampleRate", 24_000)
            reporter.stage("保存模型清单")
            KokoroModels.writeManifest(modelRoot, manifest)
            currentCoroutineContext().ensureActive()
            reporter.stage("安装模型")
            ModelImportSafety.commit(modelRoot, KokoroModels.dir(context.filesDir, modelId))
            reporter.stage("导入完成", 1f, running = false)
            KokoroImportResult(true, modelId, "已导入 $modelId（${format.uppercase()}，校验与试音通过）")
        } catch (cancelled: CancellationException) {
            reporter.stage("导入已取消", running = false)
            throw cancelled
        } catch (_: OutOfMemoryError) {
            reporter.stage("导入失败：内存不足", running = false)
            KokoroImportResult(false, message = "导入失败：内存不足，请关闭其他应用后重试")
        } catch (e: Exception) {
            reporter.stage("导入失败", running = false)
            KokoroImportResult(false, message = e.message ?: "模型导入失败")
        } finally {
            KokoroModels.deleteRecursively(staging)
        }
    }

    fun delete(filesDir: File, modelId: String): Boolean = runCatching {
        val dir = KokoroModels.dir(filesDir, modelId)
        if (!dir.isDirectory) return false
        val tomb = File(dir.parentFile, ".deleted-${UUID.randomUUID()}")
        if (!dir.renameTo(tomb)) return false
        tomb.deleteRecursively()
    }.getOrDefault(false)

    private fun querySize(context: Context, uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst() && index >= 0 && !c.isNull(index)) c.getLong(index).takeIf { it > 0 } else null
        }
    }.getOrNull()

    private suspend fun unpack(context: Context, uri: Uri, dest: File, declared: Long?, reporter: Reporter): String {
        val raw = context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取所选文件")
        val counted = object : FilterInputStream(raw) {
            var count = 0L
            fun add(n: Int) {
                if (n <= 0) return
                count += n
                if (count > MAX_ARCHIVE_BYTES) throw IOException("实际压缩包体积超过上限")
                reporter.bytes("解压模型资源", count, declared)
            }
            override fun read(): Int = super.read().also { if (it >= 0) add(1) }
            override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also(::add)
            override fun skip(n: Long): Long {
                val buffer = ByteArray(minOf(n, 8192L).coerceAtLeast(1L).toInt())
                var skipped = 0L
                while (skipped < n) {
                    val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
                    if (read < 0) break
                    skipped += read
                }
                return skipped
            }
        }
        reporter.stage("解压模型资源")
        BufferedInputStream(counted).use { input ->
            input.mark(8)
            val head = ByteArray(4)
            val n = input.read(head)
            input.reset()
            return when {
                n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() -> {
                    unpackZip(input, dest); "zip"
                }
                n >= 3 && head[0] == 'B'.code.toByte() && head[1] == 'Z'.code.toByte() && head[2] == 'h'.code.toByte() -> {
                    unpackTar(input, dest); "tar.bz2"
                }
                else -> throw IOException("仅支持 ZIP 或 tar.bz2 模型包")
            }
        }
    }

    private suspend fun unpackZip(input: InputStream, dest: File) {
        val budget = ModelArchiveBudget()
        ZipArchiveInputStream(input).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextZipEntry ?: break
                budget.beginEntry()
                if (entry.isUnixSymlink || entry.generalPurposeBit.usesEncryption() || !zip.canReadEntryData(entry)) {
                    throw IOException("不支持链接、加密或无法解压的条目")
                }
                val out = ModelImportSafety.resolve(dest, entry.name)
                if (entry.isDirectory) {
                    if (!ignoreEntry(entry.name) && !out.isDirectory && !out.mkdirs()) throw IOException("无法创建模型资源目录")
                    copyLimited(zip, null, budget, dest)
                } else copyLimited(zip, if (ignoreEntry(entry.name)) null else out, budget, dest)
            }
        }
    }

    private suspend fun unpackTar(input: InputStream, dest: File) {
        val budget = ModelArchiveBudget()
        TarArchiveInputStream(BZip2CompressorInputStream(input)).use { tar ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = tar.nextTarEntry ?: break
                budget.beginEntry()
                if (entry.isSymbolicLink || entry.isLink || (!entry.isFile && !entry.isDirectory)) {
                    throw IOException("压缩包包含链接或不支持的文件类型")
                }
                val out = ModelImportSafety.resolve(dest, entry.name)
                if (entry.isDirectory) {
                    if (!ignoreEntry(entry.name) && !out.isDirectory && !out.mkdirs()) throw IOException("无法创建模型资源目录")
                    copyLimited(tar, null, budget, dest)
                } else copyLimited(tar, if (ignoreEntry(entry.name)) null else out, budget, dest)
            }
        }
    }

    private fun ignoreEntry(name: String) = name == "__MACOSX" || name.startsWith("__MACOSX/") || name.endsWith(".DS_Store")

    private suspend fun copyLimited(input: InputStream, out: File?, budget: ModelArchiveBudget, root: File) {
        if (out != null) {
            if (out.exists()) throw IOException("压缩包包含重复资源文件")
            out.parentFile?.mkdirs()
        }
        val output = out?.let(::FileOutputStream)
        try {
            val buffer = ByteArray(256 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                budget.addBytes(n.toLong())
                if (out != null && root.usableSpace < n + RESERVE_BYTES) throw IOException("解压空间不足，原模型保持不变")
                output?.write(buffer, 0, n)
            }
        } finally { output?.close() }
    }

    private suspend fun buildManifest(root: File, id: String, source: JSONObject?, reporter: Reporter): JSONObject {
        val modelFile = KokoroModels.modelFile(root)!!
        for (name in listOf(modelFile.name, "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt")) {
            if (File(root, name).length() == 0L) throw IOException("必需资源为空：$name")
        }
        val actual = root.walkTopDown().filter { it.isFile && it != File(root, KokoroModels.MANIFEST_FILE) }.toList()
        val total = actual.sumOf { it.length() }
        var processed = 0L
        val files = JSONObject()
        reporter.stage("校验资源 SHA-256", 0f)
        for (file in actual) {
            currentCoroutineContext().ensureActive()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    processed += n
                    reporter.bytes("校验资源 SHA-256", processed, total)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            files.put(file.relativeTo(root).invariantSeparatorsPath, JSONObject().put("size", file.length()).put("sha256", hash))
        }
        val expected = source?.optJSONObject("files")
        if (expected != null) for (name in expected.keys()) {
            ModelImportSafety.resolve(root, name)
            val item = files.optJSONObject(name) ?: throw IOException("清单资源缺失：$name")
            val details = expected.optJSONObject(name)
            val hash = details?.optString("sha256") ?: expected.optString(name)
            if (!Regex("[a-fA-F0-9]{64}").matches(hash) || !hash.equals(item.getString("sha256"), true)) {
                throw IOException("资源哈希校验失败：$name")
            }
            if (details?.has("size") == true && details.getLong("size") != item.getLong("size")) throw IOException("资源大小校验失败：$name")
        }
        val modelHash = files.getJSONObject(modelFile.name).getString("sha256")
        val oldHash = source?.optString("sha256").orEmpty()
        if (oldHash.isNotBlank() && !oldHash.equals(modelHash, true)) throw IOException("模型主文件哈希校验失败")
        return JSONObject().apply {
            put("id", id)
            put("label", source?.optString("label").orEmpty().ifBlank { id }.take(128))
            put("version", source?.optString("version").orEmpty().ifBlank { "v1_1" })
            put("precision", source?.optString("precision").orEmpty().ifBlank {
                if (id.contains("int8", true) || modelFile.name.contains("int8")) "int8" else "fp32"
            })
            put("sdkVersion", "1.13.4")
            put("languages", JSONArray(listOf("zh", "en")))
            put("speakerIds", JSONArray((3..102).toList()))
            put("files", files)
            put("modelFile", modelFile.name)
            put("sha256", modelHash)
            put("importedAt", System.currentTimeMillis())
        }
    }

    private fun trialSynthesis(root: File) {
        val tts = KokoroModels.createTts(root)
        try {
            if (tts.numSpeakers() <= 3) throw IOException("模型缺少中文试音音色")
            val audio = tts.generate(TRIAL_TEXT, 3, 1.0f)
            if (audio.sampleRate != 24_000) throw IOException("模型采样率不符合要求")
            val report = AudioHealthCheck().check(audio.samples, audio.sampleRate)
            if (!report.ok) throw IOException("试音健康检查失败：${report.reason}")
        } finally { tts.release() }
    }

    private fun sanitizeId(raw: String): String = raw.trim().replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('.', '-').take(64).also { if (it.isBlank()) throw IOException("模型 ID 非法") }

    private class Reporter {
        private var lastAt = 0L
        fun stage(stage: String, fraction: Float? = null, running: Boolean = true) {
            lastAt = 0L
            KokoroImportState.update(KokoroImportProgress(stage, fraction, running = running))
        }
        fun bytes(stage: String, processed: Long, total: Long?) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAt < 100) return
            lastAt = now
            KokoroImportState.update(KokoroImportProgress(stage,
                total?.takeIf { it > 0 }?.let { (processed.toDouble() / it).toFloat().coerceIn(0f, 1f) }, processed, total))
        }
    }
}
