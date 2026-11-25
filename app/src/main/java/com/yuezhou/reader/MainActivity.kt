package com.yuezhou.reader

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
import androidx.compose.material.icons.filled.MoreVert
import com.yuezhou.reader.reader.db.SyncLogEntity
import com.yuezhou.reader.reader.online.mapping.OnlineBookMappingStore
import com.yuezhou.reader.speech.SpeechDiagnosticsState
import com.yuezhou.reader.speech.KokoroImportState
import com.yuezhou.reader.speech.SpeechRuntime
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
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
import com.yuezhou.reader.reader.TempFileCleanup
import com.yuezhou.reader.reader.SyncLogStore
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuezhou.reader.ui.online.DownloadManagerScreen
import com.yuezhou.reader.ui.online.OnlineLibraryScreen
import com.yuezhou.reader.ui.online.OnlineLibraryViewModel
import com.yuezhou.reader.ui.online.ServerSettingsScreen
import com.yuezhou.reader.ui.online.ZLibrarySettingsScreen
import com.yuezhou.reader.web.ZLibraryWebEntry
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
        // 冷启动时异步清理下载/导入遗留的 .part 临时文件（主动取消/进程被杀残留）
        lifecycleScope.launch { TempFileCleanup.clean(this@MainActivity) }
        setContent {
            YuezhouTheme {
                BookshelfScreen()
            }
        }
        forwardExternalEpub(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        forwardExternalEpub(intent)
    }

    private fun forwardExternalEpub(incoming: Intent) {
        // 外部入口结束后，Android 可能把相同 VIEW 请求交给已有任务的书架根页。
        // 从当前任务显式转发，不携带 NEW_TASK，保证重复点击同一本书也会进入导入入口。
        if (incoming.action == Intent.ACTION_VIEW && incoming.data != null) {
            startActivity(Intent(incoming).setClass(this, OpenEpubActivity::class.java)
                .setFlags(incoming.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)))
        }
    }

}

/**
 * 主导航状态用名称字符串保存：Activity 重建或"不保留活动"下仍恢复原页面，
 * 且不受枚举增删顺序影响；无法识别的旧值安全回退到默认页。
 */
private val TabSaver = Saver<Tab, String>(
    save = { it.name },
    restore = { runCatching { Tab.valueOf(it) }.getOrDefault(Tab.书架) },
)

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
    // 主导航状态须跨 Activity 重建/从桌面返回保留，否则会掉回默认的「书库」。
    var currentTab by rememberSaveable(stateSaver = TabSaver) { mutableStateOf(Tab.书架) }
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
    // 书架排序：按最近阅读时间倒序（读过的书排最前），从未读过的书排最后
    val visibleBooks = remember(books, hiddenBooks) {
        books.filter { it.bookId !in hiddenBooks }
            .sortedByDescending { it.lastReadAt ?: Long.MIN_VALUE }
    }
    val removedBooks = books.filter { it.bookId in hiddenBooks }
    val cloudSync = remember { CloudSync(context) }
    // 在线书库统一入口：书源状态由 ViewModel 独立持有，Activity 只保留详情/下载等既有状态。
    val onlineVm: OnlineLibraryViewModel = viewModel()

    // —— 云端书库状态（由 onlineVm 的当前书源状态同步，供既有详情/下载弹窗复用）——
    var remoteBooks by remember { mutableStateOf<List<CloudSync.RemoteBook>>(emptyList()) }
    var remoteLoading by remember { mutableStateOf(false) }
    // 仅「主动点刷新按钮」时置 true，用于显示顶部「更新书库中…」进度条；
    // 切 tab 的静默加载（命中缓存）不显示，避免顶部进度条闪现。
    var remoteRefreshing by remember { mutableStateOf(false) }
    var remoteError by remember { mutableStateOf<String?>(null) }
    var remoteBackendLabel by remember { mutableStateOf<String?>(null) }
    // 列表缓存状态（方案 §12.1：显示最后成功获取时间与是否命中缓存）
    var remoteFetchedAt by remember { mutableStateOf(0L) }
    var remoteFromCache by remember { mutableStateOf(false) }
    // 待下载/详情选中的书
    var detailBook by remember { mutableStateOf<CloudSync.RemoteBook?>(null) }
    // 当前下载任务（同一时间最多一个）；null 表示无任务
    var downloadTask by remember { mutableStateOf<DownloadTask?>(null) }
    // 下载协程 Job：用于取消后台下载
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var downloadTasksOpen by remember { mutableStateOf(false) }
    var confirmCloudDelete by remember { mutableStateOf(false) }
    var deletingCloudBook by remember { mutableStateOf(false) }

    var importOptionsOpen by remember { mutableStateOf(false) }

    // 本地目录只在用户选择扫描时检查。
    var scanResult by remember { mutableStateOf<LocalScanner.ScanResult?>(null) }
    var scanDialogOpen by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // 通知权限（Android 13+）：前台服务进度通知需要；下载前请求
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 无论是否授权都继续下载；未授权时前台服务通知不显示，但仍能保活 */ }

    val pickDirectory = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                scanning = true
                status = null
                try {
                    // 持久授权：允许后续扫描同一目录
                    val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
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

    // 在线下载导入成功后即时刷新书库：书架是一次性快照，过去只靠 ON_RESUME 重载，
    // 会出现「显示已导入，切到书架却看不到，切桌面回来才出现」。StateFlow 保证后台
    // 完成的导入回到前台后仍能补上，版本号 > 0 才刷新以避免初始空转。
    val libraryVersion by onlineVm.downloadManager.libraryVersion.collectAsStateWithLifecycle()
    LaunchedEffect(libraryVersion) {
        if (libraryVersion > 0L) refreshKey++
    }

    // 从阅读器返回书架（ON_RESUME）时刷新书库，让阅读进度/最近阅读排序即时更新
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val uploadQueue = remember(cloudSync, scope) { BookUploadQueue(cloudSync, scope) }

    // 云端书库：进入云端 tab 时加载（书源状态由 ViewModel 缓存；显式刷新走 force）
    fun loadRemote(force: Boolean = false) {
        scope.launch { onlineVm.loadSelected(force) }
    }

    // 当前书源状态 → 既有云端 UI/弹窗状态（避免重构原有组件）
    val onlineState = onlineVm.selectedState
    LaunchedEffect(
        onlineVm.selectedProviderId,
        onlineState.items,
        onlineState.loading,
        onlineState.refreshing,
        onlineState.error,
        onlineState.backendLabel,
        onlineState.fetchedAt,
        onlineState.fromCache,
    ) {
        remoteBooks = onlineVm.legacyBooks(onlineState.items)
        remoteLoading = onlineState.loading
        remoteRefreshing = onlineState.refreshing
        remoteError = onlineState.error
        remoteBackendLabel = onlineState.backendLabel.ifBlank { null }
        remoteFetchedAt = onlineState.fetchedAt
        remoteFromCache = onlineState.fromCache
    }

    // 主动更新书库：登记外部书籍 + 强制刷新当前书源
    fun refreshRemoteLibrary() {
        if (onlineVm.isCatalogSelected) {
            // 检索型书源无云端索引可同步，刷新即按当前条件重新搜索。
            scope.launch { onlineVm.refreshSelected() }
            return
        }
        if (uploadQueue.busy || remoteLoading) return
        scope.launch {
            val (_, message) = onlineVm.refreshSelected()
            if (!message.isNullOrBlank()) {
                status = message
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    // 网页搜索：只传已配置服务器与当前输入关键词，登录沿用 WebView 自身会话。
    fun openZLibraryWeb() {
        val keyword = onlineVm.selectedState.keyword
        if (!ZLibraryWebEntry.launch(context, onlineVm.webBaseUrl(), keyword)) {
            scope.launch { snackbarHostState.showSnackbar("请先配置可用的 Z-Library 服务器") }
        }
    }

    // 切换到云端一级页时加载一次（命中缓存时不重复请求网络）
    LaunchedEffect(currentTab) {
        if (currentTab == Tab.云端) {
            loadRemote()
        }
    }

    // 管理态下返回键退出管理态（而非退出 App）
    BackHandler(enabled = selecting != null) {
        selecting = null
    }

    // 二级设置页：以独立全屏页面呈现，隐藏外层顶栏、底部导航与悬浮按钮（方案 §3.1）。
    var showSyncLogPage by rememberSaveable { mutableStateOf(false) }
    var showZLibrarySettings by rememberSaveable { mutableStateOf(false) }
    var showServerSettings by rememberSaveable { mutableStateOf(false) }
    val fullscreenSubPage = showSyncLogPage || showZLibrarySettings || showServerSettings

    // 系统返回键逐层退出二级页，恢复原有导航结构（方案 §3.1）。
    BackHandler(enabled = showServerSettings) { showServerSettings = false }
    BackHandler(enabled = !showServerSettings && showZLibrarySettings) { showZLibrarySettings = false }
    BackHandler(enabled = !showServerSettings && !showZLibrarySettings && showSyncLogPage) { showSyncLogPage = false }
    // 统一下载管理页：覆盖在主界面之上，返回键逐层退出。
    var showOnlineDownloads by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showOnlineDownloads) { showOnlineDownloads = false }
    // 云端一级页右上角「更多」：刷新与网页搜索；额度/账户在后续阶段迁入。
    var cloudMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = YuezhouColors.Background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!fullscreenSubPage && selecting != null && currentTab == Tab.书架) {
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
            } else if (!fullscreenSubPage) {
                TopAppBar(
                    title = {
                        Text(
                            when (currentTab) {
                                Tab.书架 -> "书库"
                                Tab.云端 -> "云端书库"
                                Tab.设置 -> currentTab.label
                            },
                        )
                    },
                    actions = {
                        if (currentTab == Tab.书架 && removedBooks.isNotEmpty()) {
                            TextButton(onClick = { showHiddenBooks = true }) { Text("已移除") }
                        }
                        // 下载任务入口：书库与云端都保留；设置页不显示。
                        if (currentTab == Tab.书架 || currentTab == Tab.云端) {
                            IconButton(onClick = { showOnlineDownloads = true }) {
                                AnimatedDownloadIcon(
                                    downloading = downloadTask?.status == DownloadStatus.Downloading,
                                )
                            }
                        }
                        if (currentTab == Tab.云端) {
                            Box {
                                IconButton(onClick = { cloudMenuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = cloudMenuOpen,
                                    onDismissRequest = { cloudMenuOpen = false },
                                ) {
                                    val catalog = onlineVm.isCatalogSelected
                                    val submitted = onlineVm.selectedState.submittedKeyword
                                    DropdownMenuItem(
                                        text = { Text("刷新云端书库") },
                                        // 检索书源必须已有提交词才可刷新，避免用输入框草稿触发请求。
                                        enabled = !onlineVm.selectedState.refreshing &&
                                            (!catalog || submitted.isNotBlank()),
                                        onClick = {
                                            cloudMenuOpen = false
                                            refreshRemoteLibrary()
                                        },
                                    )
                                    if (catalog) {
                                        DropdownMenuItem(
                                            text = { Text("网页搜索") },
                                            onClick = {
                                                cloudMenuOpen = false
                                                openZLibraryWeb()
                                            },
                                        )
                                    }
                                }
                            }
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
            // 云端刷新改由下拉刷新与「更多」菜单承接，不再使用悬浮按钮。
            if (!fullscreenSubPage && currentTab == Tab.书架) {
                FloatingActionButton(
                    onClick = {
                        if (!scanning && !importing) importOptionsOpen = true
                    },
                    containerColor = YuezhouColors.Brand,
                    contentColor = Color.White,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "导入书籍")
                }
            }
        },
        bottomBar = {
            if (!fullscreenSubPage) {
                BottomNavBar(current = currentTab, onSelect = { currentTab = it })
            }
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
                Tab.云端 -> OnlineLibraryScreen(
                    vm = onlineVm,
                    onRefresh = { refreshRemoteLibrary() },
                    onOpenDetail = { detailBook = it },
                    onOpenSettings = {
                        currentTab = Tab.设置
                        showZLibrarySettings = true
                    },
                )
                Tab.设置 -> SettingsPage(
                    cloudSync = cloudSync,
                    uploadQueue = uploadQueue,
                    cloudLoading = remoteLoading,
                    showSyncLogPage = showSyncLogPage,
                    onSyncLogChange = { showSyncLogPage = it },
                    showZLibrarySettings = showZLibrarySettings,
                    onZLibraryChange = { showZLibrarySettings = it },
                    showServerSettings = showServerSettings,
                    onServerSettingsChange = { showServerSettings = it },
                )
            }
        }
    }

    // 统一下载管理（阶段 E）：在线任务与既有云端下载入口并列，不改动原下载弹窗。
    if (showOnlineDownloads) {
        DownloadManagerScreen(
            vm = onlineVm,
            onBack = { showOnlineDownloads = false },
            onOpenBook = { bookId ->
                showOnlineDownloads = false
                context.startActivity(
                    Intent(context, ReaderActivity::class.java).apply {
                        putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                    },
                )
            },
            onOpenLegacyDownloads = {
                showOnlineDownloads = false
                downloadTasksOpen = true
            },
        )
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
                            // 本地书籍删除后同步失效在线映射，避免仍显示为「已下载」。
                            books.filter { it.bookId in result.removed }.forEach { book ->
                                runCatching { OnlineBookMappingStore.deactivateByFileName(context, book.fileName) }
                            }
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
                                val result = cloudSync.deleteRemoteBook(detail.bookId, forced = onlineVm.selectedBackendKind())
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
        val taskRunning = downloadTask?.status == DownloadStatus.Downloading
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
                    TextButton(
                        enabled = !taskRunning && !deletingCloudBook && !uploadQueue.busy && !remoteLoading,
                        onClick = { confirmCloudDelete = true },
                    ) { Text("删除云端书籍", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                if (detail.local) {
                    TextButton(onClick = {
                        detailBook = null
                        context.startActivity(
                            Intent(context, ReaderActivity::class.java).apply {
                                putExtra(ReaderActivity.EXTRA_BOOK_ID, detail.bookId)
                            },
                        )
                    }) { Text("打开") }
                }
                TextButton(enabled = !taskRunning, onClick = {
                    detailBook = null
                    // Android 13+ 先请求通知权限（前台服务进度通知需要）
                    if (Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    // 后台启动下载，不阻塞阅读；同时只有一个任务
                    downloadTask = DownloadTask(
                        bookId = detail.bookId,
                        fileName = detail.fileName,
                        total = detail.size,
                    )
                    // 启动前台服务保活：切后台/锁屏时系统不冻结进程网络，避免 readTimeout 导致下载失败
                    DownloadForegroundService.start(context, detail.fileName)
                    // 用 lifecycleScope 而非 rememberCoroutineScope：切后台/锁屏不取消，下载持续到 Activity 销毁
                    downloadJob = lifecycleOwner.lifecycleScope.launch {
                            val result = try {
                                cloudSync.downloadBook(
                                    book = detail,
                                    isOpen = ReaderActivity::isBookOpen,
                                    forced = onlineVm.selectedBackendKind(),
                                    onProgress = { received, total, phase ->
                                        downloadTask = downloadTask?.copy(
                                            received = received,
                                            total = total,
                                            phase = when (phase) {
                                                com.yuezhou.reader.reader.CloudSync.DownloadPhase.Transferring -> DownloadPhase.Transferring
                                                com.yuezhou.reader.reader.CloudSync.DownloadPhase.Verifying -> DownloadPhase.Verifying
                                                com.yuezhou.reader.reader.CloudSync.DownloadPhase.Extracting -> DownloadPhase.Extracting
                                                com.yuezhou.reader.reader.CloudSync.DownloadPhase.Publishing -> DownloadPhase.Publishing
                                            },
                                        )
                                        DownloadForegroundService.updateProgress(context, detail.fileName, received, total)
                                    },
                                )
                        } catch (e: CancellationException) {
                            downloadTask = null
                            downloadJob = null
                            DownloadForegroundService.stop(context)
                            return@launch
                        }
                        DownloadForegroundService.stop(context)
                        if (result.isSuccess) {
                            downloadTask = downloadTask?.copy(status = DownloadStatus.Done, received = downloadTask?.total ?: detail.size)
                            refreshKey++
                            snackbarHostState.showSnackbar("已下载：${detail.bookId}")
                        } else {
                            downloadTask = downloadTask?.copy(
                                status = DownloadStatus.Failed,
                                error = result.exceptionOrNull()?.message,
                            )
                            snackbarHostState.showSnackbar("下载失败：${result.exceptionOrNull()?.message}")
                        }
                        downloadJob = null
                    }
                }) { Text(if (taskRunning) "下载中…" else if (detail.local) "重新下载" else "下载") }
            },
            dismissButton = {
                TextButton(onClick = { detailBook = null }) { Text("关闭") }
            },
        )
    }

    // 下载任务弹窗：顶部下载图标点击后展示当前任务状态
    if (downloadTasksOpen) {
        val task = downloadTask
        AlertDialog(
            onDismissRequest = { downloadTasksOpen = false },
            title = { Text("下载任务") },
            text = {
                if (task == null) {
                    Text("暂无下载任务", color = YuezhouColors.TextSecondary)
                } else {
                    Column {
                        Text(task.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        when (task.status) {
                            DownloadStatus.Downloading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        progress = if (task.total > 0) {
                                            (task.received.toFloat() / task.total).coerceIn(0f, 1f)
                                        } else 0f,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (task.total > 0) "${task.percent}%" else "下载中…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = YuezhouColors.Brand,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = if (task.total > 0) {
                                        (task.received.toFloat() / task.total).coerceIn(0f, 1f)
                                    } else 0f,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        when (task.phase) {
                                            DownloadPhase.Transferring -> "${formatSize(task.received)} / ${formatSize(task.total)}"
                                            DownloadPhase.Verifying -> "正在校验文件…"
                                            DownloadPhase.Extracting -> "正在提取封面…"
                                            DownloadPhase.Publishing -> "正在保存到本机…"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = YuezhouColors.TextSecondary,
                                    )
                                }
                            }
                            DownloadStatus.Done -> {
                                Text("下载完成", style = MaterialTheme.typography.bodyMedium, color = YuezhouColors.Brand)
                            }
                            DownloadStatus.Failed -> {
                                Text("下载失败", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                task.error?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                // 下载完成后可清除任务；下载中可取消
                when (task?.status) {
                    DownloadStatus.Downloading -> {
                        TextButton(onClick = {
                            downloadJob?.cancel()
                            downloadTask = null
                            downloadJob = null
                            DownloadForegroundService.stop(context)
                            downloadTasksOpen = false
                            scope.launch { snackbarHostState.showSnackbar("已取消下载") }
                        }) { Text("取消下载", color = MaterialTheme.colorScheme.error) }
                    }
                    DownloadStatus.Done, DownloadStatus.Failed -> {
                        TextButton(onClick = {
                            downloadTask = null
                            downloadTasksOpen = false
                        }) { Text("清除") }
                    }
                    null -> {}
                }
            },
            dismissButton = {
                TextButton(onClick = { downloadTasksOpen = false }) { Text("关闭") }
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
    云端("云端", cloudTabIcon()),
    设置("设置", Icons.Filled.Settings),
}

/** icons-core 不包含 Cloud，使用标准 Material Cloud 24dp 路径，避免新增图标依赖。 */
private fun cloudTabIcon(): ImageVector = ImageVector.Builder(
    name = "CloudTab",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(19.35f, 10.04f)
        curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
        curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
        curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
        curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
        horizontalLineTo(19f)
        curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
        curveTo(24f, 12.37f, 21.95f, 10.22f, 19.35f, 10.04f)
        close()
    }
}.build()

/** 单一下载任务状态（同一时间最多一个任务）。 */
private data class DownloadTask(
    val bookId: String,
    val fileName: String,
    val total: Long,
    val received: Long = 0L,
    val status: DownloadStatus = DownloadStatus.Downloading,
    val phase: DownloadPhase = DownloadPhase.Transferring,
    val error: String? = null,
) {
    val percent: Int
        get() = if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0
}

private enum class DownloadStatus { Downloading, Done, Failed }

/** 下载所处阶段（传输 vs 落盘），用于进度文案。 */
private enum class DownloadPhase { Transferring, Verifying, Extracting, Publishing }

/** 自绘下载图标（material-icons-core 无 Download，用描边路径绘制向下箭头+托盘）。 */
private val DownloadIcon: ImageVector by lazy {
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
private fun AnimatedDownloadIcon(downloading: Boolean) {
    val transition = rememberInfiniteTransition(label = "download")
    val floatY by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "float",
    )
    val rotate by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotate",
    )

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
                .graphicsLayer { translationY = if (downloading) floatY else 0f },
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
    onRefresh: () -> Unit,
    onOpenDetail: (CloudSync.RemoteBook) -> Unit,
) {
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
            maxLines = 1,
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

@Composable
private fun SettingsPage(
    cloudSync: CloudSync,
    uploadQueue: BookUploadQueue,
    cloudLoading: Boolean,
    showSyncLogPage: Boolean,
    onSyncLogChange: (Boolean) -> Unit,
    showZLibrarySettings: Boolean,
    onZLibraryChange: (Boolean) -> Unit,
    showServerSettings: Boolean,
    onServerSettingsChange: (Boolean) -> Unit,
) {
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

    var modelBusy by remember { mutableStateOf(false) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var modelInfos by remember { mutableStateOf(SpeechRuntime.kokoro?.modelInfos().orEmpty()) }
    var modelManagerOpen by rememberSaveable { mutableStateOf(false) }
    val speechDiagnostics by SpeechDiagnosticsState.snapshot.collectAsStateWithLifecycle()
    val importProgress by KokoroImportState.progress.collectAsStateWithLifecycle()
    val modelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            modelBusy = true
            modelMessage = "正在导入模型…"
            scope.launch {
                val result = runCatching { SpeechRuntime.kokoro?.importModel(uri) }.getOrNull()
                modelInfos = runCatching { SpeechRuntime.kokoro?.modelInfos() }.getOrNull().orEmpty()
                modelBusy = false
                modelMessage = result?.message ?: "导入失败：模型管理未装配"
            }
        }
    }

    if (showSyncLogPage) {
        SyncLogPage(onBack = { onSyncLogChange(false) })
        return
    }
    if (showServerSettings) {
        ServerSettingsScreen(onBack = { onServerSettingsChange(false) })
        return
    }
    if (showZLibrarySettings) {
        ZLibrarySettingsScreen(
            onBack = { onZLibraryChange(false) },
            onOpenServerSettings = { onServerSettingsChange(true) },
        )
        return
    }

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

        SettingsCard("自动同步进度") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动同步", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "进入书籍自动拉取云端进度覆盖本地；退出或切出时自动上传覆盖云端",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
                Switch(
                    checked = prefs.autoSync,
                    onCheckedChange = { enabled ->
                        scope.launch { ReaderSettings.save(context, prefs.copy(autoSync = enabled)) }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            // 同步记录入口：点击进入独立页面
            TextButton(onClick = { onSyncLogChange(true) }) {
                Text("查看最近 20 次同步记录")
            }
        }

        SettingsCard("书源设置") {
            Text(
                "配置百度网盘、WebDAV 等云端书源账号。",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            CloudConfigSection(store = credentialStore)

            // Z-Library 为检索型书源，配置与云同步账号分开管理。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onZLibraryChange(true) }
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Z-Library", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "检索型书源 · 配置、登录与额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
                Text("去配置", style = MaterialTheme.typography.bodyMedium, color = YuezhouColors.Brand)
            }
        }

        if (SpeechRuntime.kokoro != null) {
            SettingsCard("Kokoro 朗读模型") {
                val selectedModel = modelInfos.firstOrNull { it.id == prefs.kokoroModel }
                Text(
                    selectedModel?.label ?: "未选择模型",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (modelInfos.isEmpty()) "暂无可用模型，请导入 ZIP 或 tar.bz2 模型包。" else "在此管理模型、试音和诊断信息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
                Row {
                    TextButton(onClick = {
                        modelInfos = SpeechRuntime.kokoro?.modelInfos().orEmpty()
                        modelMessage = null
                        modelManagerOpen = true
                    }) {
                        Text("管理模型")
                    }
                }
            }
        }

        SettingsCard("后台朗读") {
            SpeechBackgroundSettings()
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
                listOf("light" to "浅色", "sepia" to "护眼", "dark" to "深色", "green" to "青绿", "night-blue" to "夜蓝").forEach { (value, label) ->
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

            ReaderFontPreference(prefs.fontId, prefs.fontWeight, onWeightChange = { w ->
                scope.launch { ReaderSettings.save(context, prefs.copy(fontWeight = w)) }
            })

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

        if (modelManagerOpen) {
            KokoroModelManagerDialog(
                models = modelInfos,
                diagnostics = speechDiagnostics,
                busy = modelBusy || importProgress?.running == true,
                progress = importProgress,
                message = modelMessage,
                onImport = { modelImportLauncher.launch(arrayOf("*/*")) },
                onAudition = { info ->
                    modelBusy = true
                    modelMessage = "正在试音 ${info.id}…"
                    scope.launch {
                        val error = runCatching {
                            SpeechRuntime.kokoro?.auditionModel(
                                info.id,
                                prefs.kokoroSpeaker,
                                "你好，这是 ${info.id} 模型的试音。",
                            )
                        }.getOrNull()
                        modelBusy = false
                        modelMessage = error ?: "${info.id} 试音完成"
                    }
                },
                onDelete = { info ->
                    modelBusy = true
                    modelMessage = "正在删除 ${info.id}…"
                    scope.launch {
                        val deleted = runCatching { SpeechRuntime.kokoro?.deleteModel(info.id) }.getOrNull() == true
                        modelInfos = runCatching { SpeechRuntime.kokoro?.modelInfos() }.getOrNull().orEmpty()
                        modelBusy = false
                        modelMessage = if (deleted) "已删除 ${info.id}" else "删除失败：模型可能正在使用"
                        if (deleted && prefs.kokoroModel == info.id) {
                            ReaderSettings.save(context, prefs.copy(kokoroModel = ""))
                        }
                    }
                },
                onDismiss = {
                    if (!modelBusy) {
                        modelManagerOpen = false
                        modelMessage = null
                    }
                },
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
                        val time = formatLocalTime("HH:mm:ss", at)
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
private fun SyncLogPage(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf<List<SyncLogEntity>?>(null) }

    LaunchedEffect(Unit) {
        logs = SyncLogStore(context).recent()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏：返回 + 标题
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = YuezhouColors.BrandDark)
            }
            Text(
                "同步记录（最近 20 次）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(64.dp))
        }
        // 记录列表
        val list = logs
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "暂无同步记录\n开启自动同步后，拉取/上传进度时会生成",
                    style = MaterialTheme.typography.bodyMedium,
                    color = YuezhouColors.TextSecondary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { log ->
                    val dirLabel = if (log.direction == "pull") "拉取" else "上传"
                    val time = formatLocalTime("MM-dd HH:mm:ss", log.at)
                    val ok = if (log.success) "成功" else "失败"
                    val okColor = if (log.success) YuezhouColors.BrandDark else MaterialTheme.colorScheme.error
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = YuezhouColors.Surface,
                        border = BorderStroke(1.dp, YuezhouColors.Divider),
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    dirLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = okColor,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    ok,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = okColor,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    time,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = YuezhouColors.TextSecondary,
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                log.bookTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (log.message.isNotBlank()) {
                                Text(
                                    log.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = YuezhouColors.TextSecondary,
                                )
                            }
                        }
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
            maxLines = 1,
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
                    selectedIconColor = YuezhouColors.Brand,
                    selectedTextColor = YuezhouColors.Brand,
                    unselectedIconColor = YuezhouColors.TextSecondary,
                    unselectedTextColor = YuezhouColors.TextSecondary,
                    indicatorColor = YuezhouColors.Brand.copy(alpha = 0.12f),
                ),
            )
        }
    }
}

/** 日志时间戳格式化：从 Composable 中移出，避免在组合期间读取非可观察的 Locale。 */
private fun formatLocalTime(pattern: String, epochMillis: Long): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
