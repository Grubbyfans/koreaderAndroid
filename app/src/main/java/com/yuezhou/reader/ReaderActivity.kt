package com.yuezhou.reader

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.lifecycle.lifecycleScope
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.EpubFileGeneration
import com.yuezhou.reader.reader.EpubSpeechChapterSource
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import com.yuezhou.reader.reader.SyncLogStore
import com.yuezhou.reader.reader.TocItem
import com.yuezhou.reader.core.speech.SpeechTextPlanning
import com.yuezhou.reader.core.speech.SourceAnchor
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechTextUnit
import com.yuezhou.reader.speech.KokoroModel
import com.yuezhou.reader.speech.SpeechConfig
import com.yuezhou.reader.speech.SpeechPlanStore
import com.yuezhou.reader.speech.SpeechRuntime
import com.yuezhou.reader.speech.SpeechProgressTracker
import com.yuezhou.reader.speech.SpeechVoices
import com.yuezhou.reader.speech.TtsEngine
import com.yuezhou.reader.speech.TtsVoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
            hide(WindowInsetsCompat.Type.systemBars())
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
    // Activity 级作用域：切后台/退出时 rememberCoroutineScope 会被取消，上传/记录须用它
    val appScope = LocalLifecycleOwner.current.lifecycleScope
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var title by remember { mutableStateOf(bookId ?: "阅读") }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    // 顶部提示自动消失：设置后 3 秒清除，避免残留
    LaunchedEffect(status) {
        if (status != null) {
            delay(3000)
            status = null
        }
    }
    var currentCfi by remember { mutableStateOf<String?>(null) }
    var currentFraction by remember { mutableStateOf<Float?>(null) }
    var currentIndex by remember { mutableStateOf<Int?>(null) }
    var totalSections by remember { mutableStateOf(0) }
    var tocItems by remember { mutableStateOf<List<TocItem>>(emptyList()) }
    var currentTocId by remember { mutableStateOf<Int?>(null) }
    var currentTocHref by remember { mutableStateOf<String?>(null) }
    // 待打开的书：WebView 尚未创建时先暂存
    var pendingOpen by remember { mutableStateOf<Triple<String, String?, Uri?>?>(null) }
    var openedSource by remember { mutableStateOf<Pair<String, Uri?>?>(null) }
    // 沉浸式面板显隐
    var panelVisible by remember { mutableStateOf(false) }
    // 亮度：0..1，null 表示跟随系统；左侧滑动调节后为具体值
    var brightness by remember { mutableStateOf<Float?>(null) }
    // 本次打开是否已恢复过记忆亮度（避免 prefs 刷新时重复应用）
    var brightnessRestored by remember { mutableStateOf(false) }
    // 亮度浮层是否显示（滑动调节时短暂显示）
    var brightnessVisible by remember { mutableStateOf(false) }
    // 亮度浮层自动隐藏去抖
    var brightnessHideJob by remember { mutableStateOf<Job?>(null) }
    // 亮度持久化去抖
    var brightnessSaveJob by remember { mutableStateOf<Job?>(null) }
    // 朗读状态：是否正在朗读
    var speaking by remember { mutableStateOf(false) }
    // 朗读中，用于「朗读」按钮切换为「停止」
    var speakRequested by remember { mutableStateOf(false) }
    var speechPreparationJob by remember { mutableStateOf<Job?>(null) }
    // 目录抽屉
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val store = remember { LibraryStore(context) }
    // 阅读设置（DataStore）
    val loadedPrefs by ReaderSettings.flow(context).collectAsStateWithLifecycle(initialValue = null)
    val readerPrefs = loadedPrefs ?: ReaderPreferences()
    // 让被 WebView/生命周期回调长期持有的闭包始终读到最新的 prefs（避免 stale closure）
    val latestPrefs by rememberUpdatedState(readerPrefs)
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
    var pendingJump by remember { mutableStateOf<Pair<String, CompletableDeferred<ReaderEvent.JumpVerified>>?>(null) }

    suspend fun flushProgress() {
        saveJob?.cancelAndJoin()
        val pos = lastPosition ?: return
        val id = bookId ?: return
        store.saveProgress(id, pos.first, pos.second)
    }

    // 是否已执行过本次打开的自动拉取（避免重复）
    var autoPulled by remember { mutableStateOf(false) }
    var autoSyncBusy by remember { mutableStateOf(false) }
    val syncLogStore = remember { SyncLogStore(context) }
    var positionConversion by remember { mutableStateOf<Deferred<String?>?>(null) }

    fun updatePosition(position: Pair<String, Double?>, wv: ReaderWebView?, xpointer: String? = null) {
        positionConversion?.cancel()
        lastPosition = position
        positionConversion = if (xpointer != null) {
            CompletableDeferred<String?>(xpointer)
        } else {
            wv?.let { scope.async { it.cfiToXPointer(position.first) } }
        }
    }

    suspend fun jumpAndSave(
        id: String,
        wv: ReaderWebView,
        previous: Pair<String, Double?>,
        target: Pair<String, Double?>,
        targetXPointer: String? = null,
    ): Boolean {
        if (pendingJump != null || leaving) return false
        val requestId = java.util.UUID.randomUUID().toString()
        val result = CompletableDeferred<ReaderEvent.JumpVerified>()
        pendingJump = requestId to result
        try {
            saveJob?.cancelAndJoin()
            wv.verifyJump(requestId, target.first, previous.first)
            val jump = withTimeoutOrNull(8_000) { result.await() }
            if (jump == null && !leaving) wv.goToCfi(previous.first)
            if (jump?.verified != true || leaving) return false
            val position = target.first to (jump.fraction ?: target.second)
            updatePosition(position, wv, targetXPointer)
            store.saveProgress(id, position.first, position.second)
            return true
        } finally {
            pendingJump = null
        }
    }

    /** 自动拉取云端进度并覆盖本地（自动同步开启时，书打开定位后调用一次）。 */
    fun autoPullRemoteProgress() {
        Log.d("AutoSync", "autoPullRemoteProgress 被调用, autoSync=${latestPrefs.autoSync}, autoPulled=$autoPulled, bookId=$bookId, webView=${webView != null}, lastPosition=$lastPosition")
        if (!latestPrefs.autoSync) return
        if (autoPulled) return
        val id = bookId ?: return
        val wv = webView ?: return
        autoPulled = true
        scope.launch {
            try {
                val remote = CloudSync(context).fetchRemoteProgress(id)
                if (remote == null) {
                    syncLogStore.append("pull", title, false, "云端暂无进度")
                    return@launch
                }
                if (leaving) return@launch
                val cfi = wv.xPointerToCfi(remote.xpointer)
                val previous = lastPosition
                if (previous == null) {
                    syncLogStore.append("pull", title, false, "本机尚未定位")
                    return@launch
                }
                val applied = cfi != null && jumpAndSave(id, wv, previous, cfi to remote.percent, remote.xpointer)
                syncLogStore.append(
                    "pull", title, applied,
                    if (applied) "已覆盖本地进度" else "云端落点无法确认，保留本地",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                syncLogStore.append("pull", title, false, e.message ?: "拉取失败")
            }
        }
    }

    /** 冻结同一次定位的位置和百分比，退出前短暂等待尚未完成的锚点转换。 */
    suspend fun positionForAutoUpload(): Pair<String, Double?>? {
        val position = lastPosition ?: return null
        val conversion = positionConversion ?: return null
        return withTimeoutOrNull(750) {
            conversion.join()
            if (conversion.isCancelled || lastPosition !== position || pendingJump != null) return@withTimeoutOrNull null
            conversion.await()?.let { it to position.second }
        }
    }

    /** 自动上传本机进度覆盖云端（自动同步开启时，退出/切出时调用）。 */
    suspend fun autoPushProgress(position: Pair<String, Double?>?) {
        val id = bookId ?: return
        if (autoSyncBusy) return
        autoSyncBusy = true
        try {
            if (position == null) {
                syncLogStore.append("push", title, false, "当前位置尚未确认或转换，已跳过上传")
                return
            }
            val error = CloudSync(context).uploadLocalProgress(id, position.first, position.second)
            syncLogStore.append("push", title, error == null, error ?: "已覆盖云端进度")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogStore.append("push", title, false, e.message ?: "上传失败")
        } finally {
            autoSyncBusy = false
        }
    }

    /**
     * 左侧滑动调节亮度：delta 为本次垂直位移增量（px），正=下滑增亮，负=上滑减暗。
     * 通过 Activity 窗口亮度覆盖实现（screenBrightness，范围 0.0..1.0），
     * 仅在本阅读页生效，不影响系统全局亮度；退出页面后随 Activity 销毁自动释放。
     * 调节结果持久化记忆，下次打开书自动恢复。
     */
    fun adjustBrightness(ctx: android.content.Context, delta: Float) {
        val activity = ctx as? android.app.Activity ?: return
        val window = activity.window
        // 首次调节：优先用记忆亮度，其次读系统当前亮度作为起点（否则从默认值跳变）
        var current = brightness ?: run {
            val remembered = latestPrefs.brightness
            if (remembered >= 0f) {
                remembered
            } else {
                try {
                    android.provider.Settings.System.getInt(
                        ctx.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    ) / 255f
                } catch (_: Exception) {
                    0.5f
                }
            }
        }
        // 灵敏度：约 600px 滑动对应满量程
        val step = delta / 600f
        current = (current + step).coerceIn(0.02f, 1f)
        brightness = current
        val attrs = window.attributes
        attrs.screenBrightness = current
        window.attributes = attrs
        // 持久化记忆（去抖，随滑随手势频繁调用合并写入）
        brightnessSaveJob?.cancel()
        brightnessSaveJob = appScope.launch {
            delay(400)
            ReaderSettings.saveBrightness(context, current)
        }
        // 显示浮层并自动隐藏
        brightnessVisible = true
        brightnessHideJob?.cancel()
        brightnessHideJob = scope.launch {
            delay(1200)
            brightnessVisible = false
        }
    }

    fun leaveReader() {
        if (leaving) return
        leaving = true
        // 先在 WebView 仍可用时确认上传位置，网络请求放在退出之后。
        appScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                flushProgress()
                val autoSync = latestPrefs.autoSync
                val position = if (autoSync) positionForAutoUpload() else null
                onBack()
                if (autoSync) autoPushProgress(position)
            }
        }
    }

    fun openSyncDialog() {
        if (bookId == null || syncDialogOpen) return
        syncLoading = true
        syncInfo = null
        syncDialogOpen = true
    }

    LaunchedEffect(syncDialogOpen, bookId) {
        val id = bookId ?: return@LaunchedEffect
        if (!syncDialogOpen) return@LaunchedEffect
        try {
            syncInfo = CloudSync(context).progressInfo(id)
        } finally {
            syncLoading = false
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
                val applied = cfi != null && jumpAndSave(id, wv, previous, cfi to info.remotePercent, remote)
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

    // 朗读进度收集：服务写入 SpeechProgressTracker，这里只反映 UI（朗读中状态 + 高亮回写）。
    // 位置落库已移到服务侧（SpeechProgressSink），Activity 销毁后仍持续保存。
    LaunchedEffect(Unit) {
        SpeechProgressTracker.current.collect { snap ->
            speaking = snap != null
            // 朗读停止时同步清空「朗读请求中」标记
            if (snap == null) speakRequested = false
            if (snap == null || snap.bookId != bookId) webView?.clearSpeechHighlight()
            // 已确认朗读点 → 回写高亮到 WebView（起止锚点形成整句高亮）
            val anchor = snap?.sourceAnchor
            if (snap != null && snap.bookId == bookId && anchor != null && (anchor.nodeId.startsWith("epubcfi(") || anchor.nodeId.matches(Regex("\\d+:\\d+")))) {
                val end = snap.endAnchor
                webView?.highlight(
                    anchor.nodeId,
                    anchor.offset,
                    end?.nodeId,
                    end?.offset ?: 0,
                )
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

    // 从整章文本节点构造朗读计划并启动前台朗读服务。
    // [nodes] 是本章完整文本（索引稳定）；[startPosition] 定位当前阅读位置对应的首句。
    fun startSpeech(
        nodes: List<com.yuezhou.reader.reader.ChapterTextNode>,
        chapterIndex: Int,
        startPosition: com.yuezhou.reader.reader.ChapterTextStart?,
    ) {
        val id = bookId ?: return
        val (fileName, source) = openedSource ?: return
        val prefs = latestPrefs
        val engine = if (prefs.speechEngine == "kokoro" && SpeechRuntime.kokoroAvailable) SpeechEngineType.KOKORO else SpeechEngineType.SYSTEM_TTS
        val novelParsing = engine == SpeechEngineType.KOKORO
        speechPreparationJob?.cancel()
        speechPreparationJob = scope.launch {
            try {
                val units = nodes.mapIndexed { index, node ->
                    SpeechTextUnit(node.text, SourceAnchor(node.cfi, 0),
                        node.paragraphId == null || node.paragraphId != nodes.getOrNull(index + 1)?.paragraphId)
                }
                val planning = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    SpeechTextPlanning.plan(units, engine, novelParsing)
                }
                if (planning.segments.isEmpty()) {
                    status = "本章无可朗读文本"; speakRequested = false; return@launch
                }
                planning.fallbackReason?.let { Log.w("NovelText", "解析失败，当前章回退：$it") }
                val generation = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    EpubFileGeneration.of(context, fileName, source)
                }
                val plan = SpeechPlan(id, generation, chapterIndex, planning.segments, planning.version,
                    novelParsing, units, engine)
                val from = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    startPosition?.let { start -> nodes.getOrNull(start.nodeIndex ?: -1)?.let {
                        plan.findSegmentForAnchor(it.cfi, start.charOffsetInNode)
                    } }?.coerceAtLeast(0) ?: 0
                }
                SpeechPlanStore.putConfig(SpeechConfig(prefs.speechRate, prefs.speechVoice, engine,
                    prefs.kokoroModel, prefs.kokoroSpeaker, novelParsing))
                SpeechPlanStore.startAndPlay(context, plan, EpubSpeechChapterSource(context, fileName, source), from)
                speakRequested = true
                status = "已从当前页开始朗读"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { speakRequested = false; status = "启动朗读失败：${e.message}" }
        }
    }

    // 生命周期：切后台/退出时立即保存进度；自动同步开启时同时上传覆盖云端
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && !leaving) {
                appScope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        flushProgress()
                        if (latestPrefs.autoSync && !leaving) autoPushProgress(positionForAutoUpload())
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val startWithBackgroundGuide = rememberBackgroundSpeechStart {
        speakRequested = true
        status = "准备朗读…"
        webView?.requestChapterText(currentCfi)
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
                pendingJump?.takeIf { it.first == event.requestId }?.second?.complete(event)
            }
            is ReaderEvent.Relocated -> {
                currentCfi = event.cfi
                currentFraction = event.fraction
                currentIndex = event.index
                currentTocId = event.tocId
                currentTocHref = event.tocHref
                val cfi = event.cfi
                val fraction = event.fraction?.toDouble()?.takeIf { it in 0.0..1.0 }
                if (cfi != null && bookId != null && pendingJump == null && !leaving) {
                    updatePosition(cfi to fraction, webView)
                    // 去抖：300ms 内的连续翻页合并成一次写
                    saveJob?.cancel()
                    saveJob = scope.launch {
                        delay(300)
                        store.saveProgress(bookId, cfi, fraction)
                    }
                    // 自动同步：首次定位到本地位置后，拉取云端进度覆盖本地（仅一次）
                    Log.d("AutoSync", "Relocated 触发, cfi=$cfi, autoSync=${latestPrefs.autoSync}")
                    autoPullRemoteProgress()
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
                    startSpeech(event.items, chapterIndex, event.startPosition)
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
            pageAnimation = prefs.pageAnimation,
        )
        pendingOpen?.let { (fileName, restoreCfi, source) ->
            pendingOpen = null
            openedSource = fileName to source
            wv.openEpub(fileName, restoreCfi, source)
        }
        // 打开书时恢复记忆亮度（仅首次，避免 prefs 变更反复覆盖用户当前调节）
        if (!brightnessRestored) {
            brightnessRestored = true
            val remembered = prefs.brightness
            if (remembered >= 0f) {
                brightness = remembered
                val attrs = (context as? android.app.Activity)?.window?.attributes
                if (attrs != null) {
                    attrs.screenBrightness = remembered
                    (context as android.app.Activity).window.attributes = attrs
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                drawerContainerColor = YuezhouColors.Surface,
                drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            ) {
                TocDrawer(
                    items = tocItems,
                    isOpen = drawerState.isOpen,
                    currentId = currentTocId,
                    currentHref = currentTocHref,
                    bookTitle = title,
                    progress = currentFraction,
                    onClose = { scope.launch { drawerState.close() } },
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
                        wv.onBrightnessScroll = { delta ->
                            adjustBrightness(ctx, delta)
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

            // 亮度浮层：左侧滑动调节亮度时显示当前百分比
            AnimatedVisibility(
                visible = brightnessVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.Center),
            ) {
                val b = brightness ?: 0.5f
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = YuezhouColors.Surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "亮度",
                            style = MaterialTheme.typography.labelMedium,
                            color = YuezhouColors.TextSecondary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "${(b * 100).toInt()}%",
                            style = MaterialTheme.typography.titleLarge,
                            color = YuezhouColors.TextPrimary,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = b.coerceIn(0f, 1f),
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }
            }

            // 沉浸式面板：顶部（返回+书名+章节）+ 底部（目录/进度/设置）
            AnimatedVisibility(
                visible = panelVisible,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(180)),
            ) {
                val kokoroModels = SpeechRuntime.kokoro?.availableModels().orEmpty()
                ReaderPanel(
                    title = title,
                    fraction = currentFraction,
                    sectionIndex = currentIndex,
                    totalSections = totalSections,
                    prefs = readerPrefs,
                    voices = availableVoices,
                    kokoroModels = kokoroModels,
                    speaking = speaking || speakRequested,
                    onBack = { leaveReader() },
                    onDismiss = { panelVisible = false },
                    onOpenToc = { scope.launch { drawerState.open() } },
                    onImport = onImport,
                    onFractionSeek = { frac -> webView?.goToFraction(frac) },
                    onPrefsChange = { p ->
                        val speechConfigChanged = p.speechVoice != readerPrefs.speechVoice ||
                            p.speechEngine != readerPrefs.speechEngine ||
                            p.kokoroModel != readerPrefs.kokoroModel ||
                            p.kokoroSpeaker != readerPrefs.kokoroSpeaker
                        val rateChanged = p.speechRate != readerPrefs.speechRate
                        scope.launch {
                            ReaderSettings.save(context, p)
                            // 语速实时生效（Kokoro 重合成当前句，系统 TTS 应用到后续句），不打断队列。
                            if (rateChanged && speaking) {
                                SpeechPlanStore.setRate(context, p.speechRate)
                            }
                            // 引擎/音色变更且正在朗读：保存完成、等 DataStore flow 传播到 latestPrefs 后
                            // 再重启朗读，确保 startSpeech 读到新配置。
                            if (speechConfigChanged && speaking) {
                                delay(120)
                                SpeechPlanStore.stop(context)
                                status = "已切换朗读配置，重新朗读…"
                                speakRequested = true
                                webView?.requestChapterText(currentCfi)
                            }
                        }
                    },
                    onSync = { openSyncDialog() },
                    onSpeak = {
                        if (speaking || speakRequested) {
                            speechPreparationJob?.cancel()
                            speakRequested = false
                            // 朗读中 → 停止
                            SpeechPlanStore.stop(context)
                            status = "已停止朗读"
                        } else {
                            startWithBackgroundGuide()
                        }
                    },
                )
            }
        }

        // 进度同步面板（方案 §5.6：本机/云端对照 + 上传/应用两步交互）
        if (syncDialogOpen) {
            ProgressSyncDialog(
                info = syncInfo,
                localPosition = lastPosition,
                loading = syncLoading,
                busy = syncBusy || pendingJump != null,
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
    localPosition: Pair<String, Double?>?,
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
                    val local = localPosition ?: info.localLocation?.let { it to info.localPercent }
                    Text(
                        "本机进度：${formatSyncProgress(local?.first, local?.second)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    val cloudProgress = info.remoteError?.let { "读取失败（$it）" }
                        ?: formatSyncProgress(info.remoteLocation, info.remotePercent)
                    Text(
                        "云端进度：$cloudProgress",
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
                enabled = !busy && !loading && localPosition != null && info?.remoteLocation != null,
            ) { Text("应用云端进度") }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onUpload,
                    enabled = !busy && !loading && localPosition != null,
                ) { Text("上传本机进度") }
                TextButton(onClick = onDismiss, enabled = !busy) { Text("关闭") }
            }
        },
    )
}

private fun formatSyncProgress(location: String?, percent: Double?): String = when {
    location == null -> "暂无进度"
    percent == null -> "已保存位置"
    else -> "${(percent * 100).toInt()}%"
}

private fun formatTime(millis: Long): String {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return fmt.format(java.util.Date(millis))
}

/** 主题背景色（与 bridge.js 主题一致）。 */
private fun themeBackground(theme: String): Color = when (theme) {
    "sepia" -> Color(0xFFF5ECD7)
    "dark" -> Color(0xFF1A1A1A)
    "green" -> Color(0xFFCCE8CF)
    "night-blue" -> Color(0xFF0D1B2A)
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
    kokoroModels: List<KokoroModel>,
    speaking: Boolean,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelMaxHeight = maxHeight / 2
        // 全屏透明点击层：拦截对正文/翻页区的点击，直接隐藏面板
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss),
        )
        Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏：从上方滑入/滑出
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(220)) { -it } + fadeOut(tween(220)),
            modifier = Modifier.fillMaxWidth(),
        ) {
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
        }

        Spacer(Modifier.weight(1f))

        // 底部栏：从下方滑入/滑出
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(tween(240)) { it } + fadeIn(tween(240)),
            exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(240)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight),
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
                    // 底部面板最多占半屏；操作栏固定，设置内容超出剩余高度时滚动。
                    AnimatedVisibility(
                        visible = settingsExpanded,
                        enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(180)),
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            SettingsControls(
                                prefs = prefs,
                                voices = voices,
                                kokoroModels = kokoroModels,
                                onPrefsChange = onPrefsChange,
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
private fun PanelAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun SettingsControls(
    prefs: ReaderPreferences,
    voices: List<TtsVoice>,
    kokoroModels: List<KokoroModel>,
    onPrefsChange: (ReaderPreferences) -> Unit,
) {
    // 拖动中的本地预览值；null 表示未拖动（跟随真实 prefs），松手才提交，避免连续写 DataStore + 重排全文
    var fontSizePreview by remember { mutableStateOf<Int?>(null) }
    var lineHeightPreview by remember { mutableStateOf<Double?>(null) }
    // 语速预览值；null 表示未拖动
    var ratePreview by remember { mutableStateOf<Float?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        ReaderFontPreference(prefs.fontId, prefs.fontWeight, onWeightChange = { w ->
            onPrefsChange(prefs.copy(fontWeight = w))
        })
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
                "green" to "护眼",
                "night-blue" to "夜蓝",
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
        // 翻页动画方式
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("动画", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(12.dp))
            listOf(
                "none" to "无",
                "slide" to "滑动",
            ).forEach { (value, label) ->
                TextButton(onClick = { onPrefsChange(prefs.copy(pageAnimation = value)) }) {
                    Text(
                        label,
                        color = if (prefs.pageAnimation == value) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (prefs.pageAnimation == value) FontWeight.Bold else FontWeight.Normal,
                    )
                }
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
        // 朗读引擎：测试版装配 Kokoro 后才显示切换；正式版固定系统 TTS。
        if (kokoroModels.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("引擎", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { onPrefsChange(prefs.copy(speechEngine = "system")) }) {
                    Text(
                        "系统 TTS",
                        color = if (prefs.speechEngine != "kokoro") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (prefs.speechEngine != "kokoro") FontWeight.Bold else FontWeight.Normal,
                    )
                }
                TextButton(onClick = { onPrefsChange(prefs.copy(speechEngine = "kokoro")) }) {
                    Text(
                        "Kokoro",
                        color = if (prefs.speechEngine == "kokoro") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (prefs.speechEngine == "kokoro") FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            if (prefs.speechEngine == "kokoro") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("模型", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    kokoroModels.forEach { model ->
                        TextButton(onClick = { onPrefsChange(prefs.copy(kokoroModel = model.id)) }) {
                            Text(
                                model.label,
                                color = if (prefs.kokoroModel == model.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (prefs.kokoroModel == model.id) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                KokoroSpeakerPreference(prefs.kokoroSpeaker) { id ->
                    onPrefsChange(prefs.copy(kokoroSpeaker = id))
                }
            }
        }
        if (prefs.speechEngine != "kokoro") {
            SpeechVoicePreference(voices, prefs.speechVoice) { name ->
                onPrefsChange(prefs.copy(speechVoice = name))
            }
        }
    }
}

@Composable
private fun KokoroSpeakerPreference(selectedId: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val presets = remember {
        listOf(
            3 to "女声 1（sid 3）",
            4 to "女声 2（sid 4）",
            5 to "女声 3（sid 5）",
            58 to "男声 1（sid 58）",
            59 to "男声 2（sid 59）",
            60 to "男声 3（sid 60）",
        )
    }
    val selectedLabel = presets.firstOrNull { it.first == selectedId }?.second ?: "sid $selectedId"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Kokoro 音色", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { open = true }) { Text(selectedLabel) }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Kokoro 中文音色") },
            text = {
                Column {
                    presets.forEach { (id, label) ->
                        TextButton(onClick = {
                            onSelect(id)
                            open = false
                        }) { Text(label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SpeechVoicePreference(voices: List<TtsVoice>, selectedName: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val labels = remember(voices) {
        val chinese = java.util.Locale.SIMPLIFIED_CHINESE
        voices.groupBy { it.locale.toLanguageTag() }.values.flatMap { byLocale ->
            byLocale.sortedBy { it.name }.mapIndexed { index, voice ->
                val localeName = voice.locale.getDisplayName(chinese).ifBlank { "语言未知" }
                // 展示名：优先音色真实名，退化到「语言 · 音色 N」
                val display = voice.displayName.ifBlank { "音色 ${index + 1}" }
                voice to "$localeName · $display"
            }
        }.sortedWith(compareBy(
            { it.first.locale.language != "zh" },
            { !it.first.isOfflineCapable },
            { it.first.locale.toLanguageTag() },
            { it.first.name },
        ))
    }
    val selectedLabel = when {
        selectedName.isBlank() -> "系统默认"
        else -> labels.firstOrNull { it.first.name == selectedName }?.second ?: "已选音色"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("音色", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { open = true }) { Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("朗读音色") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    item {
                        SpeechVoiceOption("系统默认（跟随引擎）", null, selectedName.isBlank()) {
                            open = false
                            onSelect("")
                        }
                    }
                    // 按性别分组展示，便于区分男声/女声
                    items(labels, key = { it.first.name }) { (voice, label) ->
                        val availability = when {
                            voice.isOfflineCapable -> "离线"
                            voice.isNetworkConnectionRequired -> "需要联网"
                            else -> "语音数据未安装"
                        }
                        val genderTag = when (voice.gender) {
                            TtsVoice.Gender.FEMALE -> "女声"
                            TtsVoice.Gender.MALE -> "男声"
                            null -> ""
                        }
                        val details = listOf(availability, genderTag, voice.displayName)
                            .filter { it.isNotBlank() }.joinToString(" · ")
                        SpeechVoiceOption(label, details, voice.name == selectedName) {
                            open = false
                            onSelect(voice.name)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun SpeechVoiceOption(label: String, details: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}


private data class TocRow(
    val key: String,
    val item: TocItem,
    val ancestors: List<String>,
)

@Composable
private fun TocDrawer(
    items: List<TocItem>,
    isOpen: Boolean,
    currentId: Int?,
    currentHref: String?,
    bookTitle: String,
    progress: Float?,
    onClose: () -> Unit,
    onSelect: (TocItem) -> Unit,
) {
    val rows = remember(items) {
        buildList {
            fun append(nodes: List<TocItem>, ancestors: List<String>) {
                nodes.forEachIndexed { index, node ->
                    val key = ancestors.lastOrNull()?.let { "$it/$index" } ?: index.toString()
                    add(TocRow(key, node, ancestors))
                    append(node.subitems, ancestors + key)
                }
            }
            append(items, emptyList())
        }
    }
    val active = rows.firstOrNull { currentId != null && it.item.id == currentId }
        ?: rows.firstOrNull { !currentHref.isNullOrEmpty() && it.item.href == currentHref }
    var expanded by remember(items) { mutableStateOf(emptySet<String>()) }
    val visible = remember(rows, expanded) {
        rows.filter { row -> row.ancestors.all { it in expanded } }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(isOpen, active?.key, rows) {
        if (isOpen && active != null) {
            val revealed = expanded + active.ancestors
            expanded = revealed
            val index = rows.filter { row -> row.ancestors.all { it in revealed } }.indexOf(active)
            if (index >= 0) listState.requestScrollToItem(index)
        }
    }

    val readProgress = (progress ?: 0f).coerceIn(0f, 1f)
    val percent = (readProgress * 100).toInt()

    Column(modifier = Modifier.fillMaxSize().background(YuezhouColors.Surface)) {
        // 顶部：标题 + 书名 + 关闭
        Column(
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 10.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "目录",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = YuezhouColors.TextPrimary,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭目录",
                        tint = YuezhouColors.TextSecondary,
                    )
                }
            }
            Text(
                text = bookTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = YuezhouColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp, top = 2.dp),
            )
        }

        // 阅读进度概览
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(YuezhouColors.Brand),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "阅读进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = YuezhouColors.TextSecondary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = YuezhouColors.BrandDark,
                )
            }
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier.fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(YuezhouColors.Divider),
            ) {
                Box(
                    Modifier.fillMaxWidth(readProgress.coerceAtLeast(0.01f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(YuezhouColors.Brand),
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(YuezhouColors.Divider))

        // 章节统计
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "章节",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = YuezhouColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 ${items.size} 章",
                style = MaterialTheme.typography.labelMedium,
                color = YuezhouColors.TextSecondary,
            )
        }

        if (rows.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp),
                ) {
                    Box(
                        modifier = Modifier.size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(YuezhouColors.BrandContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = null,
                            tint = YuezhouColors.BrandDark,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("这本书还没有目录", style = MaterialTheme.typography.bodyMedium, color = YuezhouColors.TextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "可以直接阅读正文，进度会照常保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible, key = { it.key }) { row ->
                    val item = row.item
                    val depth = row.ancestors.size
                    val current = row.key == active?.key
                    val hasChildren = item.subitems.isNotEmpty()
                    val isExpanded = row.key in expanded
                    val toggle = {
                        expanded = if (isExpanded) expanded - row.key else expanded + row.key
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (current) YuezhouColors.BrandContainer else Color.Transparent)
                            .clickable(enabled = item.href.isNotBlank() || hasChildren) {
                                if (item.href.isNotBlank()) onSelect(item) else toggle()
                            }
                            .padding(
                                start = (10 + depth.coerceAtMost(5) * 14).dp,
                                end = 10.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current) {
                            Box(
                                Modifier.width(3.dp)
                                    .height(20.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(YuezhouColors.Brand),
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        if (hasChildren) {
                            Box(
                                modifier = Modifier.size(28.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (isExpanded) YuezhouColors.BrandContainer else Color(0xFFF5F5F5))
                                    .clickable(onClick = toggle),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (isExpanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = if (isExpanded) "收起 ${item.label}" else "展开 ${item.label}",
                                    tint = if (isExpanded) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(36.dp))
                        }

                        Text(
                            text = item.label,
                            style = if (depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            color = if (current) YuezhouColors.BrandDark else if (depth == 0) YuezhouColors.TextPrimary else Color(0xFF4A4A4A),
                            fontWeight = if (current || depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (current) {
                            Box(
                                modifier = Modifier.padding(start = 8.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(YuezhouColors.BrandContainer)
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    "在读",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = YuezhouColors.BrandDark,
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(YuezhouColors.Divider))
        Text(
            text = "点击章节即可跳转",
            style = MaterialTheme.typography.labelSmall,
            color = YuezhouColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
