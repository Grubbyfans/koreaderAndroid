package com.yuezhou.reader.ui.online

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.CloudBookshelfContent
import com.yuezhou.reader.reader.CloudSync

/**
 * 在线书库统一入口：置于书架页「云端」作用域内，顶部书源 Tab 切换。
 *
 * 云存储书源复用现有 CloudBookshelfContent；检索型书源（Z-Library）在阶段 C/D 接入，
 * 当前仅展示禁用占位，不提供未完成功能。
 */
@Composable
fun OnlineLibraryScreen(
    vm: OnlineLibraryViewModel,
    onRefresh: () -> Unit,
    onOpenDetail: (CloudSync.RemoteBook) -> Unit,
    onOpenSettings: () -> Unit,
    onShowDownloads: () -> Unit,
) {
    val state = vm.selectedState

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
            // 检索型书源尚未接入，保留可见占位但不提供操作。
            FilterChip(
                selected = false,
                enabled = false,
                onClick = {},
                label = { Text("Z-Library") },
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onShowDownloads) { Text("下载") }
            TextButton(onClick = onOpenSettings) { Text("书源设置") }
        }

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
