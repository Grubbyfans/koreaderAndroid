package com.yuezhou.reader.ui.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 导航状态：区分「导航请求」与「已提交页面」。
 *
 * - [requestedKey] 只表示用户意图（点击一级/二级导航、返回键），由 Pager 去执行滚动。
 * - [committedKey] 只有在 Pager 真正落页后才写入，业务加载与顶栏/底栏都只认它。
 * - 页面内容以各自页面的 providerId 为准，动画期间相邻页可安全同时组合。
 * - 页面列表动态变化时按稳定 key 重定位；无效 key 有明确回退链。
 */
class ShelfNavigator(
    initialKey: String,
    providerIds: List<String>,
) {
    /** 当前可用的扁平页面列表。 */
    var pages: List<ShelfPage> by mutableStateOf(ShelfPage.build(providerIds))
        private set

    /** 唯一已提交的页面 key（落页后写入）。 */
    var committedKey: String by mutableStateOf(initialKey)
        private set

    /** 当前导航请求 key；null 表示没有未完成的程序化导航（手势拖动不算请求）。 */
    var requestedKey: String? by mutableStateOf(null)
        private set

    init {
        committedKey = resolve(pages, initialKey)
    }

    /** 是否仍有未落页的程序化导航。手势拖动请配合 PagerState.isScrollInProgress 判断。 */
    val isNavigating: Boolean
        get() = requestedKey != null

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

    /** 稳定 key 在扁平页面列表中的下标；不存在返回 -1。 */
    fun indexOf(key: String): Int = pages.indexOfFirst { it.key == key }

    /**
     * 书源增删/排序后同步页面列表，并按稳定 key 重定位。
     *
     * 回退链：保留原 key → 首个可用书源 → 云端空态 → 首个页面。
     * 若待执行的目标页在本次变化中失效，则取消该次导航（请求退回已提交页）。
     */
    fun syncPages(providerIds: List<String>) {
        pages = ShelfPage.build(providerIds)
        committedKey = resolve(pages, committedKey)
        val pending = requestedKey
        if (pending != null && pages.none { it.key == pending }) requestedKey = null
    }

    /**
     * 导航请求入口：只记录目标，不切换业务页。
     *
     * 合法的 key 直接作为目标；未知的云端 key 按回退链落到可用书源；
     * 其它无法识别的 key 保持当前导航，避免一次错误跳转把用户带回书库。
     */
    fun navigateTo(key: String) {
        val target = when {
            pages.any { it.key == key } -> key
            key.startsWith(CLOUD_PREFIX) -> resolve(pages, key)
            else -> return
        }
        requestedKey = target
    }

    /** Pager 落页后提交业务页面；无效 key 忽略，避免页面列表变化瞬间写坏状态。 */
    fun commit(key: String) {
        if (pages.none { it.key == key }) return
        committedKey = key
        // 目标已通过本次落页完成，清掉请求；手势落页时本就没有请求。
        if (requestedKey == key) requestedKey = null
    }

    /** 程序化滚动结束（含被手势取消）后清理该次请求，避免 isNavigating 残留。 */
    fun clearRequest(key: String) {
        if (requestedKey == key) requestedKey = null
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
