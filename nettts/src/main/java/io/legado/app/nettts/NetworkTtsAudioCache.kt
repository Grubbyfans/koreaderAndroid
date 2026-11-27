package io.legado.app.nettts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 落盘音频缓存（原版 HttpTtsFileCache / HttpTtsDownload 的精简实现，GPLv3 来源）。
 *
 * 写入使用随机 `.tmp` 文件，校验非空后原子发布；容量淘汰跳过正在合成、排队或播放的键。
 * [exclusive] 按“目录 + 缓存键”跨实例去重，避免试听与正式朗读同时下载同一音频。
 */
class NetworkTtsAudioCache(
    private val rootDir: File,
    private val maxBytes: Long = NetworkTtsRuntime.maxCacheBytes,
    private val maxAudioBytes: Long = NetworkTtsRuntime.maxAudioBytes,
) {

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
    }

    internal class Lease(
        private val cache: NetworkTtsAudioCache,
        private val key: String,
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) cache.release(key)
        }
    }

    fun fileFor(key: String): File = File(rootDir, "$key.audio")

    fun exists(key: String): Boolean {
        val file = fileFor(key)
        return file.exists() && file.length() > 0
    }

    fun touch(key: String) {
        fileFor(key).setLastModified(System.currentTimeMillis())
    }

    internal fun retain(key: String): Lease {
        val activeKey = activeKey(key)
        synchronized(activeKeys) { activeKeys[activeKey] = (activeKeys[activeKey] ?: 0) + 1 }
        return Lease(this, key)
    }

    private fun release(key: String) {
        val activeKey = activeKey(key)
        synchronized(activeKeys) {
            val remaining = (activeKeys[activeKey] ?: 1) - 1
            if (remaining > 0) activeKeys[activeKey] = remaining else activeKeys.remove(activeKey)
        }
        trimIfNeeded()
    }

    internal suspend fun <T> exclusive(key: String, block: suspend () -> T): T {
        val lockKey = "${rootDir.absolutePath}:$key"
        val entry = synchronized(synthesisLocks) {
            synthesisLocks.getOrPut(lockKey) { LockEntry(Mutex()) }.also { it.users++ }
        }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(synthesisLocks) {
                entry.users--
                if (entry.users == 0) synthesisLocks.remove(lockKey, entry)
            }
        }
    }

    /**
     * 写入音频；[writer] 负责把字节流写进 [OutputStream]（可挂起，便于逐块检查取消）。
     * 写入超限或结果为空都会删除临时文件并抛错，绝不产生“空白音频”占位。
     */
    suspend fun write(key: String, writer: suspend (OutputStream) -> Unit) {
        val lease = retain(key)
        val target = fileFor(key)
        val temp = File(rootDir, "$key.${UUID.randomUUID()}.tmp")
        try {
            temp.outputStream().use { output ->
                writer(LimitedOutputStream(output, maxAudioBytes))
            }
            if (temp.length() <= 0L) throw IllegalStateException("TTS 返回空音频")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            trimIfNeeded()
        } finally {
            if (temp.exists()) temp.delete()
            lease.close()
        }
    }

    fun delete(key: String) {
        if (isActive(key)) return
        fileFor(key).delete()
    }

    /** 主动清理同样保护使用中文件；其释放后仍可由正常 LRU 淘汰。 */
    fun clear(): Int {
        val files = rootDir.listFiles() ?: return 0
        var removed = 0
        files.filter { it.isFile && it.name.endsWith(".audio") && !isActive(keyOf(it)) }
            .forEach { if (it.delete()) removed++ }
        return removed
    }

    fun totalBytes(): Long = rootDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun activeKey(key: String): String = "${rootDir.absolutePath}:$key"

    private fun isActive(key: String): Boolean = synchronized(activeKeys) {
        activeKeys.containsKey(activeKey(key))
    }

    private fun keyOf(file: File): String = file.name.removeSuffix(".audio")

    private fun trimIfNeeded() {
        val files = rootDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".audio") }
            ?.sortedBy { it.lastModified() }
            ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (file in files) {
            if (total <= maxBytes) break
            if (isActive(keyOf(file))) continue
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    internal class SizeLimitException(message: String) : IOException(message)

    private class LimitedOutputStream(
        private val output: OutputStream,
        private val maxBytes: Long,
    ) : OutputStream() {
        private var count = 0L

        override fun write(value: Int) {
            ensureCapacity(1)
            output.write(value)
            count++
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length.toLong())
            output.write(buffer, offset, length)
            count += length
        }

        override fun flush() = output.flush()

        private fun ensureCapacity(incoming: Long) {
            if (count + incoming > maxBytes) throw SizeLimitException("TTS 音频超过大小限制（$maxBytes 字节）")
        }
    }

    private class LockEntry(val mutex: Mutex) {
        var users: Int = 0
    }

    companion object {
        private val synthesisLocks = mutableMapOf<String, LockEntry>()
        private val activeKeys = mutableMapOf<String, Int>()

        /** 缓存键：章节标题 MD5 + 规则特征 + 文本 MD5。 */
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
