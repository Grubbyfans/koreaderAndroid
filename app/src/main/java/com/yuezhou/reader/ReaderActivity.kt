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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import com.yuezhou.reader.reader.TocItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 沉浸式阅读界面：点击正文呼出/收起面板。
 * 功能：目录(TOC)、进度保存/恢复（去抖+退出即存）、字号/主题/翻页模式设置(DataStore)。
 */
class ReaderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }

    private var onEpubReady: ((Uri) -> Unit)? = null
    private var pickEpubLauncher: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onEpubReady?.invoke(uri)
        }
        pickEpubLauncher = { launcher.launch(arrayOf("application/epub+zip")) }
        setContent {
            MaterialTheme {
                ReaderScreen(
                    bookId = bookId,
                    onEpubReadySetter = { onEpubReady = it },
                    onImport = { pickEpubLauncher?.invoke() },
                    onBack = { finish() },
                )
            }
        }
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
    var pendingOpen by remember { mutableStateOf<Pair<String, String?>?>(null) }
    // 沉浸式面板显隐
    var panelVisible by remember { mutableStateOf(false) }
    // 目录抽屉
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val store = remember { LibraryStore(context) }
    // 阅读设置（DataStore）
    val readerPrefs by ReaderSettings.flow(context).collectAsStateWithLifecycle(initialValue = ReaderPreferences())

    // 进度去抖保存：翻页频繁时合并写入
    var saveJob by remember { mutableStateOf<Job?>(null) }
    // 记录最近一次位置，供退出时立即保存
    var lastPosition by remember { mutableStateOf<Pair<String, Double?>?>(null) }

    fun flushProgress() {
        val pos = lastPosition
        val id = bookId
        if (pos != null && id != null) {
            scope.launch { store.saveProgress(id, pos.first, pos.second) }
        }
    }

    // 生命周期：切后台/退出时立即保存进度
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_PAUSE) {
                flushProgress()
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
            is ReaderEvent.Relocated -> {
                currentCfi = event.cfi
                currentFraction = event.fraction
                currentIndex = event.index
                val cfi = event.cfi
                val fraction = event.fraction
                if (cfi != null && bookId != null) {
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
            is ReaderEvent.Error -> {
                loading = false
                status = "错误：${event.message}"
            }
        }
    }

    fun openBookFile(fileName: String, restoreCfi: String? = null) {
        val wv = webView
        if (wv != null) wv.openEpub(fileName, restoreCfi) else pendingOpen = fileName to restoreCfi
    }

    // 初始化：有 bookId 则从书库打开
    LaunchedEffect(bookId) {
        if (bookId != null) {
            loading = true
            val fileName = store.fileNameOf(bookId)
            val restoreCfi = store.savedLocation(bookId)
            openBookFile(fileName, restoreCfi)
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

    // 排版设置变化时应用（打开书后生效）
    LaunchedEffect(readerPrefs) {
        webView?.applySettings(
            fontSize = readerPrefs.fontSize,
            lineHeight = readerPrefs.lineHeight,
            pageMargin = readerPrefs.pageMargin,
            theme = readerPrefs.theme,
            vertical = readerPrefs.vertical,
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
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
        BackHandler(enabled = drawerState.isOpen || panelVisible) {
            if (drawerState.isOpen) {
                scope.launch { drawerState.close() }
            } else if (panelVisible) {
                panelVisible = false
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
                        webView = wv
                        pendingOpen?.let { (name, cfi) ->
                            pendingOpen = null
                            wv.openEpub(name, cfi)
                        }
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
                    onBack = onBack,
                    onOpenToc = { scope.launch { drawerState.open() } },
                    onImport = onImport,
                    onFractionSeek = { frac -> webView?.goToFraction(frac) },
                    onPrefsChange = { p -> scope.launch { ReaderSettings.save(context, p) } },
                )
            }
        }
    }
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
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onImport: () -> Unit,
    onFractionSeek: (Float) -> Unit,
    onPrefsChange: (ReaderPreferences) -> Unit,
) {
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "进度 ${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Slider(
                            value = fraction,
                            onValueChange = { onFractionSeek(it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
                ) {
                    PanelAction("目录", onClick = onOpenToc)
                    PanelAction("字号", onClick = { /* 见设置区 */ })
                    PanelAction("主题", onClick = { /* 见设置区 */ })
                    PanelAction("翻页", onClick = { /* 见设置区 */ })
                }
                // 设置区（内联展开）
                SettingsControls(prefs = prefs, onPrefsChange = onPrefsChange)
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
    onPrefsChange: (ReaderPreferences) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 字号
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                "${prefs.fontSize}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = prefs.fontSize.toFloat(),
                onValueChange = { onPrefsChange(prefs.copy(fontSize = it.toInt())) },
                valueRange = 12f..30f,
                modifier = Modifier.weight(1f),
            )
        }
        // 行距
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            Text(
                String.format("%.1f", prefs.lineHeight),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            Slider(
                value = prefs.lineHeight.toFloat(),
                onValueChange = { onPrefsChange(prefs.copy(lineHeight = it.toDouble())) },
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
