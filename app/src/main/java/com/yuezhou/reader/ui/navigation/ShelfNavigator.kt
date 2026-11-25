package com.yuezhou.reader.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 唯一的已提交导航状态（阶段 A 不接入 Pager）。
 *
 * - `committedKey` 只允许通过 [navigateTo] / [syncPages] 修改，所有导航入口统一走这里。
 * - 页面内容与业务请求以 committed 书源为准，避免点击瞬间就切换业务数据源。
 * - 页面列表动态变化时按稳定 key 重定位；无效 key 有明确回退链。
 */
class ShelfNavigator(
    initialKey: String,
    providerIds: List<String>,
) {
    /** 当前可用的扁平页面列表。 */
    var pages: List<ShelfPage> by mutableStateOf(ShelfPage.build(providerIds))
        private set

    /** 唯一已提交的页面 key。 */
    var committedKey: String by mutableStateOf(initialKey)
        private set

    init {
        committedKey = resolve(pages, initialKey)
    }

    /** 一级栏目由 committed key 派生，不再单独维护 currentTab。 */
    val tab: Tab
        get() = when {
            committedKey == ShelfPage.LocalShelf.key -> Tab.书架
            committedKey.startsWith(CLOUD_PREFIX) -> Tab.云端
            else -> Tab.设置
        }

    /** 云端具体书源 id；空态页返回 null。 */
    val providerId: String?
        get() = when {
            committedKey == ShelfPage.CloudEmpty.key -> null
            committedKey.startsWith(CLOUD_PREFIX) -> committedKey.removePrefix(CLOUD_PREFIX).ifBlank { null }
            else -> null
        }

    /**
     * 书源增删/排序后同步页面列表，并按稳定 key 重定位当前页。
     *
     * 回退链：保留原 key → 首个可用书源 → 云端空态 → 首个页面。
     */
    fun syncPages(providerIds: List<String>) {
        pages = ShelfPage.build(providerIds)
        committedKey = resolve(pages, committedKey)
    }

    /**
     * 统一导航入口：合法 key 直接提交；未知的云端 key 按回退链落到可用书源。
     *
     * 其它无法识别的 key 保持当前页，避免一次错误跳转把用户带回书库。
     */
    fun navigateTo(key: String) {
        when {
            pages.any { it.key == key } -> committedKey = key
            key.startsWith(CLOUD_PREFIX) -> committedKey = resolve(pages, key)
        }
    }

    /** 点击「云端」时恢复上次访问的有效书源；不存在则回退首个可用书源/空态。 */
    fun cloudKey(preferredProviderId: String?): String {
        if (preferredProviderId != null &&
            pages.any { it is ShelfPage.Cloud && it.providerId == preferredProviderId }
        ) {
            return "$CLOUD_PREFIX$preferredProviderId"
        }
        return pages.firstOrNull { it is ShelfPage.Cloud }?.key ?: ShelfPage.CloudEmpty.key
    }

    private fun resolve(pages: List<ShelfPage>, key: String): String {
        if (pages.any { it.key == key }) return key
        if (key.startsWith(CLOUD_PREFIX)) {
            return pages.firstOrNull { it is ShelfPage.Cloud }?.key ?: ShelfPage.CloudEmpty.key
        }
        return pages.firstOrNull { it.key == key }?.key
            ?: pages.firstOrNull()?.key
            ?: ShelfPage.LocalShelf.key
    }

    private companion object {
        const val CLOUD_PREFIX = "cloud."
    }
}
