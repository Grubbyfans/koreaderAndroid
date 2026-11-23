package com.yuezhou.reader.core.speech

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KokoroMemoryPolicyTest {
    @Test
    fun `旧模型释放前不允许新模型占用名额且重复释放无副作用`() = runTest {
        val slot = KokoroModelSlot()
        val first = slot.acquire()
        var second: KokoroModelSlot.Lease? = null
        val waiting = launch { second = slot.acquire() }
        runCurrent()
        assertNull(second)
        first.release()
        first.release()
        runCurrent()
        assertNotNull(second)
        second!!.release()
        waiting.join()
        slot.acquire().release()
    }

    @Test
    fun `取消排队请求不泄漏模型名额`() = runTest {
        val slot = KokoroModelSlot()
        val first = slot.acquire()
        val waiting = launch { slot.acquire() }
        runCurrent()
        waiting.cancel()
        waiting.join()
        first.release()
        slot.acquire().release()
    }

    @Test
    fun `等待超时不能解锁仍在使用的模型`() = runTest {
        val slot = KokoroModelSlot()
        val first = slot.acquire()
        var failed = false
        val waiting = launch {
            try { slot.acquire(1) } catch (_: IllegalStateException) { failed = true }
        }
        testScheduler.advanceUntilIdle()
        assertTrue(failed)
        first.release()
        waiting.join()
        slot.acquire().release()
    }

    @Test
    fun `低内存标志或可用内存不足时拒绝加载`() {
        val mib = 1024L * 1024
        assertThrows(KokoroMemoryException::class.java) { KokoroMemoryPolicy.requireAvailable(4096*mib, 128*mib, true, 300*mib) }
        assertThrows(KokoroMemoryException::class.java) { KokoroMemoryPolicy.requireAvailable(500*mib, 128*mib, false, 300*mib) }
        KokoroMemoryPolicy.requireAvailable(2048*mib, 128*mib, false, 300*mib)
    }
}
