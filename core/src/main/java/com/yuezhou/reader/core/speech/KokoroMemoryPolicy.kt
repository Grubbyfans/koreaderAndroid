package com.yuezhou.reader.core.speech

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

class KokoroMemoryException(message: String) : IllegalStateException(message)

/** 全进程最多一个模型实例；租约直到 native 安全释放后才归还，取消等待不会占用名额。 */
class KokoroModelSlot {
    private val mutex = Mutex()

    suspend fun acquire(timeoutMs: Long = 15_000): Lease {
        var acquired = false
        try {
            val locked = withTimeoutOrNull(timeoutMs) {
                mutex.lock()
                acquired = true
                true
            } ?: false
            check(locked) { "模型正在使用或释放，请停止朗读后重试" }
            return Lease { mutex.unlock() }
        } catch (t: Throwable) {
            if (acquired) mutex.unlock()
            throw t
        }
    }

    class Lease internal constructor(private val unlock: () -> Unit) {
        private val released = AtomicBoolean(false)
        fun release() { if (released.compareAndSet(false, true)) unlock() }
    }
}

object KokoroMemoryPolicy {
    /** 预留模型权重两倍与 64 MiB 工作空间，至少保留 256 MiB；不代表 native 峰值保证。 */
    fun requireAvailable(available: Long, threshold: Long, lowMemory: Boolean, modelBytes: Long) {
        val required = maxOf(256L * 1024 * 1024, threshold, modelBytes * 2 + 64L * 1024 * 1024)
        if (lowMemory || available < required) {
            throw KokoroMemoryException("可用内存不足，已保留系统 TTS 朗读能力，请关闭其他应用后重试 Kokoro")
        }
    }
}
