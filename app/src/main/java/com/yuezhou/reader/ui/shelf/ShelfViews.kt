package com.yuezhou.reader.ui.shelf

import com.yuezhou.reader.ui.components.BookCoverPlaceholder

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import com.yuezhou.reader.reader.online.mapping.OnlineBookMappingStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.LocalScanner
import com.yuezhou.reader.reader.TempFileCleanup
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuezhou.reader.ui.LibraryUiPrefs
import com.yuezhou.reader.ui.components.BookReadingProgress
import com.yuezhou.reader.ui.components.LibraryPageHeading
import com.yuezhou.reader.ui.components.LibraryGridIcon
import com.yuezhou.reader.ui.components.coverPlaceholderColor
import com.yuezhou.reader.ui.LibrarySort
import com.yuezhou.reader.ui.LocalLibraryFilters
import com.yuezhou.reader.ui.ReadingFilter
import com.yuezhou.reader.ui.localLibraryResults
import com.yuezhou.reader.ui.settings.SettingsPage
import com.yuezhou.reader.ui.settings.SettingsRoute
import com.yuezhou.reader.ui.navigation.ShelfNavigator
import com.yuezhou.reader.ui.navigation.ShelfPage
import com.yuezhou.reader.ui.navigation.SwipeBlockRegistry
import com.yuezhou.reader.ui.navigation.Tab
import com.yuezhou.reader.ui.navigation.shelfSwipe
import com.yuezhou.reader.ui.rememberRestoredGridState
import com.yuezhou.reader.ui.rememberRestoredListState
import com.yuezhou.reader.ui.online.DownloadManagerScreen
import com.yuezhou.reader.ui.online.OnlineLibraryScreen
import com.yuezhou.reader.ui.online.OnlineLibraryViewModel
import com.yuezhou.reader.ui.online.CloudSourceTabs
import com.yuezhou.reader.web.ZLibraryWebEntry
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.YuezhouDimens
import com.yuezhou.reader.LocalAppMotionEnabled
import com.yuezhou.reader.ReaderActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
@Composable
internal fun ScanResultDialog(
    scan: LocalScanner.ScanResult,
    onImportLocation: (LocalScanner.Location) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫描结果") },
        text = {
            Column {
                when {
                    scan.books.isEmpty() -> {
                        Text("未发现 EPUB 文件", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Text(
                            "发现 ${scan.books.size} 本，同名冲突 ${scan.conflicts.size} 处",
                            style = MaterialTheme.typography.bodySmall,
                            color = YuezhouColors.TextSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            lazyItems(scan.books) { book ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text(
                                        book.bookId,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (book.locations.size > 1) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    // 同名冲突：列出全部位置，每个位置单独可导入，不自动选一个
                                    book.locations.forEach { loc ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "${loc.displayName}（${formatSize(loc.size)}）",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (book.locations.size > 1)
                                                    MaterialTheme.colorScheme.error
                                                else YuezhouColors.TextSecondary,
                                                modifier = Modifier.weight(1f),
                                            )
                                            TextButton(onClick = { onImportLocation(loc) }) {
                                                Text(if (book.alreadyInLibrary) "覆盖" else "导入")
                                            }
                                        }
                                    }
                                    if (book.locations.size > 1) {
                                        Text(
                                            "同名文件有多个位置，请选择要导入的一项",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.padding(start = 12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 自绘下载图标（material-icons-core 无 Download，用描边路径绘制向下箭头+托盘）。 */
internal val DownloadIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
        ) {
            moveTo(12f, 4f)
            lineTo(12f, 14f)
            moveTo(8f, 10f)
            lineTo(12f, 14f)
            lineTo(16f, 10f)
            moveTo(4f, 19f)
            lineTo(20f, 19f)
        }
    }.build()
}

/**
 * 右上角下载图标，带下载中动效：
 * - 下载中：箭头上下浮动（模拟「往下搬运」），外层叠加一圈旋转的进度弧线；
 * - 完成/失败：静态图标。
 */
@Composable
internal fun AnimatedDownloadIcon(downloading: Boolean) {
    val floatOffset = if (downloading && LocalAppMotionEnabled.current) {
        val transition = rememberInfiniteTransition(label = "download")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "float",
        )
    } else {
        null
    }
    Box(contentAlignment = Alignment.Center) {
        // 下载中的旋转进度弧线
        if (downloading) {
            CircularProgressIndicator(
                progress = { 0.3f },
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = YuezhouColors.Brand,
                trackColor = YuezhouColors.Brand.copy(alpha = 0.15f),
            )
        }
        // 箭头上下浮动
        Icon(
            DownloadIcon,
            contentDescription = "下载任务",
            tint = YuezhouColors.Brand,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { translationY = floatOffset?.value ?: 0f },
        )
    }
}

/** 云端书库内容：后端名 + 网格 + 空/错误态。 */
@Composable
internal fun CloudBookshelfContent(
    books: List<CloudSync.RemoteBook>,
    loading: Boolean,
    showRefreshBar: Boolean,
    error: String?,
    backendLabel: String?,
    fetchedAt: Long,
    fromCache: Boolean,
    scrollKey: String,
    setupLabel: String?,
    onSetup: () -> Unit,
    onRefresh: () -> Unit,
    onOpenDetail: (CloudSync.RemoteBook) -> Unit,
    coversEnabled: Boolean,
    loadCover: suspend (CloudSync.RemoteBook) -> ByteArray?,
) {
    val cloudContext = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(cloudContext) { LibraryUiPrefs(cloudContext) }
    when {
        // 仅首次加载（尚无任何数据）时显示转圈；已有列表时静默刷新，避免切 tab 闪烁
        loading && books.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null && books.isEmpty() -> {
            EmptyState(
                icon = Icons.Filled.List,
                title = if (setupLabel == null) "云端书库加载失败" else "书源需要设置",
                subtitle = error,
                actionLabel = setupLabel ?: "重试",
                onAction = if (setupLabel == null) onRefresh else onSetup,
            )
        }
        books.isEmpty() -> {
            EmptyState(
                icon = Icons.Filled.List,
                title = "云端书库为空",
                subtitle = "在设置中点「立即同步」选择要上传的书籍",
            )
        }
        else -> {
            Column(Modifier.fillMaxSize()) {
                // 刷新中：顶部轻量进度条，明确「更新书库」正在进行（仅主动刷新时显示）
                if (showRefreshBar) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = YuezhouColors.Brand,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "更新书库中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = YuezhouColors.Brand,
                        )
                    }
                }
                backendLabel?.let {
                    Text(
                        buildString {
                            append("后端：$it · ${books.size} 本")
                            if (fetchedAt > 0) {
                                append(" · ")
                                append(if (fromCache) "缓存" else "已更新")
                                append(" ")
                                append(formatFetchedAt(fetchedAt))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                LazyVerticalGrid(
                    state = rememberRestoredGridState(scrollKey, prefs),
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(books, key = { it.bookId }) { book ->
                        CloudBookCard(
                            book = book,
                            onClick = { onOpenDetail(book) },
                            coversEnabled = coversEnabled,
                            coverRefreshKey = fetchedAt,
                            loadCover = loadCover,
                        )
                    }
                }
            }
        }
    }
}

/** 把 epoch millis 格式化为「HH:mm」或「昨天 HH:mm」等简短时间。 */
internal fun formatFetchedAt(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** 云端书籍卡片：书名占位封面 + 本机已存在角标。 */
@Composable
internal fun CloudBookCard(
    book: CloudSync.RemoteBook,
    onClick: () -> Unit,
    coversEnabled: Boolean,
    coverRefreshKey: Long,
    loadCover: suspend (CloudSync.RemoteBook) -> ByteArray?,
) {
    val cover = androidx.compose.runtime.key(book.cloudCover, book.coverPng) {
        val bytes by produceState(book.coverPng, coversEnabled, coverRefreshKey) {
            if (coversEnabled && value == null) value = loadCover(book)
        }
        bytes
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(coverPlaceholderColor(book.bookId)),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) {
                CoverImage(
                    bytes = cover,
                    contentDescription = book.bookId,
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                BookCoverPlaceholder(book.bookId, book.bookId)
            }

            // 本机已存在角标
            if (book.local) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "✓",
                        color = YuezhouColors.Brand,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Text(
            text = book.bookId,
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 格式化文件大小为可读字符串。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudBookDetailsSheet(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismissRequest, containerColor = YuezhouColors.Surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.material3.ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
            text()
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dismissButton()
                confirmButton()
            }
        }
    }
}

internal fun formatSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

@Composable
internal fun LocalBookDetailDialog(
    book: LibraryStore.Entry,
    store: LibraryStore,
    onDismiss: () -> Unit,
    onEnterSelect: () -> Unit,
) {
    val authors by produceState(emptyList<String>(), book, store) {
        value = store.authorsFor(book)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("书籍详情") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(book.title, style = MaterialTheme.typography.titleMedium) }
                if (authors.isNotEmpty()) {
                    item { Text("作者：${authors.joinToString("、")}") }
                }
                item { Text("文件大小：${formatSize(book.size)}") }
                item {
                    val progress = book.percent?.coerceIn(0.0, 1.0)?.let { "${(it * 100).toInt()}%" }
                    Text("阅读进度：${progress ?: "未开始"}")
                }
                item {
                    Column {
                        Text("文件位置")
                        SelectionContainer {
                            Text(book.localPath, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onEnterSelect) { Text("管理书籍") } },
    )
}

@Composable
internal fun BookshelfContent(
    books: List<LibraryStore.Entry>,
    loading: Boolean,
    query: String,
    readingFilter: ReadingFilter,
    librarySort: LibrarySort,
    authorSearchLoading: Boolean,
    authors: Map<String, List<String>>,
    lookupAuthor: suspend (LibraryStore.Entry) -> String?,
    authorRefreshKey: Int,
    gridMode: Boolean,
    selecting: Set<String>?,
    navigationLocked: Boolean,
    onImport: () -> Unit,
    onEnterSelect: (LibraryStore.Entry) -> Unit,
    onToggleSelect: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val localPrefs = remember(context) { LibraryUiPrefs(context) }
    val coverStorage = remember(context) { LibraryStore(context.applicationContext) }
    val filtering = query.isNotEmpty() || readingFilter != ReadingFilter.ALL
    val temporaryResults = filtering || librarySort != LibrarySort.RECENT
    val gridState = if (temporaryResults) rememberLazyGridState() else rememberRestoredGridState("local", localPrefs)
    val listState = if (temporaryResults) rememberLazyListState() else rememberRestoredListState("local.list", localPrefs)
    LaunchedEffect(query, readingFilter, librarySort) {
        if (temporaryResults) {
            gridState.scrollToItem(0)
            listState.scrollToItem(0)
        }
    }

    fun openBook(book: LibraryStore.Entry) {
        if (selecting != null) {
            onToggleSelect(book.bookId)
        } else if (!navigationLocked) {
            val intent = Intent(context, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.bookId)
            }
            context.startActivity(intent)
        }
    }

    when {
        loading || (books.isEmpty() && authorSearchLoading) -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        books.isEmpty() && filtering -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有匹配的书籍", color = YuezhouColors.TextSecondary)
            }
        }
        books.isEmpty() -> {
            EmptyLibrary(onImport = onImport)
        }
        !gridMode -> {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                lazyItems(books, key = { it.bookId }) { book ->
                    val indexed = authors[book.bookId]?.joinToString("、")
                    val author by produceState(indexed, book, indexed, authorRefreshKey) {
                        value = indexed ?: lookupAuthor(book)
                    }
                    BookListRow(
                        book = book,
                        coverStorage = coverStorage,
                        author = author,
                        selecting = selecting != null,
                        checked = selecting?.contains(book.bookId) == true,
                        onClick = { openBook(book) },
                        onLongClick = if (selecting == null && !navigationLocked) {
                            { onEnterSelect(book) }
                        } else null,
                    )
                }
            }
        }
        else -> {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(
                    minSize = 96.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f),
                ),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books, key = { it.bookId }) { book ->
                    BookCard(
                        book = book,
                        coverStorage = coverStorage,
                        selecting = selecting != null,
                        checked = selecting?.contains(book.bookId) == true,
                        onClick = { openBook(book) },
                        onLongClick = if (selecting == null && !navigationLocked) {
                            { onEnterSelect(book) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BookCard(
    book: LibraryStore.Entry,
    coverStorage: LibraryStore,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(YuezhouDimens.CoverRadius))
                .background(coverPlaceholderColor(book.bookId))
                .then(
                    if (selecting && checked) {
                        Modifier.border(2.dp, YuezhouColors.Brand, RoundedCornerShape(YuezhouDimens.CoverRadius))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val cover = rememberShelfCover(book, storage = coverStorage, targetPx = COVER_GRID_SIZE)
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                BookCoverPlaceholder(book.title, book.bookId)
            }

            // 管理态：右上角勾选圆点
            if (selecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (checked) YuezhouColors.Brand else MaterialTheme.colorScheme.surface)
                        .border(
                            1.dp,
                            if (checked) Color.Transparent else YuezhouColors.Divider,
                            androidx.compose.foundation.shape.CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        Text(
                            "✓",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = YuezhouColors.TextPrimary,
            minLines = 2,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = YuezhouDimens.Gap),
        )

        book.percent?.let { progress ->
            BookReadingProgress(
                progress,
                Modifier.fillMaxWidth().padding(top = YuezhouDimens.GapSmall),
            )
        }
    }
}

/** 本地书库列表模式：封面缩略 + 书名 + 大小/进度，与网格模式共用点击与多选行为。 */
@Composable
internal fun BookListRow(
    book: LibraryStore.Entry,
    coverStorage: LibraryStore,
    author: String?,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 78.dp)
                .clip(RoundedCornerShape(YuezhouDimens.CoverRadius))
                .background(coverPlaceholderColor(book.bookId))
                .then(
                    if (selecting && checked) {
                        Modifier.border(2.dp, YuezhouColors.Brand, RoundedCornerShape(YuezhouDimens.CoverRadius))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            val cover = rememberShelfCover(book, storage = coverStorage, targetPx = COVER_LIST_SIZE)
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = book.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                BookCoverPlaceholder(book.title.take(1), book.bookId)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = YuezhouColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(YuezhouDimens.Gap)) {
                Text(
                    author.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatSize(book.size), style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            }
            book.percent?.let { progress ->
                BookReadingProgress(
                    progress,
                    Modifier.fillMaxWidth().padding(top = YuezhouDimens.GapSmall),
                )
            }
        }

        if (selecting) {
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (checked) YuezhouColors.Brand else MaterialTheme.colorScheme.surface)
                    .border(
                        1.dp,
                        if (checked) Color.Transparent else YuezhouColors.Divider,
                        androidx.compose.foundation.shape.CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Text(
                        "✓",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private const val COVER_GRID_SIZE = 512
private const val COVER_LIST_SIZE = 192

internal data class ShelfCoverKey(
    val path: String,
    val version: String?,
    val modifiedAt: Long,
    val fileSize: Long,
    val targetPx: Int,
)

internal val shelfCoverCache = object : android.util.LruCache<ShelfCoverKey, android.graphics.Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: ShelfCoverKey, value: android.graphics.Bitmap): Int =
        value.allocationByteCount
}
internal val shelfCoverDispatcher = Dispatchers.IO.limitedParallelism(2)

@Composable
internal fun rememberShelfCover(
    book: LibraryStore.Entry,
    storage: LibraryStore,
    targetPx: Int,
): androidx.compose.ui.graphics.ImageBitmap? {
    val file = book.coverFile ?: return null
    return androidx.compose.runtime.key(file, book.coverVersion, targetPx) {
        val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null) {
            value = withContext(shelfCoverDispatcher) {
                val key = ShelfCoverKey(file.path, book.coverVersion, file.lastModified(), file.length(), targetPx)
                val bitmap = shelfCoverCache.get(key) ?: storage.readCover(file)
                    ?.let { decodeCoverDownsampled(it, targetPx) }
                    ?.also { shelfCoverCache.put(key, it) }
                bitmap?.asImageBitmap()
            }
        }
        image
    }
}

/** 封面图片：后台解码 + 限尺寸降采样，避免大图在主线程解码导致掉帧。 */
@Composable
internal fun CoverImage(
    bytes: ByteArray,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        bytes,
    ) {
        value = withContext(Dispatchers.Default) {
            decodeCoverDownsampled(bytes)?.asImageBitmap()
        }
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

/**
 * 封面解码：先仅读边界得到原尺寸，再按目标边长（默认 512px）计算 inSampleSize 降采样，
 * 限制解码后的内存占用，避免超大封面位图占用过多内存。
 */
internal fun decodeCoverDownsampled(bytes: ByteArray, targetPx: Int = 512): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sample * 2) >= targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

@Composable
internal fun EmptyLibrary(onImport: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.List,
        title = "书库还是空的",
        subtitle = "点击右下角 + 导入 EPUB 书籍",
        actionLabel = "导入书籍",
        onAction = onImport,
    )
}

/** 统一空态：圆形图标底 + 主文案 + 副文案 + 可选主按钮。全站复用，保证观感一致。 */
@Composable
internal fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(YuezhouColors.BrandContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = YuezhouColors.BrandDark,
                    modifier = Modifier.size(34.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = YuezhouColors.TextPrimary,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YuezhouColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(YuezhouColors.BrandContainer)
                        .clickable(onClick = onAction)
                        .padding(horizontal = 28.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = YuezhouColors.BrandDark,
                    )
                }
            }
        }
    }
}

@Composable
internal fun BottomNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(
        containerColor = YuezhouColors.Surface,
        tonalElevation = 0.dp,
        // 只预留底部系统栏；safeDrawing 会把状态栏高度也算进来，导致导航栏明显变高。
        windowInsets = WindowInsets.navigationBars,
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = YuezhouColors.BrandDark,
                    selectedTextColor = YuezhouColors.BrandDark,
                    unselectedIconColor = YuezhouColors.TextSecondary,
                    unselectedTextColor = YuezhouColors.TextSecondary,
                    indicatorColor = YuezhouColors.BrandContainer,
                ),
            )
        }
    }
}
