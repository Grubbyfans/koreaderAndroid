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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.LocalScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    // 书库范围：本地 / 云端
    var bookScope by remember { mutableStateOf(BookScope.本地) }
    // 管理态（多选删除）：非空表示进入管理态，集合为已勾选的 bookId
    var selecting by remember { mutableStateOf<Set<String>?>(null) }
    // 待确认删除的书（弹二次确认框用）；非空时展示确认弹窗
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    // 软删除集合：确认删除后先隐藏，Snackbar 撤销前不真正落盘删除
    var hiddenBooks by remember { mutableStateOf<Set<String>>(emptySet()) }
    val snackbarHostState = remember { SnackbarHostState() }

    val store = remember { LibraryStore(context) }
    val cloudSync = remember { CloudSync(context) }

    // —— 云端书库状态 ——
    var remoteBooks by remember { mutableStateOf<List<CloudSync.RemoteBook>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteBackendLabel by remember { mutableStateOf<String?>(null) }
    // 列表缓存状态（方案 §12.1：显示最后成功获取时间与是否命中缓存）
    var remoteFetchedAt by remember { mutableStateOf(0L) }
    var remoteFromCache by remember { mutableStateOf(false) }
    // 待下载/详情选中的书
    var detailBook by remember { mutableStateOf<CloudSync.RemoteBook?>(null) }
    var downloading by remember { mutableStateOf(false) }

    // —— 本地目录扫描（方案 §8.3：点击「检查本机书籍」才扫描）——
    var scanResult by remember { mutableStateOf<LocalScanner.ScanResult?>(null) }
    var scanDialogOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val pickDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                scanning = true
                status = null
                try {
                    // 持久授权：允许后续扫描同一目录
                    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, flags)
                    } catch (e: Exception) {
                        // 持久授权失败不阻断单次扫描
                    }
                    val existingIds = store.snapshot().books.map { it.bookId }.toSet()
                    val result = LocalScanner.scan(context, uri, existingIds)
                    scanResult = result
                    scanUri = uri
                    scanDialogOpen = true
                } catch (e: Exception) {
                    status = "扫描失败：${e.message}"
                } finally {
                    scanning = false
                }
            }
        }
    }

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

    // 云端书库：进入云端 tab 时加载（带缓存，短时间复用；显式刷新走 force）
    fun loadRemote(force: Boolean = false) {
        scope.launch {
            remoteLoading = true
            remoteError = null
            try {
                val lib = cloudSync.listBooks(force)
                remoteBooks = lib.books
                remoteBackendLabel = lib.backendLabel
                remoteFetchedAt = lib.fetchedAt
                remoteFromCache = lib.fromCache
            } catch (e: Exception) {
                remoteError = e.message ?: "云端书库加载失败"
                remoteBooks = emptyList()
            } finally {
                remoteLoading = false
            }
        }
    }

    // 主动更新书库（方案 §10）：强制刷新列表 + 枚举 books/ 登记外部上传 EPUB
    fun refreshRemoteLibrary() {
        scope.launch {
            remoteLoading = true
            remoteError = null
            try {
                val msg = cloudSync.refreshLibrary()
                // 主动更新后再拉最新列表展示
                val lib = cloudSync.listBooks(force = true)
                remoteBooks = lib.books
                remoteBackendLabel = lib.backendLabel
                remoteFetchedAt = lib.fetchedAt
                remoteFromCache = false
                status = msg
            } catch (e: Exception) {
                remoteError = e.message ?: "更新书库失败"
            } finally {
                remoteLoading = false
            }
        }
    }

    // 切换到云端 tab 时自动加载（命中缓存时几乎瞬时返回，不重复请求网络）
    LaunchedEffect(currentTab, bookScope) {
        if (currentTab == Tab.书架 && bookScope == BookScope.云端) {
            loadRemote()
        }
    }

    // 管理态下返回键退出管理态（而非退出 App）
    BackHandler(enabled = selecting != null) {
        selecting = null
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = YuezhouColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                    // 二次确认：先弹框，确认后才真正删除
                                    pendingDelete = selected
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
                if (bookScope == BookScope.本地) {
                    FloatingActionButton(
                        onClick = { pickEpub.launch(arrayOf("application/epub+zip")) },
                        containerColor = YuezhouColors.Brand,
                        contentColor = Color.White,
                    ) {
                        Text("+", style = MaterialTheme.typography.headlineMedium)
                    }
                } else {
                    // 云端：主动刷新按钮（主动更新：强制拉列表 + 发现外部上传）
                    FloatingActionButton(
                        onClick = { refreshRemoteLibrary() },
                        containerColor = YuezhouColors.Brand,
                        contentColor = Color.White,
                    ) {
                        Text("↻", style = MaterialTheme.typography.headlineMedium)
                    }
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

            val visibleBooks = books.filter { it.bookId !in hiddenBooks }
            when (currentTab) {
                Tab.书架 -> {
                    // 本地/云端切换条
                    BookScopeSwitch(current = bookScope, onSelect = { bookScope = it })
                    when (bookScope) {
                        BookScope.本地 -> {
                            // 检查本机书籍入口（方案 §8.3：正常进入不扫描，点击才扫描）
                            if (!scanning) {
                                TextButton(
                                    onClick = { pickDirectory.launch(null) },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                ) {
                                    Text("检查本机书籍", color = YuezhouColors.Brand)
                                }
                            } else {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("扫描本机书籍中…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            BookshelfContent(
                                books = visibleBooks,
                                loading = loading,
                                selecting = selecting,
                                onPickEpub = { pickEpub.launch(arrayOf("application/epub+zip")) },
                                onEnterSelect = { selecting = setOf(it) },
                                onToggleSelect = { id ->
                                    val cur = selecting ?: emptySet()
                                    selecting = if (id in cur) cur - id else cur + id
                                },
                            )
                        }
                        BookScope.云端 -> CloudBookshelfContent(
                            books = remoteBooks,
                            loading = remoteLoading,
                            error = remoteError,
                            backendLabel = remoteBackendLabel,
                            fetchedAt = remoteFetchedAt,
                            fromCache = remoteFromCache,
                            onRefresh = { refreshRemoteLibrary() },
                            onOpenDetail = { detailBook = it },
                        )
                    }
                }
                Tab.最近阅读 -> RecentReading(visibleBooks)
                Tab.设置 -> SettingsPage()
            }
        }
    }

    // 删除二次确认弹窗：列出将删除的书名，明确范围，确认后才执行
    val toDelete = pendingDelete
    if (toDelete != null && toDelete.isNotEmpty()) {
        val titles = books.filter { it.bookId in toDelete }.map { it.title }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除书籍") },
            text = {
                Text(
                    buildString {
                        append("将删除以下 ")
                        append(toDelete.size)
                        append(" 本本地书籍，正文与阅读进度一并移除，此操作不可撤销：\n\n")
                        titles.take(5).forEach { append("· ").append(it).append('\n') }
                        if (titles.size > 5) append("… 等共 ").append(titles.size).append(" 本")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // 先软隐藏，给撤销窗口；Snackbar 超时（未撤销）才真正删除
                            hiddenBooks = hiddenBooks + toDelete
                            pendingDelete = null
                            selecting = null
                            refreshKey++
                            val result = snackbarHostState.showSnackbar(
                                message = "已移除 ${toDelete.size} 本",
                                actionLabel = "撤销",
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                // 撤销：恢复显示，不删除
                                hiddenBooks = hiddenBooks - toDelete
                                refreshKey++
                            } else {
                                // 超时/消失：真正删除，并从软隐藏集合移除（避免残留 bookId 干扰后续同名书导入）
                                store.removeBooks(toDelete)
                                hiddenBooks = hiddenBooks - toDelete
                                refreshKey++
                            }
                        }
                    },
                ) {
                    Text("删除", color = Color(0xFFE64340))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 云端书籍详情弹窗：名称/大小/后端 + 下载（或已本地存在时打开）
    val detail = detailBook
    if (detail != null) {
        AlertDialog(
            onDismissRequest = { detailBook = null },
            title = { Text(detail.bookId, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text("文件名：${detail.fileName}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "大小：${formatSize(detail.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = YuezhouColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "后端：${remoteBackendLabel ?: "云端"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = YuezhouColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (detail.local) "已在本机书库" else "尚未下载到本机",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (detail.local) YuezhouColors.Brand else YuezhouColors.TextSecondary,
                    )
                }
            },
            confirmButton = {
                if (detail.local) {
                    TextButton(
                        onClick = {
                            detailBook = null
                            context.startActivity(
                                Intent(context, ReaderActivity::class.java).apply {
                                    putExtra(ReaderActivity.EXTRA_BOOK_ID, detail.bookId)
                                },
                            )
                        },
                    ) { Text("打开") }
                } else {
                    TextButton(
                        enabled = !downloading,
                        onClick = {
                            scope.launch {
                                downloading = true
                                val result = CloudSync(context).downloadBook(detail.fileName)
                                downloading = false
                                if (result.isSuccess) {
                                    detailBook = null
                                    refreshKey++ // 刷新本地书库
                                    snackbarHostState.showSnackbar("已下载：${detail.bookId}")
                                } else {
                                    snackbarHostState.showSnackbar(
                                        "下载失败：${result.exceptionOrNull()?.message}",
                                    )
                                }
                            }
                        },
                    ) { Text(if (downloading) "下载中…" else "下载") }
                }
            },
            dismissButton = {
                TextButton(onClick = { detailBook = null }) { Text("关闭") }
            },
        )
    }

    // 扫描结果弹窗（方案 §8.3：列出发现的 EPUB + 同名冲突位置，不自动选）
    val scan = scanResult
    if (scanDialogOpen && scan != null) {
        ScanResultDialog(
            scan = scan,
            onImportLocation = { location ->
                scope.launch {
                    importing = true
                    val result = EpubImporter.import(context, location.uri)
                    importing = false
                    if (result.isSuccess) {
                        status = "已导入：${result.getOrNull()?.nameWithoutExtension}"
                        refreshKey++
                        // 重新扫描刷新「已在库」状态
                        scanUri?.let { u ->
                            val ids = store.snapshot().books.map { it.bookId }.toSet()
                            scanResult = LocalScanner.scan(context, u, ids)
                        }
                    } else {
                        status = "导入失败：${result.exceptionOrNull()?.message}"
                    }
                }
            },
            onDismiss = { scanDialogOpen = false },
        )
    }
}

/** 扫描结果弹窗：列出发现的 EPUB，同名冲突列出全部位置供用户选择。 */
@Composable
private fun ScanResultDialog(
    scan: LocalScanner.ScanResult,
    onImportLocation: (LocalScanner.Location) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("检查本机书籍") },
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
                                                    Color(0xFFE64340)
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
                                            color = Color(0xFFE64340),
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

/** 底部导航的三个 tab。 */
private enum class Tab(val label: String, val icon: ImageVector) {
    书架("书库", Icons.Filled.List),
    最近阅读("最近阅读", Icons.Filled.Star),
    设置("设置", Icons.Filled.Settings),
}

/** 书库范围：本地 / 云端。 */
private enum class BookScope(val label: String) {
    本地("本地"),
    云端("云端"),
}

/** 本地/云端切换条。 */
@Composable
private fun BookScopeSwitch(current: BookScope, onSelect: (BookScope) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BookScope.entries.forEach { scope ->
            val selected = current == scope
            Text(
                scope.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) Color.White else YuezhouColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) YuezhouColors.Brand else YuezhouColors.Surface)
                    .clickable { onSelect(scope) }
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** 云端书库内容：后端名 + 网格 + 空/错误态。 */
@Composable
private fun CloudBookshelfContent(
    books: List<CloudSync.RemoteBook>,
    loading: Boolean,
    error: String?,
    backendLabel: String?,
    fetchedAt: Long,
    fromCache: Boolean,
    onRefresh: () -> Unit,
    onOpenDetail: (CloudSync.RemoteBook) -> Unit,
) {
    when {
        loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        error != null -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = YuezhouColors.TextSecondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onRefresh) { Text("重试") }
                }
            }
        }
        books.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("云端书库为空", color = YuezhouColors.TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "在本地导入书后点「立即同步」即可上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
            }
        }
        else -> {
            Column(Modifier.fillMaxSize()) {
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
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(books, key = { it.bookId }) { book ->
                        CloudBookCard(book = book, onClick = { onOpenDetail(book) })
                    }
                }
            }
        }
    }
}

/** 把 epoch millis 格式化为「HH:mm」或「昨天 HH:mm」等简短时间。 */
private fun formatFetchedAt(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** 云端书籍卡片：书名占位封面 + 本机已存在角标。 */
@Composable
private fun CloudBookCard(
    book: CloudSync.RemoteBook,
    onClick: () -> Unit,
) {
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
                .clip(RoundedCornerShape(8.dp))
                .background(CoverPlaceholder.color(book.bookId)),
            contentAlignment = Alignment.Center,
        ) {
            val cover = book.coverPng
            if (cover != null) {
                CoverImage(
                    bytes = cover,
                    contentDescription = book.bookId,
                    contentScale = ContentScale.FillBounds,
                )
            } else {
                Text(
                    text = book.bookId,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp),
                )
            }

            // 本机已存在角标
            if (book.local) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = YuezhouColors.Brand, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Text(
            text = book.bookId,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 格式化文件大小为可读字符串。 */
private fun formatSize(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
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
                        CoverImage(
                            bytes = cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                        )
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

/** 设置页：全局阅读默认值（主题/翻页/字号/行距/页边距）+ 云同步与朗读的占位说明。 */
@Composable
private fun SettingsPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ReaderSettings.flow(context).collectAsState(
        initial = ReaderPreferences(),
    )

    // 松手才保存的预览值（避免拖动中频繁写 DataStore）
    var fontSizePreview by remember { mutableStateOf<Int?>(null) }
    var lineHeightPreview by remember { mutableStateOf<Double?>(null) }
    var marginPreview by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // 主题
        Text("主题", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("light" to "浅色", "sepia" to "护眼", "dark" to "深色").forEach { (value, label) ->
                val selected = prefs.theme == value
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Color.White else YuezhouColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) YuezhouColors.Brand else YuezhouColors.Surface)
                        .clickable { scope.launch { ReaderSettings.save(context, prefs.copy(theme = value)) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 翻页模式
        Text("翻页模式", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to "左右翻页", true to "上下滚动").forEach { (value, label) ->
                val selected = prefs.vertical == value
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) Color.White else YuezhouColors.TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (selected) YuezhouColors.Brand else YuezhouColors.Surface)
                        .clickable { scope.launch { ReaderSettings.save(context, prefs.copy(vertical = value)) } }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // 字号
        Text("默认字号：${fontSizePreview ?: prefs.fontSize}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = (fontSizePreview ?: prefs.fontSize).toFloat(),
            onValueChange = { fontSizePreview = it.toInt() },
            onValueChangeFinished = {
                val v = fontSizePreview
                if (v != null) {
                    scope.launch { ReaderSettings.save(context, prefs.copy(fontSize = v)) }
                    fontSizePreview = null
                }
            },
            valueRange = 12f..30f,
        )

        // 行距
        Text("默认行距：${String.format("%.1f", lineHeightPreview ?: prefs.lineHeight)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = (lineHeightPreview ?: prefs.lineHeight).toFloat(),
            onValueChange = { lineHeightPreview = it.toDouble() },
            onValueChangeFinished = {
                val v = lineHeightPreview
                if (v != null) {
                    scope.launch { ReaderSettings.save(context, prefs.copy(lineHeight = v)) }
                    lineHeightPreview = null
                }
            },
            valueRange = 1.2f..2.5f,
        )

        // 页边距
        Text("默认页边距：${marginPreview ?: prefs.pageMargin}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = (marginPreview ?: prefs.pageMargin).toFloat(),
            onValueChange = { marginPreview = it.toInt() },
            onValueChangeFinished = {
                val v = marginPreview
                if (v != null) {
                    scope.launch { ReaderSettings.save(context, prefs.copy(pageMargin = v)) }
                    marginPreview = null
                }
            },
            valueRange = 0f..48f,
        )

        Spacer(Modifier.height(24.dp))
        // 云同步后端配置（WebDAV / 百度网盘授权）
        val credentialStore = remember { CloudCredentialStore(context) }
        CloudConfigSection(store = credentialStore)

        // 立即同步（推送本地书库到已配置的云端）
        Spacer(Modifier.height(16.dp))
        var syncMessage by remember { mutableStateOf<String?>(null) }
        var syncing by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !syncing,
                onClick = {
                    scope.launch {
                        syncing = true
                        syncMessage = null
                        val sync = CloudSync(context)
                        val backendLabel = sync.activeBackendLabel()
                        val outcome = sync.pushLocal()
                        syncMessage = if (outcome.success && backendLabel != null) {
                            "${outcome.message}（$backendLabel）"
                        } else {
                            outcome.message
                        }
                        syncing = false
                    }
                },
            ) {
                Text(if (syncing) "同步中…" else "立即同步")
            }
            // 补全待发布回执（方案 §11.6：正文已传但列表发布失败时的补救）
            TextButton(
                enabled = !syncing,
                onClick = {
                    scope.launch {
                        syncing = true
                        syncMessage = null
                        val repaired = CloudSync(context).repairPendingPublications()
                        syncMessage = if (repaired > 0) "已补全 $repaired 本" else "无待补全记录"
                        syncing = false
                    }
                },
            ) {
                Text("补全待发布")
            }
            syncMessage?.let {
                Spacer(Modifier.width(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            }
        }

        // 诊断摘要（方案 §13.2：最近 20 次同步/刷新任务的可执行诊断）
        Spacer(Modifier.height(16.dp))
        var diagnostics by remember { mutableStateOf<List<Pair<Long, Pair<String, com.yuezhou.reader.core.cloud.DiagnosticSummary>>>>(emptyList()) }
        var showDiagnostics by remember { mutableStateOf(false) }
        TextButton(
            onClick = {
                scope.launch {
                    diagnostics = CloudSync(context).diagnosticSummaries()
                    showDiagnostics = !showDiagnostics
                }
            },
        ) {
            Text(if (showDiagnostics) "收起诊断" else "查看诊断")
        }
        if (showDiagnostics) {
            if (diagnostics.isEmpty()) {
                Text(
                    "暂无诊断记录（完成一次同步或刷新书库后生成）",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
            } else {
                // 倒序展示最近的在前面
                diagnostics.reversed().forEach { (at, pair) ->
                    val (kind, s) = pair
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(at))
                    val phaseText = s.phasesMs.entries.joinToString(" ") { "${it.key}=${it.value}ms" }
                    Text(
                        "[$time] $kind · 请求${s.totalRequests} · ↑${s.bytesUp}B ↓${s.bytesDown}B · 缓存${s.cacheHits}" +
                            (s.errorKind?.let { " · $it" } ?: "") + "\n$phaseText",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
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
                CoverImage(
                    bytes = cover,
                    contentDescription = book.title,
                    contentScale = ContentScale.FillBounds,
                )
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

/** 封面图片：后台解码 + 限尺寸降采样，避免大图在主线程解码导致掉帧。 */
@Composable
private fun CoverImage(
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
private fun decodeCoverDownsampled(bytes: ByteArray, targetPx: Int = 512): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
    while (maxDim / (sample * 2) >= targetPx) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
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
    NavigationBar(
        containerColor = YuezhouColors.Surface,
        windowInsets = WindowInsets.safeDrawing,
    ) {
        NavigationBarItem(
            selected = current == Tab.书架,
            onClick = { onSelect(Tab.书架) },
            icon = { Icon(Tab.书架.icon, contentDescription = Tab.书架.label) },
            label = { Text(Tab.书架.label) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = YuezhouColors.Brand,
                selectedTextColor = YuezhouColors.Brand,
                unselectedIconColor = YuezhouColors.TextSecondary,
                unselectedTextColor = YuezhouColors.TextSecondary,
                indicatorColor = YuezhouColors.Brand.copy(alpha = 0.12f),
            ),
        )
        NavigationBarItem(
            selected = current == Tab.最近阅读,
            onClick = { onSelect(Tab.最近阅读) },
            icon = { Icon(Tab.最近阅读.icon, contentDescription = Tab.最近阅读.label) },
            label = { Text(Tab.最近阅读.label) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = YuezhouColors.Brand,
                selectedTextColor = YuezhouColors.Brand,
                unselectedIconColor = YuezhouColors.TextSecondary,
                unselectedTextColor = YuezhouColors.TextSecondary,
                indicatorColor = YuezhouColors.Brand.copy(alpha = 0.12f),
            ),
        )
        NavigationBarItem(
            selected = current == Tab.设置,
            onClick = { onSelect(Tab.设置) },
            icon = { Icon(Tab.设置.icon, contentDescription = Tab.设置.label) },
            label = { Text(Tab.设置.label) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = YuezhouColors.Brand,
                selectedTextColor = YuezhouColors.Brand,
                unselectedIconColor = YuezhouColors.TextSecondary,
                unselectedTextColor = YuezhouColors.TextSecondary,
                indicatorColor = YuezhouColors.Brand.copy(alpha = 0.12f),
            ),
        )
    }
}
