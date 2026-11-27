package io.legado.app.nettts

import java.io.File

/**
 * 落盘音频缓存（原版 HttpTtsFileCache / HttpTtsDownload 的精简实现，GPLv3 来源）。
 *
 * 行为对齐原版：先写 `.tmp`，校验非空后原子重命名发布；同时限制目录总量
 * （[NetworkTtsRuntime.maxCacheBytes]，超出按最后修改时间淘汰最旧文件）。
 * 取消由调用方关闭流并删除临时文件实现，避免阻塞下载不响应协程取消。
 */
class NetworkTtsAudioCache(
    private val rootDir: File,
    private val maxBytes: Long = NetworkTtsRuntime.maxCacheBytes,
) {

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    fun fileFor(key: String): File = File(rootDir, "$key.audio")

    fun exists(key: String): Boolean {
        val file = fileFor(key)
        return file.exists() && file.length() > 0
    }

    fun touch(key: String) {
        fileFor(key).setLastModified(System.currentTimeMillis())
    }

    /**
     * 写入音频；[writer] 负责把字节流写进 [java.io.OutputStream]（可挂起，便于逐块检查取消）。
     * 写入超限或结果为空都会删除临时文件并抛错，绝不产生"空白音频"占位。
     */
    suspend fun write(key: String, writer: suspend (java.io.OutputStream) -> Unit) {
        val target = fileFor(key)
        val temp = File(rootDir, "$key.tmp")
        try {
            temp.outputStream().use { output -> writer(output) }
            if (temp.length() <= 0L) {
                throw IllegalStateException("TTS 返回空音频")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            trimIfNeeded()
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun delete(key: String) {
        fileFor(key).delete()
    }

    fun clear(): Int {
        val files = rootDir.listFiles() ?: return 0
        var removed = 0
        files.forEach { if (it.delete()) removed++ }
        return removed
    }

    fun totalBytes(): Long = rootDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun trimIfNeeded() {
        val files = rootDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files) {
            if (total <= maxBytes) break
            total -= file.length()
            file.delete()
        }
    }

    companion object {
        /**
         * 缓存键：章节标题 MD5 + 规则特征 + 文本 MD5。
         *
         * 与原版区别：把 `ruleHash` 纳入键，避免规则改动后复用旧音频（原版只算 url/速率/变量/登录头）。
         */
        fun buildKey(chapterTitle: String, ruleHash: String, speechRate: Int, text: String): String {
            val chapterKey = md5(chapterTitle).take(16)
            val identity = buildString {
                arrayOf(ruleHash, speechRate.toString(), text).forEach { value ->
                    append(value.length).append(':').append(value)
                }
            }
            return "${chapterKey}_${md5(identity).take(16)}"
        }

        fun md5(text: String): String {
            val digest = java.security.MessageDigest.getInstance("MD5")
                .digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
