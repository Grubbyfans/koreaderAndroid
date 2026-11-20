package com.yuezhou.reader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.EpubSpeechChapterSource
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import com.yuezhou.reader.reader.TocItem
import com.yuezhou.reader.core.speech.SentencePlanner
import com.yuezhou.reader.core.speech.SourceAnchor
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechTextUnit
import com.yuezhou.reader.speech.SpeechConfig
import com.yuezhou.reader.speech.SpeechPlanStore
import com.yuezhou.reader.speech.SpeechProgressTracker
import com.yuezhou.reader.speech.SpeechVoices
import com.yuezhou.reader.speech.TtsEngine
import com.yuezhou.reader.speech.TtsVoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 沉浸式阅读界面：点击正文呼出/收起面板。
 * 功能：目录(TOC)、进度保存/恢复（去抖+退出即存）、字号/主题/翻页模式设置(DataStore)。
 */
class ReaderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
        private val openReaders = java.util.concurrent.ConcurrentHashMap<ReaderActivity, String>()

        fun isBookOpen(bookId: String): Boolean = openReaders.containsValue(bookId)
    }

    private var onEpubReady: ((Uri) -> Unit)? = null
    private var pickEpubLauncher: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideNavigationBar()
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (bookId != null) openReaders[this] = bookId
        val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onEpubReady?.invoke(uri)
        }
        pickEpubLauncher = { launcher.launch(arrayOf("application/epub+zip")) }
        setContent {
            YuezhouTheme {
                ReaderScreen(
                    bookId = bookId,
                    onEpubReadySetter = { onEpubReady = it },
                    onImport = { pickEpubLauncher?.invoke() },
                    onBack = { finish() },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    private fun hideNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    override fun onDestroy() {
        openReaders.remove(this)
        super.onDestroy()
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    bookId: String?,
    onEpubReadySetter: ((Uri) -> Unit) -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var title by remember { mutableStateOf(bookId ?: "阅读") }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var currentCfi by remember { mutableStateOf<String?>(null) }
    var currentFraction by remember { mutableStateOf<Float?>(null) }
    var currentIndex by remember { mutableStateOf<Int?>(null) }
    var totalSections by remember { mutableStateOf(0) }
    var tocItems by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    // 待打开的书：WebView 尚未创建时先暂存
    var pendingOpen by remember { mutableStateOf<Triple<String, String?, Uri?>?>(null) }
    var openedSource by remember { mutableStateOf<Pair<String, Uri?>?>(null) }
    // 沉浸式面板显隐
    var panelVisible by remember { mutableStateOf(false) }
    // 朗读状态：是否正在朗读
    var speaking by remember { mutableStateOf(false) }
    // 朗读中，用于「朗读」按钮切换为「停止」
    var speakRequested by remember { mutableStateOf(false) }
    // 目录抽屉
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val store = remember { LibraryStore(context) }
    // 阅读设置（DataStore）
    val loadedPrefs by ReaderSettings.flow(context).collectAsStateWithLifecycle(initialValue = null)
    val readerPrefs = loadedPrefs ?: ReaderPreferences()
    // 当前 TTS 引擎可用音色（服务就绪后发布）
    val availableVoices by SpeechVoices.voices.collectAsStateWithLifecycle()

    // 进度去抖保存：翻页频繁时合并写入
    var saveJob by remember { mutableStateOf<Job?>(null) }
    var leaving by remember { mutableStateOf(false) }
    // 记录最近一次位置，供退出时立即保存
    var lastPosition by remember { mutableStateOf<Pair<String, Double?>?>(null) }

    // 进度同步面板：非空表示打开；数据为进度对照信息
    var syncDialogOpen by remember { mutableStateOf(false) }
    var syncInfo by remember { mutableStateOf<CloudSync.ProgressInfo?>(null) }
    var syncLoading by remember { mutableStateOf(false) }
    var syncBusy by remember { mutableStateOf(false) }
    var undoPosition by remember(bookId) { mutableStateOf<Pair<String, Double?>?>(null) }
    var pendingJump by remember { mutableStateOf<Pair<String, kotlinx.coroutines.CompletableDeferred<Boolean>>?>(null) }

    suspend fun flushProgress() {
        saveJob?.cancelAndJoin()
        val pos = lastPosition ?: return
        val id = bookId ?: return
        store.saveProgress(id, pos.first, pos.second)
    }

    fun leaveReader() {
        if (leaving) return
        leaving = true
        scope.launch {
            flushProgress()
            onBack()
        }
    }

    // 打开进度同步面板：读本机 + 云端进度对照
    fun openSyncDialog() {
        val id = bookId ?: return
        scope.launch {
            syncLoading = true
            syncDialogOpen = true
            syncInfo = null
            // 打开前先 flush 当前进度，保证「本机位置」是最新的
            flushProgress()
            syncInfo = CloudSync(context).progressInfo(id)
            syncLoading = false
        }
    }

    suspend fun jumpAndSave(
        id: String,
        wv: ReaderWebView,
        previous: Pair<String, Double?>,
        target: Pair<String, Double?>,
    ): Boolean {
        val requestId = java.util.UUID.randomUUID().toString()
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        saveJob?.cancel()
        pendingJump = requestId to result
        try {
            wv.verifyJump(requestId, target.first, previous.first)
            val verified = kotlinx.coroutines.withTimeoutOrNull(8_000) { result.await() }
            if (verified == null) wv.goToCfi(previous.first)
            if (verified != true) return false
            store.saveProgress(id, target.first, target.second)
            lastPosition = target
            return true
        } finally {
            pendingJump = null
        }
    }

    fun applyRemoteProgress() {
        val id = bookId ?: return
        val info = syncInfo ?: return
        val remote = info.remoteLocation ?: return
        val previous = lastPosition ?: return
        val wv = webView ?: return
        syncBusy = true
        SpeechPlanStore.stop(context)
        scope.launch {
            try {
                val cfi = wv.xPointerToCfi(remote)
                val applied = cfi != null && jumpAndSave(id, wv, previous, cfi to info.remotePercent)
                if (applied) undoPosition = previous
                status = if (applied) "已应用云端进度，可在进度同步中撤销" else "无法确认云端落点，已保留本机进度"
                syncDialogOpen = false
            } finally {
                syncBusy = false
            }
        }
    }

    fun undoRemoteProgress() {
        val id = bookId ?: return
        val previous = lastPosition ?: return
        val restore = undoPosition ?: return
        val wv = webView ?: return
        syncBusy = true
        SpeechPlanStore.stop(context)
        scope.launch {
            try {
                val restored = jumpAndSave(id, wv, previous, restore)
                if (restored) undoPosition = null
                status = if (restored) "已恢复到同步前位置" else "无法确认撤销位置"
                syncDialogOpen = false
            } finally {
                syncBusy = false
            }
        }
    }

    // 上传本机进度到云端：先把 CFI 转 XPointer 再上传
    fun uploadLocalProgress() {
        val id = bookId ?: return
        val pos = lastPosition
        if (pos == null) {
            status = "本机暂无进度可上传"
            return
        }
        val cfi = pos.first
        val wv = webView
        if (wv == null) {
            status = "阅读器尚未就绪，无法上传进度"
            return
        }
        syncBusy = true
        scope.launch {
            try {
                val xpointer = wv.cfiToXPointer(cfi)
                status = if (xpointer == null) {
                    "当前阅读位置无法转换，已取消上传"
                } else {
                    CloudSync(context).uploadLocalProgress(id, xpointer, pos.second) ?: "已上传本机进度"
                }
                syncDialogOpen = false
            } finally {
                syncBusy = false
            }
        }
    }

    // 朗读进度收集：服务写入 SpeechProgressTracker，这里反映到 UI（朗读中状态 + 高亮回写）
    LaunchedEffect(Unit) {
        SpeechProgressTracker.current.collect { snap ->
            speaking = snap != null
            // 朗读停止时同步清空「朗读请求中」标记
            if (snap == null) speakRequested = false
            // 已确认朗读点 → 回写高亮到 WebView（CFI + 节点内偏移）
            val anchor = snap?.sourceAnchor
            if (snap != null && snap.bookId == bookId && anchor?.nodeId?.startsWith("epubcfi(") == true) {
                webView?.highlight(anchor.nodeId, anchor.offset)
            }
        }
    }

    // 面板打开且音色列表为空时，一次性查询系统 TTS 音色（供朗读前选择男声/女声）
    LaunchedEffect(panelVisible, availableVoices.isEmpty()) {
        if (panelVisible && availableVoices.isEmpty()) {
            TtsEngine.queryVoicesOnce(context) { voices ->
                SpeechVoices.publish(voices)
            }
        }
    }

    // 从章节文本节点构造朗读计划并启动前台朗读服务。
    fun startSpeech(nodes: List<com.yuezhou.reader.reader.ChapterTextNode>, chapterIndex: Int) {
        val id = bookId ?: return
        val (fileName, source) = openedSource ?: return
        // 文本节点 → SpeechTextUnit：用 CFI 字符串作为 nodeId（跨章节唯一，映射回原文锚点）
        val units = nodes.map { node ->
            SpeechTextUnit(text = node.text, anchor = SourceAnchor(nodeId = node.cfi, offset = 0))
        }
        val segments = SentencePlanner().plan(units)
        if (segments.isEmpty()) {
            status = "本章无可朗读文本"
            return
        }
        // fileGeneration 暂用空字符串（本地库无代次概念，见方案 §7.2 的 fileGeneration 语义）
        val plan = SpeechPlan(
            bookId = id,
            fileGeneration = "",
            chapterIndex = chapterIndex,
            segments = segments,
        )
        try {
            // 注入朗读配置（语速 + 音色），服务在开始朗读时应用
            SpeechPlanStore.putConfig(
                SpeechConfig(rate = readerPrefs.speechRate, voiceName = readerPrefs.speechVoice)
            )
            SpeechPlanStore.startAndPlay(context, plan, EpubSpeechChapterSource(context, fileName, source))
            speakRequested = true
            status = "已开始朗读"
        } catch (e: Exception) {
            status = "启动朗读失败：${e.message}"
        }
    }

    // 生命周期：切后台/退出时立即保存进度
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !leaving) {
                scope.launch { flushProgress() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 事件监听
    val eventListener: (ReaderEvent) -> Unit = { event ->
        when (event) {
            is ReaderEvent.BookOpened -> {
                title = event.title.ifBlank { bookId ?: "阅读" }
                totalSections = event.sections
                loading = false
                status = null
                // 打开后拉取目录
                webView?.requestToc()
            }
            is ReaderEvent.JumpVerified -> {
                pendingJump?.takeIf { it.first == event.requestId }?.second?.complete(event.verified)
            }
            is ReaderEvent.Relocated -> {
                currentCfi = event.cfi
                currentFraction = event.fraction
                currentIndex = event.index
                val cfi = event.cfi
                val fraction = event.fraction
                if (cfi != null && bookId != null && pendingJump == null && !leaving) {
                    lastPosition = cfi to fraction?.toDouble()
                    // 去抖：300ms 内的连续翻页合并成一次写
                    saveJob?.cancel()
                    saveJob = scope.launch {
                        delay(300)
                        store.saveProgress(bookId, cfi, fraction?.toDouble())
                    }
                }
            }
            is ReaderEvent.TocLoaded -> {
                tocItems = event.items
            }
            is ReaderEvent.ChapterText -> {
                speakRequested = false
                val chapterIndex = event.index
                if (event.items.isEmpty() || chapterIndex == null) {
                    status = "本章无可朗读文本"
                } else {
                    startSpeech(event.items, chapterIndex)
                }
            }
            is ReaderEvent.Error -> {
                loading = false
                status = "错误：${event.message}"
            }
        }
    }

    fun openBookFile(fileName: String, restoreCfi: String? = null, source: Uri? = null) {
        pendingOpen = Triple(fileName, restoreCfi, source)
    }

    // 初始化：有 bookId 则从书库打开
    LaunchedEffect(bookId) {
        if (bookId != null) {
            loading = true
            try {
                val source = store.bookLocation(bookId)
                val restoreCfi = store.savedLocation(bookId)
                openBookFile(source?.fileName ?: "$bookId.epub", restoreCfi, source?.uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                status = "无法读取书籍位置：${e.message}"
            }
        } else {
            loading = false
        }
    }

    // SAF 选书回调
    LaunchedEffect(Unit) {
        onEpubReadySetter { uri ->
            scope.launch {
                loading = true
                status = null
                val result = EpubImporter.import(context, uri)
                result.fold(
                    onSuccess = { file ->
                        openBookFile(file.name, null)
                        status = "已导入：${file.nameWithoutExtension}"
                    },
                    onFailure = { e ->
                        loading = false
                        status = "导入失败：${e.message}"
                    },
                )
            }
        }
    }

    // Open with the saved layout already applied so restoring the CFI uses the final pagination.
    LaunchedEffect(webView, loadedPrefs, pendingOpen) {
        val wv = webView ?: return@LaunchedEffect
        val prefs = loadedPrefs ?: return@LaunchedEffect
        wv.verticalMode = prefs.vertical
        wv.applySettings(
            fontSize = prefs.fontSize,
            lineHeight = prefs.lineHeight,
            pageMargin = prefs.pageMargin,
            theme = prefs.theme,
            vertical = prefs.vertical,
            fontId = prefs.fontId,
            fontWeight = prefs.fontWeight,
        )
        pendingOpen?.let { (fileName, restoreCfi, source) ->
            pendingOpen = null
            openedSource = fileName to source
            wv.openEpub(fileName, restoreCfi, source)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false, // 禁用边缘滑动手势，避免与翻页手势冲突；目录仅通过按钮打开
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                TocDrawer(
                    title = title,
                    items = tocItems,
                    onSelect = { item ->
                        scope.launch { drawerState.close() }
                        webView?.goToToc(item.href)
                    },
                )
            }
        },
    ) {
        // 返回键：优先关抽屉 → 关面板 → 退出
        BackHandler {
            if (drawerState.isOpen) {
                scope.launch { drawerState.close() }
            } else if (panelVisible) {
                panelVisible = false
            } else {
                leaveReader()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(themeBackground(readerPrefs.theme)),
        ) {
            // 正文 WebView（铺满，单击呼出面板）
            AndroidView(
                factory = { ctx ->
                    ReaderWebView(ctx).also { wv ->
                        wv.eventListener = eventListener
                        wv.onTap = { panelVisible = !panelVisible }
                        wv.onPageTap = { dir ->
                            if (dir > 0) wv.nextPage() else wv.prevPage()
                        }
                        wv.verticalMode = readerPrefs.vertical
                        webView = wv
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 加载中 / 状态
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            status?.let {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // 沉浸式面板：顶部（返回+书名+章节）+ 底部（目录/进度/设置）
            if (panelVisible) {
                ReaderPanel(
                    title = title,
                    fraction = currentFraction,
                    sectionIndex = currentIndex,
                    totalSections = totalSections,
                    prefs = readerPrefs,
                    voices = availableVoices,
                    speaking = speaking || speakRequested,
                    onBack = { leaveReader() },
                    onOpenToc = { scope.launch { drawerState.open() } },
                    onImport = onImport,
                    onFractionSeek = { frac -> webView?.goToFraction(frac) },
                    onPrefsChange = { p -> scope.launch { ReaderSettings.save(context, p) } },
                    onSync = { openSyncDialog() },
                    onSpeak = {
                        if (speaking) {
                            // 朗读中 → 停止
                            SpeechPlanStore.stop(context)
                            status = "已停止朗读"
                        } else {
                            // 请求当前章节文本（从当前阅读位置开始），ChapterText 事件回传后启动朗读
                            speakRequested = true
                            status = "准备朗读…"
                            webView?.requestChapterText(currentCfi)
                        }
                    },
                )
            }
        }

        // 进度同步面板（方案 §5.6：本机/云端对照 + 上传/应用两步交互）
        if (syncDialogOpen) {
            ProgressSyncDialog(
                info = syncInfo,
                loading = syncLoading,
                busy = syncBusy,
                onUpload = { uploadLocalProgress() },
                onApply = { applyRemoteProgress() },
                onUndo = if (undoPosition != null) ::undoRemoteProgress else null,
                onDismiss = { syncDialogOpen = false },
            )
        }
    }
}

/** 进度同步对话框：展示后端、本机/云端进度、设备与时间，提供上传/应用两步操作。 */
@Composable
private fun ProgressSyncDialog(
    info: CloudSync.ProgressInfo?,
    loading: Boolean,
    busy: Boolean,
    onUpload: () -> Unit,
    onApply: () -> Unit,
    onUndo: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("进度同步") },
        text = {
            Column {
                if (loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("读取进度中…", style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (info == null) {
                    Text("无法读取进度信息", style = MaterialTheme.typography.bodyMedium)
                } else {
                    info.backendLabel?.let {
                        Text("后端：$it", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        "本机进度：${formatPercent(info.localPercent)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "云端进度：${formatPercent(info.remotePercent)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    info.remoteUpdatedAt?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "云端更新：${formatTime(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    info.remoteDeviceId?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "云端设备：${it.take(8)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请选择操作，不会按百分比自动覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (onUndo != null) {
                TextButton(onClick = onUndo, enabled = !busy && !loading) { Text("撤销上次应用") }
            }
            TextButton(
                onClick = onApply,
                enabled = !busy && !loading && info?.remoteLocation != null,
            ) { Text("应用云端进度") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onUpload,
                    enabled = !busy && !loading && info?.localLocation != null,
                ) { Text("上传本机进度") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
        },
    )
}

private fun formatPercent(p: Double?): String =
    if (p == null) "无" else "${(p * 100).toInt()}%"

private fun formatTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** 主题背景色（与 bridge.js 主题一致）。 */
private fun themeBackground(theme: String): Color = when (theme) {
    "sepia" -> Color(0xFFF5ECD7)
    "dark" -> Color(0xFF1A1A1A)
    else -> Color(0xFFFAF9F7)
}

@Composable
private fun ReaderPanel(
    title: String,
    fraction: Float?,
    sectionIndex: Int?,
    totalSections: Int,
    prefs: ReaderPreferences,
    voices: List<TtsVoice>,
    speaking: Boolean,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onImport: () -> Unit,
    onFractionSeek: (Float) -> Unit,
    onPrefsChange: (ReaderPreferences) -> Unit,
    onSync: () -> Unit,
    onSpeak: () -> Unit,
) {
    // 设置区折叠状态
    var settingsExpanded by remember { mutableStateOf(false) }
    // 进度滑块拖动中的本地预览值；null 表示未在拖动（跟随真实 fraction）
    var fractionPreview by remember { mutableStateOf<Float?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (totalSections > 0) {
                        Text(
                            "${(sectionIndex ?: 0) + 1} / $totalSections 章",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onImport) { Text("导入") }
            }
        }

        Spacer(Modifier.weight(1f))

        // 底部栏：进度 + 操作
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                // 进度
                if (fraction != null) {
                    val shownFraction = fractionPreview ?: fraction
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "进度 ${(shownFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Slider(
                            value = shownFraction,
                            onValueChange = { fractionPreview = it },
                            onValueChangeFinished = {
                                // 松手才真正跳转，避免拖动中连续重排全文
                                val target = fractionPreview
                                if (target != null) {
                                    onFractionSeek(target)
                                    fractionPreview = null
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // 底部操作：目录 + 朗读 + 同步 + 设置（设置默认收起）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    PanelAction("目录", onClick = onOpenToc)
                    PanelAction(if (speaking) "停止朗读" else "朗读", onClick = onSpeak)
                    PanelAction("同步", onClick = onSync)
                    PanelAction(
                        if (settingsExpanded) "收起设置" else "设置",
                        onClick = { settingsExpanded = !settingsExpanded },
                    )
                }
                // 设置区（默认收起，点击「设置」展开）
                if (settingsExpanded) {
                    SettingsControls(prefs = prefs, voices = voices, onPrefsChange = onPrefsChange)
                }
            }
        }
    }
}

@Composable
private fun PanelAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun SettingsControls(
    prefs: ReaderPreferences,
    voices: List<TtsVoice>,
    onPrefsChange: (ReaderPreferences) -> Unit,
) {
    // 拖动中的本地预览值；null 表示未拖动（跟随真实 prefs），松手才提交，避免连续写 DataStore + 重排全文
    var fontSizePreview by remember { mutableStateOf<Int?>(null) }
    var lineHeightPreview by remember { mutableStateOf<Double?>(null) }
    // 语速预览值；null 表示未拖动
    var ratePreview by remember { mutableStateOf<Float?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        ReaderFontPreference(prefs.fontId, prefs.fontWeight)
        // 字号
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                "${fontSizePreview ?: prefs.fontSize}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = (fontSizePreview ?: prefs.fontSize).toFloat(),
                onValueChange = { fontSizePreview = it.toInt() },
                onValueChangeFinished = {
                    val v = fontSizePreview
                    if (v != null) {
                        onPrefsChange(prefs.copy(fontSize = v))
                        fontSizePreview = null
                    }
                },
                valueRange = 12f..30f,
                modifier = Modifier.weight(1f),
            )
        }
        // 行距
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                String.format("%.1f", lineHeightPreview ?: prefs.lineHeight),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = (lineHeightPreview ?: prefs.lineHeight).toFloat(),
                onValueChange = { lineHeightPreview = it.toDouble() },
                onValueChangeFinished = {
                    val v = lineHeightPreview
                    if (v != null) {
                        onPrefsChange(prefs.copy(lineHeight = v))
                        lineHeightPreview = null
                    }
                },
                valueRange = 1.2f..2.5f,
                modifier = Modifier.weight(1f),
            )
        }
        // 主题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("主题", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            listOf(
                "light" to "浅色",
                "sepia" to "米黄",
                "dark" to "深色",
            ).forEach { (value, label) ->
                TextButton(onClick = { onPrefsChange(prefs.copy(theme = value)) }) {
                    Text(
                        label,
                        color = if (prefs.theme == value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (prefs.theme == value) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        // 翻页模式
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("翻页", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            TextButton(onClick = { onPrefsChange(prefs.copy(vertical = false)) }) {
                Text(
                    "左右",
                    color = if (!prefs.vertical) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (!prefs.vertical) FontWeight.Bold else FontWeight.Normal,
                )
            }
            TextButton(onClick = { onPrefsChange(prefs.copy(vertical = true)) }) {
                Text(
                    "上下",
                    color = if (prefs.vertical) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (prefs.vertical) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
        // 朗读语速
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("语速", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                String.format("%.1fx", ratePreview ?: prefs.speechRate),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = (ratePreview ?: prefs.speechRate).coerceIn(0.5f, 2.0f),
                onValueChange = { ratePreview = it },
                onValueChangeFinished = {
                    val v = ratePreview
                    if (v != null) {
                        onPrefsChange(prefs.copy(speechRate = v))
                        ratePreview = null
                    }
                },
                valueRange = 0.5f..2.0f,
                modifier = Modifier.weight(1f),
            )
        }
        // 朗读音色
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("音色", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                "默认",
                style = MaterialTheme.typography.bodySmall,
                color = if (prefs.speechVoice.isBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (prefs.speechVoice.isBlank()) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable { onPrefsChange(prefs.copy(speechVoice = "")) },
            )
        }
        // 音色列表（横向滚动，便于选择男声/女声等）
        if (voices.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(voices) { voice ->
                    val selected = prefs.speechVoice == voice.name
                    val genderTag = when (voice.gender) {
                        TtsVoice.Gender.MALE -> "男声"
                        TtsVoice.Gender.FEMALE -> "女声"
                        else -> null
                    }
                    val label = buildString {
                        append(voice.displayName)
                        if (genderTag != null) append("·$genderTag")
                        if (!voice.isOfflineCapable) append("(需联网)")
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .clickable { onPrefsChange(prefs.copy(speechVoice = voice.name)) },
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TocDrawer(
    title: String,
    items: List<TocItem>,
    onSelect: (TocItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        if (items.isEmpty()) {
            Text(
                "本书无目录",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(items) { item ->
                    TocRow(item = item, depth = 0, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun TocRow(item: TocItem, depth: Int, onSelect: (TocItem) -> Unit) {
    Column {
        Text(
            text = item.label,
            style = if (depth == 0) MaterialTheme.typography.bodyLarge
            else MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(item) }
                .padding(start = (16 + depth * 16).dp, top = 12.dp, bottom = 12.dp, end = 16.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        item.subitems.forEach { child ->
            TocRow(item = child, depth = depth + 1, onSelect = onSelect)
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}
