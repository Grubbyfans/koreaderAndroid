package com.yuezhou.reader.ui.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.CloudBookshelfContent
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.provider.BookProvider
import com.yuezhou.reader.core.online.provider.CatalogBookProvider
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.ui.navigation.SwipeBlockRegistry

/**
 * 在线书库统一入口：作为底部一级「云端」页，顶部书源 Tab 切换。
 *
 * 云存储书源复用现有 CloudBookshelfContent；Z-Library 走独立检索页（搜索、筛选、分页、详情）。
 * 下载任务与更多菜单位于云端标题栏右上角；书源配置入口位于设置页。
 */
@Composable
fun OnlineLibraryScreen(
    vm: OnlineLibraryViewModel,
    providerId: String?,
    onSelectProvider: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: (CloudSync.RemoteBook) -> Unit,
    onOpenSettings: () -> Unit,
    interactionsEnabled: Boolean = true,
    swipeBlocker: SwipeBlockRegistry? = null,
) {
    val state = providerId?.let { vm.stateOf(it) }

    // 详情展开时关闭横滑：避免从详情页开始拖动导致误切栏目。
    val detailOpen = state?.detail != null
    DisposableEffect(swipeBlocker, providerId, detailOpen) {
        val token = "detail:${providerId.orEmpty()}"
        if (detailOpen) swipeBlocker?.hold(token)
        onDispose { swipeBlocker?.release(token) }
    }

    // 二级导航栏已抽离到 MainActivity 外层，整个云端栏目共用一份固定的切换条（阶段 E 评审 P0 修复）。
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (providerId == null || state == null) {
                // 全部书源被禁用：仍保留「云端」一级入口，给出明确的恢复路径。
                CloudEmptyState(onOpenSettings)
            } else if (vm.registry.get(providerId) is CatalogBookProvider) {
                CatalogLibraryScreen(
                    vm = vm,
                    state = state,
                    providerId = providerId,
                    onOpenSettings = onOpenSettings,
                    interactionsEnabled = interactionsEnabled,
                    swipeBlocker = swipeBlocker,
                )
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CloudBookshelfContent(
                        books = vm.legacyBooks(state.items),
                        loading = state.loading,
                        showRefreshBar = state.refreshing,
                        error = state.error,
                        backendLabel = state.backendLabel.ifBlank { null },
                        fetchedAt = state.fetchedAt,
                        fromCache = state.fromCache,
                        scrollKey = "cloud.$providerId",
                        onRefresh = onRefresh,
                        onOpenDetail = onOpenDetail,
                    )
                }
            }
        }

        // 详情页覆盖在整页之上，而不是追加在内容列之后（否则会被 fillMaxSize 内容挤出可视区）。
        if (providerId != null && state != null) {
            state.detail?.let { detail ->
                // 页面切换期间不接管返回键，避免滑出中的页面拦截手势返回。
                BackHandler(enabled = interactionsEnabled) { vm.closeDetail(providerId) }
                BookDetailScreen(
                    book = detail,
                    state = state,
                    vm = vm,
                    providerId = providerId,
                    interactionsEnabled = interactionsEnabled,
                    onBack = { vm.closeDetail(providerId) },
                )
            }
        }
    }
}

/** 全部书源禁用时的云端空态。 */
@Composable
private fun CloudEmptyState(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("暂无启用的书源", style = MaterialTheme.typography.titleMedium)
        Text(
            "在设置中启用百度网盘、WebDAV 或 Z-Library 后即可使用云端书库",
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("去设置") }
    }
}

/**
 * 云端二级书源切换条：等宽分段控件，书源多时改为横向滚动保留最小宽度。
 * 已抽离到 Pager 外层，由 MainActivity 统一渲染一份，避免随页面整体滑动（阶段 E 评审 P0 修复）。
 *
 * @param providers 当前可用书源列表。
 * @param selectedId 当前选中的书源 id；为空或与 providers 无匹配时所有项都不高亮。
 * @param onSelect 点击某书源后的回调；由外层负责发起导航（如 `navigateTo("cloud.$id")`）。
 */
@Composable
fun CloudSourceTabs(
    providers: List<BookProvider>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    // 书源较多时等宽分段会挤压名称，改为可横向滚动且保留最小宽度。
    val scrollable = providers.size > 3
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(YuezhouColors.Surface)
            .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        providers.forEach { provider ->
            val selected = provider.id == selectedId
            val backgroundColor by animateColorAsState(
                targetValue = if (selected) YuezhouColors.BrandContainer else Color.Transparent,
                label = "cloudTabBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                label = "cloudTabFg",
            )
            val fontWeightFactor by animateFloatAsState(
                targetValue = if (selected) 600f else 400f,
                label = "cloudTabWeight",
            )
            Box(
                modifier = Modifier
                    .then(if (scrollable) Modifier.widthIn(min = 96.dp) else Modifier.weight(1f))
                    .clip(RoundedCornerShape(17.dp))
                    .background(backgroundColor)
                    .clickable { onSelect(provider.id) }
                    .padding(horizontal = if (scrollable) 14.dp else 0.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight(fontWeightFactor.toInt()),
                    ),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
