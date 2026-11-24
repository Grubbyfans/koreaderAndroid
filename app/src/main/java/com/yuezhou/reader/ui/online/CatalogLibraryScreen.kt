package com.yuezhou.reader.ui.online

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.model.OnlineBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 排序标识与参考实现 SUPPORTED_ORDERS 对齐；UI 文案保持中文。 */
private data class SortOption(val label: String, val value: String)
private val SORT_OPTIONS = listOf(
    SortOption("最佳匹配", "bestmatch"),
    SortOption("最热门", "popular"),
    SortOption("最近加入", "date"),
    SortOption("年份", "year"),
)

/** 语言值按参考实现的 API language key 传递，而非 ISO 639-1 代码。 */
private data class LanguageOption(val label: String, val value: String?)
private val LANGUAGE_OPTIONS = listOf(
    LanguageOption("全部语言", null),
    LanguageOption("中文", "chinese"),
    LanguageOption("英文", "english"),
    LanguageOption("日文", "japanese"),
    LanguageOption("俄文", "russian"),
    LanguageOption("法文", "french"),
    LanguageOption("德文", "german"),
    LanguageOption("西班牙文", "spanish"),
)

/**
 * Z-Library 检索页（阶段 D）：搜索、语言/格式/排序、分页、列表/网格、详情入口与封面。
 * 状态全部由 ViewModel 按书源保存，切 Tab 或进入详情再返回时恢复输入、筛选与滚动位置。
 */
@Composable
fun CatalogLibraryScreen(
    vm: OnlineLibraryViewModel,
    state: ProviderUiState,
    onOpenSettings: () -> Unit,
) {
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.scrollIndex)
    LaunchedEffect(gridState.firstVisibleItemIndex) { vm.saveScroll(gridState.firstVisibleItemIndex) }
    LaunchedEffect(state.submittedKeyword) {
        if (state.submittedKeyword.isNotEmpty()) gridState.scrollToItem(0)
    }
    LaunchedEffect(Unit) { vm.loadQuota() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::setKeyword,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("书名 / 作者 / ISBN") },
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { vm.search() }, enabled = !state.loading) { Text("搜索") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LANGUAGE_OPTIONS.forEach { option ->
                FilterChip(
                    selected = state.language == option.value,
                    onClick = { vm.setLanguage(option.value, option.label) },
                    label = { Text(option.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SORT_OPTIONS.forEach { option ->
                FilterChip(
                    selected = state.sort == option.value,
                    onClick = { vm.setSort(option.value) },
                    label = { Text(option.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
            Text(
                "格式：EPUB",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
                modifier = Modifier.padding(end = 8.dp),
            )
            TextButton(onClick = { vm.setGridMode(!state.gridMode) }) {
                Text(if (state.gridMode) "列表" else "网格")
            }
        }

        Text(
            text = state.quotaText ?: "正在读取登录状态…",
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        state.searchError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC0392B),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            state.loading && state.items.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            !state.searched -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("输入书名、作者或 ISBN 开始检索", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "搜索无需登录；下载与完整详情需要登录并可能计入额度。仅检索你有合法授权的资源。",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onOpenSettings) { Text("配置 Z-Library 服务器") }
                }
            }
            state.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("没有找到相关书籍", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "试试更换关键词、语言或排序条件",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
            }
            else -> CatalogResults(vm, state, gridState)
        }
    }
}

@Composable
private fun CatalogResults(
    vm: OnlineLibraryViewModel,
    state: ProviderUiState,
    gridState: LazyGridState,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (state.gridMode) 2 else 1),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.items, key = { it.remoteId }) { book ->
            if (state.gridMode) GridBookCard(book, vm) else ListBookCard(book, vm)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                state.loadingMore -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(modifier = Modifier.height(22.dp)) }
                state.hasMore -> OutlinedButton(
                    onClick = { vm.loadMore() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("加载更多") }
                else -> Text(
                    "已加载全部结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GridBookCard(book: OnlineBook, vm: OnlineLibraryViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openDetail(book) },
    ) {
        RemoteCover(book.coverUrl, book.title, vm, Modifier.fillMaxWidth().aspectRatio(0.75f))
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.authors.joinToString("、").ifBlank { "作者未知" },
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            buildString {
                append(book.format.uppercase())
                book.fileSize?.let { append(" · ${formatSize(it)}") }
                book.language?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ListBookCard(book: OnlineBook, vm: OnlineLibraryViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.openDetail(book) }
            .padding(vertical = 4.dp),
    ) {
        RemoteCover(book.coverUrl, book.title, vm, Modifier.width(72.dp).height(102.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.authors.joinToString("、").ifBlank { "作者未知" },
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
            Text(
                buildString {
                    append(book.format.uppercase())
                    book.publishYear?.let { append(" · $it") }
                    book.fileSize?.let { append(" · ${formatSize(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
        }
    }
}

/** 封面按 URL 在 ViewModel 中缓存；失败时回退为按书名着色的文字占位。 */
@Composable
private fun RemoteCover(
    url: String?,
    title: String,
    vm: OnlineLibraryViewModel,
    modifier: Modifier,
) {
    LaunchedEffect(url) { vm.loadCover(url) }
    val bytes = vm.coverOf(url)
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bytes) {
        value = if (bytes == null) null else withContext(Dispatchers.Default) {
            decodeCover(bytes)?.asImageBitmap()
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, YuezhouColors.Divider),
        color = placeholderColor(title),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap!!,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    title.take(1).ifBlank { "书" },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun decodeCover(bytes: ByteArray): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sample * 2) >= 512) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

private fun placeholderColor(key: String): Color {
    val palette = listOf(
        Color(0xFF5B8FF9), Color(0xFF61C0BF), Color(0xFFF6903D), Color(0xFF9D7BD8),
        Color(0xFFE8684A), Color(0xFF5DADEC), Color(0xFF6DC8EC), Color(0xFF78D3A0),
    )
    return palette[(key.hashCode() and Int.MAX_VALUE) % palette.size]
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
    else -> "${bytes}B"
}
