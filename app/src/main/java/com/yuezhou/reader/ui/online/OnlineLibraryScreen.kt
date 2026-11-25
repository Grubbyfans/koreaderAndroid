package com.yuezhou.reader.ui.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.CloudBookshelfContent
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.core.online.provider.CatalogBookProvider

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                vm.registry.enabled().forEach { provider ->
                    FilterChip(
                        selected = provider.id == vm.selectedProviderId,
                        onClick = { vm.selectProvider(provider.id) },
                        label = { Text(provider.displayName) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }

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
