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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.DownloadDirectory
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.LocalScanner
import kotlinx.coroutines.CancellationException
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
    var shelfMenuOpen by remember { mutableStateOf(false) }
    var showHiddenBooks by remember { mutableStateOf(false) }
    var deletingLocalBooks by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val store = remember { LibraryStore(context) }
    var hiddenBooks by remember { mutableStateOf(store.hiddenBookIds()) }
    val visibleBooks = books.filter { it.bookId !in hiddenBooks }
    val removedBooks = books.filter { it.bookId in hiddenBooks }
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
    var confirmCloudDelete by remember { mutableStateOf(false) }
    var deletingCloudBook by remember { mutableStateOf(false) }

    var importOptionsOpen by remember { mutableStateOf(false) }

    // 本地目录只在用户选择扫描时检查。
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
        hiddenBooks = store.hiddenBookIds()
        loading = false
    }

    val uploadQueue = remember(cloudSync, scope) { BookUploadQueue(cloudSync, scope) }

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
        if (uploadQueue.busy || remoteLoading) return
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
            if (selecting != null && currentTab == Tab.书架 && bookScope == BookScope.本地) {
                // 管理态顶栏：已选数量 + 全选/删除
                val selected = selecting!!
                TopAppBar(
                    title = { Text("已选 ${selected.size} 本") },
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
                        val allSelected = visibleBooks.isNotEmpty() && selected.size == visibleBooks.size
                        Text(
                            if (allSelected) "取消全选" else "全选",
                            style = MaterialTheme.typography.bodyMedium,
                            color = YuezhouColors.Brand,
                            modifier = Modifier
                                .clickable {
                                    selecting = if (allSelected) emptySet()
                                    else visibleBooks.map { it.bookId }.toSet()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        Box {
                            IconButton(enabled = selected.isNotEmpty() && !deletingLocalBooks, onClick = { shelfMenuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = shelfMenuOpen, onDismissRequest = { shelfMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("从书架移除") },
                                    onClick = {
                                        shelfMenuOpen = false
                                        selecting = null
                                        store.setBooksHidden(selected, true)
                                        hiddenBooks = store.hiddenBookIds()
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "已从书架移除 ${selected.size} 本，文件已保留",
                                                actionLabel = "撤销",
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                store.setBooksHidden(selected, false)
                                                hiddenBooks = store.hiddenBookIds()
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除本机文件", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        shelfMenuOpen = false
                                        pendingDelete = selected
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = YuezhouColors.Surface,
                        titleContentColor = YuezhouColors.TextPrimary,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text(if (currentTab == Tab.书架) "书库" else currentTab.label) },
                    actions = {
                        if (currentTab == Tab.书架 && bookScope == BookScope.本地 && removedBooks.isNotEmpty()) {
                            TextButton(onClick = { showHiddenBooks = true }) { Text("已移除") }
                        }
                    },
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
                        onClick = {
                            if (!scanning && !importing) importOptionsOpen = true
                        },
                        containerColor = YuezhouColors.Brand,
                        contentColor = Color.White,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "导入书籍")
                    }
                } else {
                    // 云端：主动刷新按钮（主动更新：强制拉列表 + 发现外部上传）
                    FloatingActionButton(
                        onClick = { refreshRemoteLibrary() },
                        containerColor = YuezhouColors.Brand,
                        contentColor = Color.White,
                    ) {
                        Text("刷新", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
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

            when (currentTab) {
                Tab.书架 -> {
                    // 本地/云端切换条
                    BookScopeSwitch(current = bookScope, onSelect = {
                        bookScope = it
                        selecting = null
                    })
                    when (bookScope) {
                        BookScope.本地 -> {
                            if (scanning) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("扫描文件夹中…", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            BookshelfContent(
                                books = visibleBooks,
                                loading = loading,
                                selecting = selecting,
                                onImport = { if (!scanning && !importing) importOptionsOpen = true },
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
                Tab.设置 -> SettingsPage(cloudSync, uploadQueue, remoteLoading)
            }
        }
    }

    if (showHiddenBooks) {
        AlertDialog(
            onDismissRequest = { showHiddenBooks = false },
            title = { Text("已从书架移除") },
            text = {
                if (removedBooks.isEmpty()) Text("暂无已移除的书籍")
                else LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(removedBooks, key = { it.bookId }) { book ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(book.title, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                store.setBooksHidden(setOf(book.bookId), false)
                                hiddenBooks = store.hiddenBookIds()
                            }) { Text("恢复") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHiddenBooks = false }) { Text("关闭") } },
        )
    }

    val toDelete = pendingDelete
    if (!toDelete.isNullOrEmpty()) {
        val selectedBooks = books.filter { it.bookId in toDelete }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 ${selectedBooks.size} 本本机文件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("删除成功后将移出书架。只删除下方所列文件。阅读进度和云端书籍会保留。")
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(selectedBooks, key = { it.bookId }) { book ->
                            Text(book.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                book.localPath,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    selecting = null
                    deletingLocalBooks = true
                    scope.launch {
                        try {
                            val result = store.removeBooks(toDelete, ReaderActivity::isBookOpen)
                            refreshKey++
                            val failures = result.failures.entries.joinToString("；") { (id, reason) -> "$id：$reason" }
                            status = "已删除 ${result.removed.size} 本本机文件" +
                                if (failures.isEmpty()) "" else "，${result.failures.size} 本失败：$failures"
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            refreshKey++
                            status = "无法更新本地书架：${e.message}"
                        } finally {
                            deletingLocalBooks = false
                        }
                    }
                }) { Text("删除本机文件", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    // 云端书籍详情弹窗：名称/大小/后端 + 下载（或已本地存在时打开）
    val detail = detailBook
    if (detail != null && confirmCloudDelete) {
        AlertDialog(
            onDismissRequest = { if (!deletingCloudBook) confirmCloudDelete = false },
            title = { Text("删除云端书籍") },
            text = { Text("将从${remoteBackendLabel ?: "云端"}删除《${detail.bookId}》的正文、封面和云端阅读进度。本机书籍及进度会保留。") },
            confirmButton = {
                TextButton(
                    enabled = !deletingCloudBook,
                    onClick = {
                        deletingCloudBook = true
                        scope.launch {
                            try {
                                val result = cloudSync.deleteRemoteBook(detail.bookId)
                                confirmCloudDelete = false
                                if (result.success) {
                                    detailBook = null
                                    remoteBooks = remoteBooks.filterNot { it.bookId == detail.bookId }
                                    loadRemote(force = true)
                                }
                                snackbarHostState.showSnackbar(result.message)
                            } finally {
                                deletingCloudBook = false
                            }
                        }
                    },
                ) { Text(if (deletingCloudBook) "删除中…" else "删除云端", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(enabled = !deletingCloudBook, onClick = { confirmCloudDelete = false }) { Text("取消") }
            },
        )
    } else if (detail != null) {
        AlertDialog(
            onDismissRequest = { if (!downloading) detailBook = null },
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
                    TextButton(
                        enabled = !downloading && !deletingCloudBook && !uploadQueue.busy && !remoteLoading,
                        onClick = { confirmCloudDelete = true },
                    ) { Text("删除云端书籍", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (detail.local) {
                    TextButton(enabled = !downloading, onClick = {
                        detailBook = null
                        context.startActivity(
                            Intent(context, ReaderActivity::class.java).apply {
                                putExtra(ReaderActivity.EXTRA_BOOK_ID, detail.bookId)
                            },
                        )
                    }) { Text("打开") }
                }
                TextButton(enabled = !downloading, onClick = {
                    downloading = true
                    scope.launch {
                        val result = try {
                            cloudSync.downloadBook(detail, ReaderActivity::isBookOpen)
                        } finally {
                            downloading = false
                        }
                        if (result.isSuccess) {
                            detailBook = null
                            refreshKey++
                            snackbarHostState.showSnackbar("已下载：${detail.bookId}")
                        } else {
                            snackbarHostState.showSnackbar("下载失败：${result.exceptionOrNull()?.message}")
                        }
                    }
                }) { Text(if (downloading) "下载中…" else if (detail.local) "重新下载" else "下载") }
            },
            dismissButton = {
                TextButton(onClick = { detailBook = null }, enabled = !downloading) { Text("关闭") }
            },
        )
    }

    if (importOptionsOpen) {
        AlertDialog(
            onDismissRequest = { importOptionsOpen = false },
            title = { Text("导入书籍") },
            dismissButton = {
                TextButton(onClick = {
                    importOptionsOpen = false
                    pickDirectory.launch(null)
                }) { Text("扫描指定文件夹") }
            },
            confirmButton = {
                TextButton(onClick = {
                    importOptionsOpen = false
                    pickEpub.launch(arrayOf("application/epub+zip"))
                }) { Text("选择书籍") }
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

/** 本地/云端切换条：胶囊滑块式分段控件。 */
@Composable
private fun BookScopeSwitch(current: BookScope, onSelect: (BookScope) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(YuezhouColors.Surface)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        BookScope.entries.forEach { scope ->
            val selected = current == scope
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (selected) YuezhouColors.BrandContainer else Color.Transparent)
                    .clickable { onSelect(scope) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    scope.label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                )
            }
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
            EmptyState(
                icon = Icons.Filled.List,
                title = "云端书库加载失败",
                subtitle = error,
                actionLabel = "重试",
                onAction = onRefresh,
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
                .clip(RoundedCornerShape(10.dp))
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
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.White.copy(alpha = 0.92f)),
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
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
    onImport: () -> Unit,
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
            EmptyLibrary(onImport = onImport)
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
        EmptyState(
            icon = Icons.Filled.Star,
            title = "还没有阅读记录",
            subtitle = "开始读一本书后，它会出现在这里",
        )
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        lazyItems(recent, key = { it.bookId }) { book ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(YuezhouColors.Surface)
                    .clickable {
                        context.startActivity(
                            Intent(context, ReaderActivity::class.java).apply {
                                putExtra(ReaderActivity.EXTRA_BOOK_ID, book.bookId)
                            },
                        )
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 小封面
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 64.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CoverPlaceholder.color(book.bookId)),
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
                            color = Color.White,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = YuezhouColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(YuezhouColors.Divider),
                        ) {
                            book.percent?.let { p ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(p.coerceIn(0.0, 1.0).coerceAtLeast(0.01).toFloat())
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(1.5.dp))
                                        .background(YuezhouColors.Brand),
                                )
                            }
                        }
                        book.percent?.let { p ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "已读 ${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = YuezhouColors.TextSecondary,
                            )
                        }
                    }
                    book.lastReadAt?.let { ts ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatReadAt(ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = YuezhouColors.TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** 把阅读时间戳格式化为「刚刚 / N 分钟前 / 昨天 / MM-dd」等简短文案。 */
private fun formatReadAt(millis: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - millis
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "刚刚"
        diff < hour -> "${diff / minute} 分钟前"
        diff < day -> "${diff / hour} 小时前"
        diff < 2 * day -> "昨天"
        else -> {
            val fmt = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            fmt.format(java.util.Date(millis))
        }
    }
}

@Composable
private fun SettingsPage(cloudSync: CloudSync, uploadQueue: BookUploadQueue, cloudLoading: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ReaderSettings.flow(context).collectAsState(
        initial = ReaderPreferences(),
    )

    val downloadDirectory = remember(context) { DownloadDirectory(context) }
    var downloadLabel by remember { mutableStateOf(downloadDirectory.label()) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    val chooseDownloadDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        downloadDirectory.select(uri)
                    }
                    downloadLabel = downloadDirectory.label()
                    downloadError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    downloadError = "无法设置下载目录，请选择可读写的文件夹"
                }
            }
        }
    }

    // 松手才保存的预览值（避免拖动中频繁写 DataStore）
    var fontSizePreview by remember { mutableStateOf<Int?>(null) }
    var lineHeightPreview by remember { mutableStateOf<Double?>(null) }
    var marginPreview by remember { mutableStateOf<Int?>(null) }

    var showReading by rememberSaveable { mutableStateOf(false) }
    val credentialStore = remember(context) { CloudCredentialStore(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard("书籍同步", highlighted = true) {
            BookUploadControls(cloudSync, uploadQueue, cloudLoading)
        }

        SettingsCard("云端账号") {
            CloudConfigSection(store = credentialStore)
        }

        SettingsCard("下载目录") {
            Text(downloadLabel, style = MaterialTheme.typography.bodyMedium)
            Text("新下载的书会直接从所选目录打开。", style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            Row {
                TextButton(onClick = { chooseDownloadDirectory.launch(downloadDirectory.uri) }) {
                    Text("选择目录")
                }
                if (downloadDirectory.uri != null) {
                    TextButton(onClick = {
                        downloadDirectory.reset()
                        downloadLabel = downloadDirectory.label()
                        downloadError = null
                    }) {
                        Text("恢复默认")
                    }
                }
            }
            downloadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }

        SettingsCard(
            title = "默认阅读排版",
            expanded = showReading,
            onToggle = { showReading = !showReading },
        ) {
            // 主题
            Text("主题", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("light" to "浅色", "sepia" to "护眼", "dark" to "深色").forEach { (value, label) ->
                    val selected = prefs.theme == value
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) YuezhouColors.BrandContainer else YuezhouColors.Surface)
                            .clickable { scope.launch { ReaderSettings.save(context, prefs.copy(theme = value)) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // 翻页模式
            Text("翻页模式", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "左右翻页", true to "上下滚动").forEach { (value, label) ->
                    val selected = prefs.vertical == value
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) YuezhouColors.BrandContainer else YuezhouColors.Surface)
                            .clickable { scope.launch { ReaderSettings.save(context, prefs.copy(vertical = value)) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            // 翻页动画方式
            Text("翻页动画", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "none" to "无动画",
                    "slide" to "平滑滑动",
                    "simulate" to "仿真翻页",
                    "ripple" to "水波纹",
                ).forEach { (value, label) ->
                    val selected = prefs.pageAnimation == value
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (selected) YuezhouColors.BrandContainer else YuezhouColors.Surface)
                            .clickable { scope.launch { ReaderSettings.save(context, prefs.copy(pageAnimation = value)) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))

            ReaderFontPreference(prefs.fontId, prefs.fontWeight)

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
        }

        SettingsCard("同步诊断") {
            // 诊断摘要（方案 §13.2：最近 20 次同步/刷新任务的可执行诊断）
            var diagnostics by remember { mutableStateOf<List<Pair<Long, Pair<String, com.yuezhou.reader.core.cloud.DiagnosticSummary>>>>(emptyList()) }
            var showDiagnostics by remember { mutableStateOf(false) }
            TextButton(
                onClick = {
                    scope.launch {
                        diagnostics = cloudSync.diagnosticSummaries()
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
}

@Composable
private fun SettingsCard(
    title: String,
    highlighted: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val color = if (highlighted) YuezhouColors.BrandContainer else YuezhouColors.Surface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = color,
        border = BorderStroke(1.dp, if (highlighted) YuezhouColors.Brand.copy(alpha = 0.20f) else YuezhouColors.Divider),
    ) {
        Column {
            val header = Modifier.fillMaxWidth()
            Row(
                modifier = (if (onToggle != null) header.clickable(onClick = onToggle) else header)
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (onToggle != null) {
                    Icon(
                        if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = YuezhouColors.TextSecondary,
                    )
                }
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) { content() }
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
                .clip(RoundedCornerShape(10.dp))
                .background(CoverPlaceholder.color(book.bookId))
                .then(
                    if (selecting && checked) {
                        Modifier.border(2.dp, YuezhouColors.Brand, RoundedCornerShape(10.dp))
                    } else {
                        Modifier
                    },
                ),
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
                        .size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (checked) YuezhouColors.Brand else Color.White.copy(alpha = 0.9f))
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
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        if (book.percent != null) {
            val p = book.percent!!.coerceIn(0.0, 1.0)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(YuezhouColors.Divider),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(p.coerceAtLeast(0.01).toFloat())
                            .height(3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(YuezhouColors.Brand),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${(p * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = YuezhouColors.TextSecondary,
                )
            }
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
private fun EmptyState(
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
