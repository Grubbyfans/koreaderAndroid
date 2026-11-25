package com.yuezhou.reader.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfNavigatorTest {

    private val providers = listOf("baidu", "webdav", "zlibrary")

    @Test
    fun `页面顺序为书库 各书源 设置`() {
        val pages = ShelfPage.build(providers)
        assertEquals(
            listOf("shelf.local", "cloud.baidu", "cloud.webdav", "cloud.zlibrary", "settings"),
            pages.map { it.key },
        )
    }

    @Test
    fun `没有启用书源时保留云端空态页`() {
        val pages = ShelfPage.build(emptyList())
        assertEquals(listOf("shelf.local", "cloud.empty", "settings"), pages.map { it.key })
        assertEquals(Tab.云端, pages[1].tab)
    }

    @Test
    fun `恢复已保存的合法 key`() {
        val navigator = ShelfNavigator(initialKey = "cloud.webdav", providerIds = providers)
        assertEquals("cloud.webdav", navigator.committedKey)
        assertEquals(Tab.云端, navigator.tab)
        assertEquals("webdav", navigator.providerId)
    }

    @Test
    fun `恢复不存在的书源回退首个可用书源`() {
        val navigator = ShelfNavigator(initialKey = "cloud.removed", providerIds = providers)
        assertEquals("cloud.baidu", navigator.committedKey)
        assertEquals("baidu", navigator.providerId)
    }

    @Test
    fun `全部禁用时恢复云端 key 落到空态页`() {
        val navigator = ShelfNavigator(initialKey = "cloud.webdav", providerIds = emptyList())
        assertEquals("cloud.empty", navigator.committedKey)
        assertEquals(Tab.云端, navigator.tab)
        assertNull(navigator.providerId)
    }

    @Test
    fun `书库与设置页不派生书源`() {
        val navigator = ShelfNavigator(initialKey = ShelfPage.LocalShelf.key, providerIds = providers)
        assertEquals(Tab.书架, navigator.tab)
        assertNull(navigator.providerId)
        navigator.navigateTo(ShelfPage.Settings.key)
        assertEquals(Tab.设置, navigator.tab)
        assertNull(navigator.providerId)
    }

    @Test
    fun `书源增删后按稳定 key 重定位`() {
        val navigator = ShelfNavigator(initialKey = "cloud.webdav", providerIds = providers)
        navigator.syncPages(listOf("baidu", "webdav"))
        assertEquals("cloud.webdav", navigator.committedKey)

        // 当前书源被移除：回退到首个可用书源，而不是沿用旧 key。
        navigator.syncPages(listOf("baidu", "zlibrary"))
        assertEquals("cloud.baidu", navigator.committedKey)
        assertEquals("baidu", navigator.providerId)
    }

    @Test
    fun `禁用全部书源后再启用可恢复云端页`() {
        val navigator = ShelfNavigator(initialKey = "cloud.webdav", providerIds = providers)
        navigator.syncPages(emptyList())
        assertEquals("cloud.empty", navigator.committedKey)

        navigator.syncPages(providers)
        assertEquals("cloud.baidu", navigator.committedKey)
        assertEquals(Tab.云端, navigator.tab)
    }

    @Test
    fun `云端入口优先恢复上次书源`() {
        val navigator = ShelfNavigator(initialKey = ShelfPage.LocalShelf.key, providerIds = providers)
        assertEquals("cloud.zlibrary", navigator.cloudKey("zlibrary"))
        // 上次书源已不存在时回退首个可用书源。
        assertEquals("cloud.baidu", navigator.cloudKey("removed"))
        assertEquals("cloud.baidu", navigator.cloudKey(null))
    }

    @Test
    fun `没有启用书源时云端入口指向空态页`() {
        val navigator = ShelfNavigator(initialKey = ShelfPage.LocalShelf.key, providerIds = emptyList())
        assertEquals("cloud.empty", navigator.cloudKey("baidu"))
    }

    @Test
    fun `导航到无效 key 保持当前页`() {
        val navigator = ShelfNavigator(initialKey = "cloud.baidu", providerIds = providers)
        navigator.navigateTo("unknown.page")
        assertEquals("cloud.baidu", navigator.committedKey)
    }

    @Test
    fun `导航到合法 key 更新派生值`() {
        val navigator = ShelfNavigator(initialKey = ShelfPage.LocalShelf.key, providerIds = providers)
        navigator.navigateTo(ShelfPage.Settings.key)
        assertEquals(Tab.设置, navigator.tab)
        navigator.navigateTo("cloud.webdav")
        assertEquals(Tab.云端, navigator.tab)
        assertEquals("webdav", navigator.providerId)
    }
}
