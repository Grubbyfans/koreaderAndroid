package com.yuezhou.reader.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeBlockRegistryTest {

    @Test
    fun `默认不阻塞`() {
        val registry = SwipeBlockRegistry()
        assertFalse(registry.blocked)
    }

    @Test
    fun `任一 token 持有即阻塞`() {
        val registry = SwipeBlockRegistry()
        registry.hold("detail:baidu")
        assertTrue(registry.blocked)
        assertTrue(registry.isHeld("detail:baidu"))
    }

    @Test
    fun `多 token 分别释放且最后一个释放后解除`() {
        val registry = SwipeBlockRegistry()
        registry.hold("detail:baidu")
        registry.hold("filter:zlibrary")
        registry.release("detail:baidu")
        assertTrue(registry.blocked)

        registry.release("filter:zlibrary")
        assertFalse(registry.blocked)
    }

    @Test
    fun `重复持有与释放不存在的 token 都安全`() {
        val registry = SwipeBlockRegistry()
        registry.hold("host:overlay")
        registry.hold("host:overlay")
        registry.release("host:overlay")
        assertFalse(registry.blocked)

        registry.release("host:overlay")
        assertFalse(registry.blocked)
    }
}
