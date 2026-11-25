package com.yuezhou.reader.ui.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.CloudBookshelfContent
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.provider.BookProvider
import com.yuezhou.reader.core.online.provider.CatalogBookProvider
import com.yuezhou.reader.reader.CloudSync

/**
 * 在线书库统一入口：作为底部一级「云端」页，顶部书源 Tab 切换。
 *
 * 云存储书源复用现有 CloudBookshelfContent；Z-Library 走独立检索页（搜索、筛选、分页、详情）。
 * 下载任务与更多菜单位于云端标题栏右上角；书源配置入口位于设置页。
 */
@Composable
fun OnlineLibraryScreen(
    vm: OnlineLibraryViewModel,
    onRefresh: () -> Unit,
    onOpenDetail: (CloudSync.RemoteBook) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state = vm.selectedState

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            CloudSourceTabs(
                providers = vm.registry.enabled(),
                selectedId = vm.selectedProviderId,
                onSelect = vm::selectProvider,
            )

            if (vm.registry.get(vm.selectedProviderId) is CatalogBookProvider) {
                CatalogLibraryScreen(vm = vm, state = state, onOpenSettings = onOpenSettings)
            } else {
                CloudBookshelfContent(
                    books = vm.legacyBooks(state.items),
                    loading = state.loading,
                    showRefreshBar = state.refreshing,
                    error = state.error,
                    backendLabel = state.backendLabel.ifBlank { null },
                    fetchedAt = state.fetchedAt,
                    fromCache = state.fromCache,
                    onRefresh = onRefresh,
                    onOpenDetail = onOpenDetail,
                )
            }
        }

        state.detail?.let { detail ->
            BackHandler(enabled = true) { vm.closeDetail() }
            BookDetailScreen(
                book = detail,
                state = state,
                vm = vm,
                onBack = { vm.closeDetail() },
            )
        }
    }
}

/** 紧凑书源切换：等宽分段控件，避免占用过多纵向空间。 */
@Composable
private fun CloudSourceTabs(
    providers: List<BookProvider>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(YuezhouColors.Surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        providers.forEach { provider ->
            val selected = provider.id == selectedId
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (selected) YuezhouColors.BrandContainer else Color.Transparent)
                    .clickable { onSelect(provider.id) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    provider.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
