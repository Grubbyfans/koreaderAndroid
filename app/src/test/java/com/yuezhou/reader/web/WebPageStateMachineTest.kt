package com.yuezhou.reader.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPageStateMachineTest {

    @Test
    fun `初始为空闲且不阻塞`() {
        val machine = WebPageStateMachine()
        assertEquals(WebPageState.IDLE, machine.state)
        assertFalse(machine.blocking)
    }

    @Test
    fun `每次主导航产生新代次`() {
        val machine = WebPageStateMachine()
        val first = machine.beginNavigation()
        val second = machine.beginNavigation()
        assertEquals(WebPageState.LOADING, machine.state)
        assertTrue(second > first)
        assertTrue(machine.blocking)
    }

    @Test
    fun `旧代次回调不会覆盖新状态`() {
        val machine = WebPageStateMachine()
        val first = machine.beginNavigation()
        machine.beginNavigation()

        assertFalse(machine.markNetworkError(first))
        assertFalse(machine.markContentVisible(first))
        assertEquals(WebPageState.LOADING, machine.state)
    }

    @Test
    fun `503 等待态在内容可见后撤去`() {
        val machine = WebPageStateMachine()
        val generation = machine.beginNavigation()

        assertTrue(machine.markWaiting(generation))
        assertEquals(WebPageState.VERIFICATION_OR_WAITING, machine.state)
        assertTrue(machine.markContentVisible(generation))
        assertEquals(WebPageState.CONTENT_VISIBLE, machine.state)
        assertFalse(machine.blocking)
    }

    @Test
    fun `内容可见后超时不再生效`() {
        val machine = WebPageStateMachine()
        val generation = machine.beginNavigation()
        machine.markContentVisible(generation)

        assertFalse(machine.markTimeout(generation))
        assertEquals(WebPageState.CONTENT_VISIBLE, machine.state)
    }

    @Test
    fun `超时后可继续等待且不产生新代次`() {
        val machine = WebPageStateMachine()
        val generation = machine.beginNavigation()

        assertTrue(machine.markTimeout(generation))
        assertEquals(WebPageState.TIMEOUT, machine.state)
        machine.continueWaiting()
        assertEquals(WebPageState.LOADING, machine.state)
        assertEquals(generation, machine.generation)
    }

    @Test
    fun `网络错误不会被已完成页面回调撤销`() {
        val machine = WebPageStateMachine()
        val generation = machine.beginNavigation()

        assertTrue(machine.markNetworkError(generation))
        assertFalse(machine.markContentVisible(generation))
        assertEquals(WebPageState.NETWORK_ERROR, machine.state)
    }

    @Test
    fun `渲染器退出后忽略旧回调并可重置`() {
        val machine = WebPageStateMachine()
        val generation = machine.beginNavigation()
        machine.markContentVisible(generation)

        machine.markRendererGone()
        assertEquals(WebPageState.RENDERER_GONE, machine.state)
        assertFalse(machine.markContentVisible(generation))
        assertFalse(machine.markTimeout(generation))

        machine.resetAfterRendererGone()
        assertEquals(WebPageState.IDLE, machine.state)
    }
}
