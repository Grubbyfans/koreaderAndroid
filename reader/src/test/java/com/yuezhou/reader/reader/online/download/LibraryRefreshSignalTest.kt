package com.yuezhou.reader.reader.online.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LibraryRefreshSignalTest {

    @Test
    fun `初始版本为零不触发刷新`() {
        assertEquals(0L, LibraryRefreshSignal().version.value)
    }

    @Test
    fun `每次导入成功版本号自增`() {
        val signal = LibraryRefreshSignal()
        signal.notifyImportSucceeded()
        assertEquals(1L, signal.version.value)
        repeat(3) { signal.notifyImportSucceeded() }
        assertEquals(4L, signal.version.value)
    }

    @Test
    fun `版本号单调递增不重复便于UI去重`() {
        val signal = LibraryRefreshSignal()
        val seen = (1..20).map {
            signal.notifyImportSucceeded()
            signal.version.value
        }
        assertEquals((1L..20L).toList(), seen)
        assertEquals(seen.size, seen.toSet().size)
    }
}
