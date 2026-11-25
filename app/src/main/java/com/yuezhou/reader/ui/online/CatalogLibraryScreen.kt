package com.yuezhou.reader.ui.online

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.web.ZLibraryWebEntry
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogLibraryScreen(
    vm: OnlineLibraryViewModel,
    state: ProviderUiState,
    onOpenSettings: () -> Unit,
) {
    val downloadedIds by vm.downloadManager.activeRemoteIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = state.scrollIndex)
    LaunchedEffect(gridState.firstVisibleItemIndex) { vm.saveScroll(gridState.firstVisibleItemIndex) }
    LaunchedEffect(state.submittedKeyword) {
        if (state.submittedKeyword.isNotEmpty()) gridState.scrollToItem(0)
    }
    var filterSheetOpen by remember { mutableStateOf(false) }

    fun openWebSearch() {
        if (!ZLibraryWebEntry.launch(context, vm.webBaseUrl(), state.keyword)) {
            Toast.makeText(context, "请先配置可用的 Z-Library 服务器", Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactSearchField(
                value = state.keyword,
                onValueChange = vm::setKeyword,
                enabled = !state.loading,
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    vm.search()
                },
                modifier = Modifier.weight(1f),
            )
            // 原生搜索不可用时，网页搜索仍完整可用；登录与筛选由站点自身负责。
            TextButton(
                onClick = ::openWebSearch,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("网页") }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${state.languageLabel} · ${sortLabel(state.sort)} · EPUB",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
                modifier = Modifier
                    .weight(1f)
                    .clickable { filterSheetOpen = true }
                    .padding(vertical = 8.dp),
            )
            TextButton(
                onClick = { vm.setGridMode(!state.gridMode) },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text(if (state.gridMode) "列表" else "网格") }
        }

        state.searchError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC0392B),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        state.notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.Brand,
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
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = ::openWebSearch) { Text("网页搜索") }
                    Spacer(Modifier.height(8.dp))
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = ::openWebSearch) { Text("网页搜索") }
                }
            }
            else -> CatalogResults(vm, state, gridState, downloadedIds)
        }
    }

    if (filterSheetOpen) {
        ZLibraryFilterSheet(
            language = state.language,
            sort = state.sort,
            onDismiss = { filterSheetOpen = false },
            onApply = { language, label, sort ->
                filterSheetOpen = false
                vm.applyFilters(language, label, sort)
            },
        )
    }
}

/** 紧凑搜索框：40dp 高，支持键盘搜索键，避免常规 TextField 的 56dp 最小高度。 */
@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Surface(
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        color = YuezhouColors.Surface,
        border = BorderStroke(1.dp, YuezhouColors.Divider),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 4.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = YuezhouColors.TextPrimary),
                    cursorBrush = SolidColor(YuezhouColors.Brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onSearch()
                        },
                    ),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    "书名 / 作者 / ISBN",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = YuezhouColors.TextSecondary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(32.dp),
                ) { Icon(Icons.Filled.Close, contentDescription = "清空", modifier = Modifier.size(16.dp)) }
            }
            IconButton(
                onClick = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    onSearch()
                },
                enabled = enabled,
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.Filled.Search, contentDescription = "搜索", modifier = Modifier.size(20.dp)) }
        }
    }
}

/** 语言、排序统一在弹窗内编辑，点「应用」后只提交一次。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ZLibraryFilterSheet(
    language: String?,
    sort: String,
    onDismiss: () -> Unit,
    onApply: (String?, String, String) -> Unit,
) {
    var draftLanguage by remember(language) { mutableStateOf(language) }
    var draftSort by remember(sort) { mutableStateOf(sort) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("筛选条件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Text("语言", style = MaterialTheme.typography.labelMedium, color = YuezhouColors.TextSecondary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                LANGUAGE_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = draftLanguage == option.value,
                        onClick = { draftLanguage = option.value },
                        label = { Text(option.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Text(
                "排序",
                style = MaterialTheme.typography.labelMedium,
                color = YuezhouColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 6.dp),
            ) {
                SORT_OPTIONS.forEach { option ->
                    FilterChip(
                        selected = draftSort == option.value,
                        onClick = { draftSort = option.value },
                        label = { Text(option.label) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Text(
                "格式：EPUB（固定）",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        draftLanguage = null
                        draftSort = "bestmatch"
                    },
                ) { Text("重置") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        onApply(
                            draftLanguage,
                            LANGUAGE_OPTIONS.firstOrNull { it.value == draftLanguage }?.label ?: "全部语言",
                            draftSort,
                        )
                    },
                ) { Text("应用") }
            }
        }
    }
}

private fun sortLabel(value: String): String =
    SORT_OPTIONS.firstOrNull { it.value == value }?.label ?: SORT_OPTIONS.first().label

@Composable
private fun CatalogResults(
    vm: OnlineLibraryViewModel,
    state: ProviderUiState,
    gridState: LazyGridState,
    downloadedIds: Set<String>,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (state.gridMode) 3 else 1),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(state.items, key = { it.remoteId }) { book ->
            if (state.gridMode) GridBookCard(book, vm, book.remoteId in downloadedIds)
            else ListBookCard(book, vm, book.remoteId in downloadedIds)
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
private fun GridBookCard(book: OnlineBook, vm: OnlineLibraryViewModel, downloaded: Boolean) {
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
                append(book.formatLabel)
                book.fileSize?.let { append(" · ${formatSize(it)}") }
                book.language?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (downloaded) {
            Text(
                "已下载",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.Brand,
            )
        }
    }
}

@Composable
private fun ListBookCard(book: OnlineBook, vm: OnlineLibraryViewModel, downloaded: Boolean) {
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
                    append(book.formatLabel)
                    book.publishYear?.let { append(" · $it") }
                    book.fileSize?.let { append(" · ${formatSize(it)}") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
            if (downloaded) {
                Text("已下载", style = MaterialTheme.typography.bodySmall, color = YuezhouColors.Brand)
            }
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
