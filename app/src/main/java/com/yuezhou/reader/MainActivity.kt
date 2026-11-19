package com.yuezhou.reader

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import kotlinx.coroutines.launch

/**
 * 书架主界面：本地书库列表（封面卡片网格）+ 导入入口 + 底部导航占位。
 * 点击书籍进入 [ReaderActivity] 阅读。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            YuezhouTheme {
                BookshelfScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var books by remember { mutableStateOf<List<LibraryStore.Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // 刷新触发计数：+1 触发重新加载书库
    var refreshKey by remember { mutableStateOf(0) }
    // 当前选中的底部导航 tab
    var currentTab by remember { mutableStateOf(Tab.书架) }
    // 管理态（多选删除）：非空表示进入管理态，集合为已勾选的 bookId
    var selecting by remember { mutableStateOf<Set<String>?>(null) }

    val store = remember { LibraryStore(context) }

    val pickEpub = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importing = true
                status = null
                val result = EpubImporter.import(context, uri)
                result.fold(
                    onSuccess = { status = "已导入：${it.nameWithoutExtension}" },
                    onFailure = { status = "导入失败：${it.message}" },
                )
                importing = false
                refreshKey++
            }
        }
    }

    // 初次加载 + 每次 refreshKey 变化时重新加载书库
    LaunchedEffect(refreshKey) {
        loading = true
        books = store.snapshot().books
        loading = false
    }

    // 管理态下返回键退出管理态（而非退出 App）
    BackHandler(enabled = selecting != null) {
        selecting = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = YuezhouColors.Background,
        topBar = {
            if (selecting != null && currentTab == Tab.书架) {
                // 管理态顶栏：已选数量 + 全选/删除
                val selected = selecting!!
                TopAppBar(
                    title = { Text("已选 ${selected.size} 项") },
                    navigationIcon = {
                        Text(
                            "取消",
                            style = MaterialTheme.typography.bodyMedium,
                            color = YuezhouColors.TextPrimary,
                            modifier = Modifier
                                .clickable { selecting = null }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    },
                    actions = {
                        val allSelected = books.isNotEmpty() && selected.size == books.size
                        Text(
                            if (allSelected) "取消全选" else "全选",
                            style = MaterialTheme.typography.bodyMedium,
                            color = YuezhouColors.Brand,
                            modifier = Modifier
                                .clickable {
                                    selecting = if (allSelected) emptySet()
                                    else books.map { it.bookId }.toSet()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Text(
                            "删除",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected.isEmpty()) YuezhouColors.TextSecondary
                            else Color(0xFFE64340),
                            modifier = Modifier
                                .clickable(enabled = selected.isNotEmpty()) {
                                    scope.launch {
                                        val removed = store.removeBooks(selected)
                                        selecting = null
                                        status = "已移除 ${removed.size} 本"
                                        refreshKey++
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = YuezhouColors.Surface,
                        titleContentColor = YuezhouColors.TextPrimary,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text(if (currentTab == Tab.书架) "书库" else currentTab.label) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = YuezhouColors.Surface,
                        titleContentColor = YuezhouColors.TextPrimary,
                    ),
                )
            }
        },
        floatingActionButton = {
            if (currentTab == Tab.书架) {
                FloatingActionButton(
                    onClick = { pickEpub.launch(arrayOf("application/epub+zip")) },
                    containerColor = YuezhouColors.Brand,
                    contentColor = Color.White,
                ) {
                    Text("+", style = MaterialTheme.typography.headlineMedium)
                }
            }
        },
        bottomBar = {
            BottomNavBar(current = currentTab, onSelect = { currentTab = it })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (importing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("导入中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = YuezhouColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when (currentTab) {
                Tab.书架 -> BookshelfContent(
                    books = books,
                    loading = loading,
                    selecting = selecting,
                    onPickEpub = { pickEpub.launch(arrayOf("application/epub+zip")) },
                    onEnterSelect = { selecting = setOf(it) },
                    onToggleSelect = { id ->
                        val cur = selecting ?: emptySet()
                        selecting = if (id in cur) cur - id else cur + id
                    },
                )
                Tab.最近阅读 -> RecentReading(books)
                Tab.设置 -> SettingsPage()
            }
        }
    }
}

/** 底部导航的三个 tab。 */
private enum class Tab(val label: String) {
    书架("书库"),
    最近阅读("最近阅读"),
    设置("设置"),
}

@Composable
private fun BookshelfContent(
    books: List<LibraryStore.Entry>,
    loading: Boolean,
    selecting: Set<String>?,
    onPickEpub: () -> Unit,
    onEnterSelect: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    when {
        loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        books.isEmpty() -> {
            EmptyLibrary(onImport = { onPickEpub() })
        }
        else -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books, key = { it.bookId }) { book ->
                    BookCard(
                        book = book,
                        selecting = selecting != null,
                        checked = selecting?.contains(book.bookId) == true,
                        onClick = {
                            if (selecting != null) onToggleSelect(book.bookId)
                            else {
                                val intent = Intent(context, ReaderActivity::class.java).apply {
                                    putExtra(ReaderActivity.EXTRA_BOOK_ID, book.bookId)
                                }
                                context.startActivity(intent)
                            }
                        },
                        onLongClick = { onEnterSelect(book.bookId) },
                    )
                }
            }
        }
    }
}

/** 最近阅读：按最近阅读时间倒序，仅展示有阅读记录的书。 */
@Composable
private fun RecentReading(books: List<LibraryStore.Entry>) {
    val recent = remember(books) {
        books.filter { it.lastReadAt != null }
            .sortedByDescending { it.lastReadAt }
    }
    if (recent.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "还没有阅读记录",
                style = MaterialTheme.typography.bodyMedium,
                color = YuezhouColors.TextSecondary,
            )
        }
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        lazyItems(recent, key = { it.bookId }) { book ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        context.startActivity(
                            Intent(context, ReaderActivity::class.java).apply {
                                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.bookId)
                            },
                        )
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 小封面
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 58.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(YuezhouColors.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    val cover = book.coverPng
                    if (cover != null) {
                        val bmp = remember(cover) {
                            BitmapFactory.decodeByteArray(cover, 0, cover.size)?.asImageBitmap()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Text(
                            book.title.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = YuezhouColors.Brand,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    book.percent?.let { p ->
                        Text(
                            "已读 ${(p * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = YuezhouColors.TextSecondary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 设置页：阅读默认值占位（当前展示主题/翻页等已持久化的说明）。 */
@Composable
private fun SettingsPage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "字号、主题、翻页模式等阅读选项，可在打开书籍后点击屏幕呼出面板进行调节，并会自动保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = YuezhouColors.TextSecondary,
        )
    }
}

@Composable
private fun BookCard(
    book: LibraryStore.Entry,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(8.dp))
                .background(CoverPlaceholder.color(book.bookId)),
            contentAlignment = Alignment.Center,
        ) {
            val cover = book.coverPng
            if (cover != null) {
                val bmp = remember(cover) {
                    BitmapFactory.decodeByteArray(cover, 0, cover.size)?.asImageBitmap()
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = book.title,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                // 无封面：书名文字占位（渐变色底 + 书名）
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }

            // 管理态：右上角勾选圆点
            if (selecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (checked) YuezhouColors.Brand else Color.White.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (checked) {
                        Text(
                            "✓",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        book.percent?.let { p ->
            LinearProgressIndicator(
                progress = { p.toFloat() },
                color = YuezhouColors.Brand,
                trackColor = YuezhouColors.Divider,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
            )
        }
    }
}

/** 封面占位：根据 bookId 生成稳定的渐变底色。 */
private object CoverPlaceholder {
    private val palette = listOf(
        Color(0xFF5B8FF9),
        Color(0xFF61C0BF),
        Color(0xFFF6903D),
        Color(0xFF9D7BD8),
        Color(0xFFE8684A),
        Color(0xFF5DADEC),
        Color(0xFF6DC8EC),
        Color(0xFF78D3A0),
    )
    fun color(key: String): Color {
        val idx = (key.hashCode() and Int.MAX_VALUE) % palette.size
        return palette[idx]
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "书库还是空的",
                style = MaterialTheme.typography.titleMedium,
                color = YuezhouColors.TextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "点击右下角 + 导入 EPUB 书籍",
                style = MaterialTheme.typography.bodyMedium,
                color = YuezhouColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun BottomNavBar(current: Tab, onSelect: (Tab) -> Unit) {
    Surface(
        color = YuezhouColors.Surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavItem("书库", selected = current == Tab.书架) { onSelect(Tab.书架) }
            NavItem("最近阅读", selected = current == Tab.最近阅读) { onSelect(Tab.最近阅读) }
            NavItem("设置", selected = current == Tab.设置) { onSelect(Tab.设置) }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) YuezhouColors.Brand else YuezhouColors.TextSecondary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
