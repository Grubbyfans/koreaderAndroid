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
import com.yuezhou.reader.ui.shelf.AnimatedDownloadIcon
import com.yuezhou.reader.ui.shelf.BookshelfContent
import com.yuezhou.reader.ui.shelf.BottomNavBar
import com.yuezhou.reader.ui.shelf.CloudBookDetailsSheet
import com.yuezhou.reader.ui.shelf.LocalBookDetailDialog
import com.yuezhou.reader.ui.shelf.ScanResultDialog
import com.yuezhou.reader.ui.shelf.formatSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    // 顶部操作提示自动消失，避免长期停留在界面上
    LaunchedEffect(status) {
        if (status != null) {
            delay(3000)
            status = null
        }
    }
    // 刷新触发计数：+1 触发重新加载书库
    var refreshKey by remember { mutableStateOf(0) }
    var loadedShelfVersion by remember { mutableStateOf(0) }
    // 仅用于进程重建恢复；运行期间的导航真源是 ShelfNavigator.committedKey。
    var restoredTab by rememberSaveable(stateSaver = TabSaver) { mutableStateOf(Tab.书架) }
    // 管理态（多选删除）：非空表示进入管理态，集合为已勾选的 bookId
    var selecting by remember { mutableStateOf<Set<String>?>(null) }
    var localBookDetail by remember { mutableStateOf<LibraryStore.Entry?>(null) }
    // 待确认删除的书（弹二次确认框用）；非空时展示确认弹窗
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }
    var shelfMenuOpen by remember { mutableStateOf(false) }
    var showHiddenBooks by remember { mutableStateOf(false) }
    var deletingLocalBooks by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val uiPrefs = remember(context) { LibraryUiPrefs(context) }
    // 本地书库展示模式：网格/列表，切换后立即持久化。
    var localGridMode by remember { mutableStateOf(uiPrefs.localGridMode) }

    val store = remember { LibraryStore(context) }
    var hiddenBooks by remember { mutableStateOf(store.hiddenBookIds()) }
    var localQuery by rememberSaveable { mutableStateOf("") }
    var readingFilter by rememberSaveable { mutableStateOf(ReadingFilter.ALL) }
    var librarySort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    val query = localQuery.trim()
    val searching = query.isNotEmpty()
    val shelfBooks = remember(books, hiddenBooks) { books.filter { it.bookId !in hiddenBooks } }
    val localBookIds = remember(books) { books.mapTo(mutableSetOf()) { it.bookId } }
    var shelfAuthors by remember(shelfBooks, loadedShelfVersion) { mutableStateOf<Map<String, List<String>>?>(null) }
    LaunchedEffect(shelfBooks, searching, loadedShelfVersion) {
        if (searching && shelfAuthors == null) shelfAuthors = store.shelfAuthors(shelfBooks)
    }
    val visibleBooks = remember(shelfBooks, query, readingFilter, librarySort, shelfAuthors) {
        localLibraryResults(shelfBooks, query, readingFilter, librarySort, shelfAuthors.orEmpty())
    }
    val removedBooks = books.filter { it.bookId in hiddenBooks }
    val cloudSync = remember { CloudSync(context) }
    // 在线书源状态和下载队列由 ViewModel 提供。
    val onlineVm: OnlineLibraryViewModel = viewModel()

    // 唯一的已提交导航状态：currentTab / 当前书源均由页面 key 派生。
    // 优先恢复上次落页的稳定 key；没有记录时再按一级栏目兜底。
    var sessionPage by rememberSaveable { mutableStateOf(ShelfPage.Reading.key) }
    val navigator = remember {
        ShelfNavigator(
            initialKey = sessionPage,
            providerIds = onlineVm.registry.enabled().map { it.id },
        )
    }
    val currentTab = navigator.tab
    val providerIds = onlineVm.registry.enabled().map { it.id }
    LaunchedEffect(providerIds) { navigator.syncPages(providerIds) }

    // 横滑闸门：页面内详情/筛选/输入聚焦或宿主浮层期间临时关闭手势（阶段 C）。
    val swipeBlocker = remember { SwipeBlockRegistry() }
    // 栏目横滑开关：设置页修改后立即生效并持久化。
    var tabSwipeEnabled by remember { mutableStateOf(uiPrefs.tabSwipeEnabled) }

    // Pager 只承载视觉与手势；committedKey 仍是唯一业务真源（方案 §2.1）。
    val pageKeys = navigator.pages.map { it.key }
    val pagerState = rememberPagerState(
        initialPage = navigator.indexOf(navigator.committedKey).coerceAtLeast(0),
        pageCount = { navigator.pages.size },
    )
    var shelfDragging by remember { mutableStateOf(false) }
    val pagerBusy = shelfDragging || pagerState.isScrollInProgress || navigator.isNavigating
    val motionEnabled = LocalAppMotionEnabled.current
    // 点击导航：相邻页用原生 Pager 动画；非相邻页直接定位，避免逐页展示中间栏目。
    LaunchedEffect(navigator.requestedKey, pageKeys) {
        // 手势拖动不产生请求；只有点击/返回等程序化导航才在这里滚动。
        val key = navigator.requestedKey ?: return@LaunchedEffect
        val targetIndex = pageKeys.indexOf(key)
        if (targetIndex < 0) {
            navigator.clearRequest(key)
            return@LaunchedEffect
        }
        val currentIndex = pagerState.currentPage.coerceIn(0, (pageKeys.size - 1).coerceAtLeast(0))
        val currentKey = pageKeys.getOrNull(currentIndex)
        try {
            if (motionEnabled && currentKey != null && abs(targetIndex - currentIndex) <= 1) {
                pagerState.animateScrollToPage(targetIndex)
            } else if (currentKey != key || pagerState.currentPageOffsetFraction != 0f) {
                // 非相邻或页面列表变化导致下标错位：直接定位，不展示中间页。
                pagerState.scrollToPage(targetIndex)
            }
            navigator.commit(key)
        } finally {
            // 动画被新的点击/手势取消时也要清理请求，避免 isNavigating 残留。
            navigator.clearRequest(key)
        }
    }

    // 书源增删导致下标错位时，按已提交 key 重新定位 Pager（不触发业务加载）。
    LaunchedEffect(pageKeys) {
        val targetIndex = pageKeys.indexOf(navigator.committedKey)
        if (targetIndex >= 0 && pageKeys.getOrNull(pagerState.currentPage) != navigator.committedKey) {
            pagerState.scrollToPage(targetIndex)
        }
    }

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
    var detailBook by remember { mutableStateOf<CloudSync.RemoteBook?>(null) }
    var detailProviderId by remember { mutableStateOf<String?>(null) }
    var cloudDownloadEnqueuing by remember { mutableStateOf(false) }
    val downloadTasks by onlineVm.downloadManager.tasks.collectAsStateWithLifecycle(initialValue = emptyList())
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
                        android.util.Log.w("MainActivity", "目录持久授权失败，仅本次可用", e)
                    }
                    val existingIds = store.availableBookIds()
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
        val snapshot = store.snapshot(includeCoverBytes = false)
        books = snapshot.books
        snapshot.error?.let { status = it }
        hiddenBooks = store.hiddenBookIds()
        loadedShelfVersion = refreshKey
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

    // 云端书库：committed 页落页时加载（书源状态由 ViewModel 缓存；显式刷新走 force）
    fun loadRemote(providerId: String, force: Boolean = false) {
        scope.launch { onlineVm.loadSelected(force = force, providerId = providerId) }
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
    fun refreshRemoteLibrary(providerId: String = onlineVm.selectedProviderId) {
        if (onlineVm.isCatalog(providerId)) {
            // 检索型书源无云端索引可同步，刷新即按当前条件重新搜索。
            scope.launch { onlineVm.refreshSelected(providerId) }
            return
        }
        if (uploadQueue.busy || remoteLoading) return
        scope.launch {
            val (_, message) = onlineVm.refreshSelected(providerId)
            if (!message.isNullOrBlank()) {
                status = message
                snackbarHostState.showSnackbar(message)
            }
        }
    }

    // 网页搜索：只传已配置服务器与当前输入关键词，登录沿用 WebView 自身会话。
    fun openZLibraryWeb(providerId: String = onlineVm.selectedProviderId) {
        val keyword = onlineVm.stateOf(providerId).keyword
        if (!ZLibraryWebEntry.launch(context, onlineVm.webBaseUrl(), keyword)) {
            scope.launch { snackbarHostState.showSnackbar("请先配置可用的 Z-Library 服务器") }
        }
    }

    // 只有 committed 页（真正落页后）才触发业务加载，动画期间不提前切书源。
    LaunchedEffect(navigator.committedKey) {
        // 进程重建时把当前页写回保存状态，保证下次恢复仍停在原位置。
        sessionPage = navigator.committedKey
        restoredTab = navigator.tab
        uiPrefs.lastSectionPage = navigator.committedKey
        uiPrefs.lastShelfKey = if (currentTab == Tab.发现) "cloud.zlibrary" else navigator.committedKey
        val active = navigator.providerId?.takeIf { it in providerIds }
        if ((navigator.tab == Tab.云端 || navigator.tab == Tab.发现) && active != null) {
            if (currentTab == Tab.云端) uiPrefs.lastCloudProvider = active
            if (active == onlineVm.selectedProviderId && currentTab == Tab.云端) {
                loadRemote(active)
            } else {
                // selectProvider 是 selectedProviderId 的唯一写入点，仅 committed 页驱动。
                onlineVm.selectProvider(active)
            }
        }
    }

    // 管理态下返回键退出管理态（而非退出 App）
    BackHandler(enabled = selecting != null) {
        selecting = null
    }

    // 二级设置页：以独立全屏页面呈现，隐藏外层顶栏、底部导航与悬浮按钮（方案 §3.1）。
    var settingsRoute by rememberSaveable { mutableStateOf(SettingsRoute.Home) }
    var cloudSetupProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudSetupReturn by rememberSaveable { mutableStateOf<String?>(null) }
    val fullscreenSubPage = currentTab == Tab.设置 && settingsRoute != SettingsRoute.Home

    // 系统返回键逐层退出二级页，恢复原有导航结构（方案 §3.1）。
    BackHandler(enabled = fullscreenSubPage) { settingsRoute = settingsRoute.parent }

    // 统一下载管理页：覆盖在主界面之上，返回键逐层退出。
    var showOnlineDownloads by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showOnlineDownloads) { showOnlineDownloads = false }

    // 宿主级浮层（全屏子页/下载管理/本地管理态）期间禁止横滑，避免与内部交互冲突。
    val hostSwipeBlocked = fullscreenSubPage || showOnlineDownloads || selecting != null || localBookDetail != null
    DisposableEffect(hostSwipeBlocked) {
        if (hostSwipeBlocked) swipeBlocker.hold("host:overlay")
        onDispose { if (hostSwipeBlocked) swipeBlocker.release("host:overlay") }
    }
    // 云端是一级页：无浮层时返回键先回到「书库」，不直接退出应用。
    BackHandler(enabled = (currentTab == Tab.云端 || currentTab == Tab.发现) && !fullscreenSubPage && !showOnlineDownloads) {
        navigator.navigateTo(ShelfPage.LocalShelf.key)
    }
    // 设置同样是一级页：无全屏子页时返回键回到「书库」。
    BackHandler(enabled = currentTab == Tab.设置 && !fullscreenSubPage && !showOnlineDownloads) {
        navigator.navigateTo(ShelfPage.LocalShelf.key)
    }
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
                        val allSelected = visibleBooks.isNotEmpty() && visibleBooks.all { it.bookId in selected }
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
                        LibraryPageHeading(
                            title = when (currentTab) {
                                Tab.阅读 -> "我的阅读"
                                Tab.书架 -> "书库"
                                Tab.云端 -> "云端"
                                Tab.发现 -> "发现"
                                Tab.设置 -> currentTab.label
                            },
                            supportingText = if (currentTab == Tab.书架) "本地书籍 · ${shelfBooks.size} 本" else null,
                        )
                    },
                    actions = {
                        // 页面切换期间顶栏业务操作统一禁用，避免用旧/新书源状态发起操作。
                        if (currentTab == Tab.书架 && removedBooks.isNotEmpty()) {
                            TextButton(enabled = !pagerBusy, onClick = { showHiddenBooks = true }) { Text("已移除") }
                        }
                        if (currentTab == Tab.书架 && selecting == null) {
                            IconButton(
                                enabled = !pagerBusy,
                                onClick = {
                                    localGridMode = !localGridMode
                                    uiPrefs.localGridMode = localGridMode
                                },
                            ) {
                                Icon(
                                    if (localGridMode) Icons.Filled.List else LibraryGridIcon,
                                    contentDescription = if (localGridMode) "列表" else "网格",
                                )
                            }
                        }
                        // 下载任务入口：书库与云端都保留；设置页不显示。
                        if (currentTab == Tab.书架 || currentTab == Tab.云端 || currentTab == Tab.发现) {
                            IconButton(enabled = !pagerBusy, onClick = { showOnlineDownloads = true }) {
                                AnimatedDownloadIcon(
                                    downloading = downloadTasks.any { it.status.active },
                                )
                            }
                        }
                        val cloudProviderId = navigator.providerId?.takeIf { it in providerIds }
                        if ((currentTab == Tab.云端 || currentTab == Tab.发现) && cloudProviderId != null) {
                            Box {
                                IconButton(
                                    enabled = !pagerBusy,
                                    onClick = {
                                        cloudMenuOpen = true
                                        if (onlineVm.isCatalog(cloudProviderId)) onlineVm.loadQuota(cloudProviderId)
                                    },
                                ) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = cloudMenuOpen,
                                    onDismissRequest = { cloudMenuOpen = false },
                                ) {
                                    val catalog = onlineVm.isCatalog(cloudProviderId)
                                    val cloudState = onlineVm.stateOf(cloudProviderId)
                                    val submitted = cloudState.submittedKeyword
                                    if (catalog) {
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text("Z-Library 账户")
                                                    Text(
                                                        cloudState.quotaText ?: "正在读取登录状态…",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = YuezhouColors.TextSecondary,
                                                    )
                                                    Text(
                                                        "点击刷新额度",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = YuezhouColors.Brand,
                                                    )
                                                }
                                            },
                                            onClick = { onlineVm.loadQuota(cloudProviderId) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("账户管理") },
                                            onClick = {
                                                cloudMenuOpen = false
                                                navigator.navigateTo(ShelfPage.Settings.key)
                                                settingsRoute = SettingsRoute.ZLibrary
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("浏览官方热门") },
                                            onClick = {
                                                cloudMenuOpen = false
                                                onlineVm.openPopular(cloudProviderId)
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(if (catalog) "刷新搜索结果" else "刷新云端书库") },
                                        // 检索书源必须已有提交词才可刷新，避免用输入框草稿触发请求。
                                        enabled = !cloudState.refreshing &&
                                            (!catalog || submitted.isNotBlank()),
                                        onClick = {
                                            cloudMenuOpen = false
                                            refreshRemoteLibrary(cloudProviderId)
                                        },
                                    )
                                    if (catalog) {
                                        DropdownMenuItem(
                                            text = { Text("网页搜索") },
                                            onClick = {
                                                cloudMenuOpen = false
                                                openZLibraryWeb(cloudProviderId)
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
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "导入书籍")
                }
            }
        },
        bottomBar = {
            if (!fullscreenSubPage) {
                BottomNavBar(
                    current = currentTab,
                    onSelect = { tab ->
                        navigator.navigateTo(
                            when (tab) {
                                Tab.阅读 -> ShelfPage.Reading.key
                                Tab.书架 -> ShelfPage.LocalShelf.key
                                Tab.云端 -> navigator.cloudKey(uiPrefs.lastCloudProvider ?: onlineVm.selectedProviderId)
                                Tab.发现 -> ShelfPage.Discover.key
                                Tab.设置 -> ShelfPage.Settings.key
                            },
                        )
                    },
                )
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

            // 书源索引只在页面列表变化时重建，连续滑动位置由二级栏读取。
            val pages = navigator.pages
            val cloudPageIndices = remember(pages) {
                buildMap {
                    pages.forEachIndexed { index, page ->
                        if (page is ShelfPage.Cloud) put(page.providerId, index)
                    }
                }
            }
            CloudSourceTabs(
                providers = onlineVm.registry.enabled().filter { it.id in cloudPageIndices },
                pageIndices = cloudPageIndices,
                pagerState = pagerState,
                motionEnabled = motionEnabled,
                onSelect = { providerId ->
                    if (!pagerBusy) navigator.navigateTo("cloud.$providerId")
                },
            )

            // 同一个 Pager 承载一级和二级内容，每次手势只预览起点的相邻页。
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .shelfSwipe(
                        enabled = tabSwipeEnabled && !swipeBlocker.blocked && !navigator.isNavigating,
                        pager = pagerState,
                        layoutDirection = androidx.compose.ui.platform.LocalLayoutDirection.current,
                        pageKeys = pageKeys,
                        committedKey = { navigator.committedKey },
                        onDragging = { shelfDragging = it },
                        onSettle = navigator::navigateTo,
                    ),
                userScrollEnabled = false,
                beyondViewportPageCount = 0,
                key = { index -> pageKeys.getOrNull(index) ?: index },
            ) { index ->
                when (val page = navigator.pages.getOrNull(index)) {
                    ShelfPage.Reading -> ReadingHome(books, store, loadedShelfVersion,
                        onLibrary = { navigator.navigateTo(ShelfPage.LocalShelf.key) })
                    ShelfPage.LocalShelf -> Column(Modifier.fillMaxSize()) {
                        if (selecting == null) {
                            LocalLibraryFilters(
                                query = localQuery,
                                onQueryChange = { localQuery = it },
                                filter = readingFilter,
                                onFilterChange = { readingFilter = it },
                                sort = librarySort,
                                onSortChange = { librarySort = it },
                                swipeBlocker = swipeBlocker,
                            )
                        }
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
                            query = query,
                            readingFilter = readingFilter,
                            librarySort = librarySort,
                            authorSearchLoading = searching && shelfAuthors == null,
                            authors = shelfAuthors.orEmpty(),
                            lookupAuthor = { book -> store.authorsFor(book).takeIf { it.isNotEmpty() }?.joinToString("、") },
                            authorRefreshKey = loadedShelfVersion,
                            gridMode = localGridMode,
                            selecting = selecting,
                            navigationLocked = pagerBusy,
                            onImport = { if (!scanning && !importing) importOptionsOpen = true },
                            onEnterSelect = { selecting = setOf(it.bookId) },
                            onToggleSelect = { id ->
                                val cur = selecting ?: emptySet()
                                selecting = if (id in cur) cur - id else cur + id
                            },
                        )
                    }
                    is ShelfPage.Cloud, ShelfPage.CloudEmpty, ShelfPage.Discover -> {
                        val provider = when (page) {
                            is ShelfPage.Cloud -> page.providerId
                            ShelfPage.Discover -> ShelfPage.Discover.PROVIDER_ID.takeIf { it in providerIds }
                            else -> null
                        }
                        OnlineLibraryScreen(
                            vm = onlineVm,
                            providerId = provider,
                            interactionsEnabled = !pagerBusy && page.key == navigator.committedKey,
                            swipeBlocker = swipeBlocker,
                            localBookIds = localBookIds,
                            onRefresh = {
                                if (!pagerBusy && provider != null) refreshRemoteLibrary(provider)
                            },
                            onOpenDetail = {
                                if (!pagerBusy && provider != null) {
                                    detailProviderId = provider
                                    detailBook = it
                                }
                            },
                            onOpenDownloads = { showOnlineDownloads = true },
                            onOpenSettings = {
                                if (page is ShelfPage.Cloud) {
                                    cloudSetupProvider = page.providerId
                                    cloudSetupReturn = page.providerId
                                }
                                navigator.navigateTo(ShelfPage.Settings.key)
                                settingsRoute = if (page == ShelfPage.Discover) SettingsRoute.ZLibrary else SettingsRoute.Sources
                            },
                        )
                    }
                    ShelfPage.Settings -> SettingsPage(
                        cloudSync = cloudSync,
                        uploadQueue = uploadQueue,
                        cloudLoading = remoteLoading,
                        tabSwipeEnabled = tabSwipeEnabled,
                        onTabSwipeChange = {
                            tabSwipeEnabled = it
                            uiPrefs.tabSwipeEnabled = it
                        },
                        route = settingsRoute,
                        onNavigate = { route ->
                            settingsRoute = route
                            if (route == SettingsRoute.Home) cloudSetupReturn = null
                        },
                        localGridMode = localGridMode,
                        onLocalGridMode = {
                            localGridMode = it
                            uiPrefs.localGridMode = it
                        },
                        onOpenDownloads = { showOnlineDownloads = true },
                        cloudSetupProvider = cloudSetupProvider,
                        onCloudSetupHandled = { cloudSetupProvider = null },
                        onCloudConfigured = { provider ->
                            if (cloudSetupReturn == provider) {
                                cloudSetupReturn = null
                                settingsRoute = SettingsRoute.Home
                                navigator.navigateTo("cloud.$provider")
                                loadRemote(provider, force = true)
                            }
                        },
                    )
                    null -> Unit
                }
            }
        }
    }

    // 统一下载管理。
    if (showOnlineDownloads) {
        DownloadManagerScreen(
            vm = onlineVm,
            onBack = { showOnlineDownloads = false },
            onOpenBook = { bookId ->
                val location = store.bookLocation(bookId)
                val available = location != null && withContext(Dispatchers.IO) {
                    location.uri != null || EpubImporter.epubDir(context).resolve(location.fileName).isFile
                }
                if (available) {
                    context.startActivity(
                        Intent(context, ReaderActivity::class.java).apply {
                            putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                        },
                    )
                }
                available
            },
        )
    }

    localBookDetail?.let { book ->
        LocalBookDetailDialog(
            book = book,
            store = store,
            onDismiss = { localBookDetail = null },
            onEnterSelect = {
                localBookDetail = null
                selecting = setOf(book.bookId)
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
    val detail = detailBook?.let { book ->
        if (book.bookId in localBookIds && !book.local) book.copy(local = true) else book
    }
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
                                val result = cloudSync.deleteRemoteBook(detail.bookId, forced = detailProviderId?.let(onlineVm::backendKindOf))
                                confirmCloudDelete = false
                                if (result.success) {
                                    detailBook = null
                                    remoteBooks = remoteBooks.filterNot { it.bookId == detail.bookId }
                                    loadRemote(onlineVm.selectedProviderId, force = true)
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
        val providerId = detailProviderId
        val taskRunning = cloudDownloadEnqueuing || downloadTasks.any {
            it.providerId == providerId && it.remoteId == detail.bookId && it.status.active
        }
        CloudBookDetailsSheet(
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
                    if (providerId == null) return@TextButton
                    if (Build.VERSION.SDK_INT >= 33 &&
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    cloudDownloadEnqueuing = true
                    scope.launch {
                        try {
                            onlineVm.enqueueCloudDownload(detail, providerId)
                            detailBook = null
                            showOnlineDownloads = true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            detailBook = null
                            snackbarHostState.showSnackbar("无法创建下载任务：${e.message ?: "请重试"}")
                        } finally {
                            cloudDownloadEnqueuing = false
                        }
                    }
                }) { Text(if (taskRunning) "下载中…" else if (detail.local) "重新下载" else "下载") }
            },
            dismissButton = {
                TextButton(onClick = { detailBook = null }) { Text("关闭") }
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
                            val ids = store.availableBookIds()
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
