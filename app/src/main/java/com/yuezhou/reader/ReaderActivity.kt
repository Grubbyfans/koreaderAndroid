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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.EpubFileGeneration
import com.yuezhou.reader.reader.EpubSpeechChapterSource
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import com.yuezhou.reader.reader.SyncLogStore
import com.yuezhou.reader.reader.db.SyncLogEntity
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
import kotlinx.coroutines.flow.combine
import com.yuezhou.reader.speech.SpeechVoices
import com.yuezhou.reader.speech.TtsEngine
import com.yuezhou.reader.speech.TtsVoice
import com.yuezhou.reader.ui.reader.ProgressSyncDialog
import com.yuezhou.reader.ui.reader.ReaderOverlay
import com.yuezhou.reader.ui.reader.ReaderPalette
import com.yuezhou.reader.ui.reader.ReaderPanel
import com.yuezhou.reader.ui.reader.ReaderViewModel
import com.yuezhou.reader.ui.reader.SyncActionResult
import com.yuezhou.reader.ui.reader.RemoteProgressOffer
import com.yuezhou.reader.ui.reader.TocDrawer
import com.yuezhou.reader.ui.reader.formatSyncProgress
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
            YuezhouTheme(followsAppAppearance = false) {
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
    var animationController by remember { mutableStateOf<com.yuezhou.reader.animation.ReaderAnimationController?>(null) }
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
    var readerOverlay by remember { mutableStateOf(ReaderOverlay.Hidden) }
    val panelVisible = readerOverlay != ReaderOverlay.Hidden
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
    val speechPaused by SpeechProgressTracker.paused.collectAsStateWithLifecycle()
    // 记录暂停时的可见页；翻到新页后应重新取当前页正文，而非恢复旧播放器。
    var pausedPageCfi by rememberSaveable(bookId) { mutableStateOf<String?>(null) }
    LaunchedEffect(speechPaused, bookId) {
        if (speechPaused) {
            if (pausedPageCfi == null) pausedPageCfi = currentCfi
        } else {
            pausedPageCfi = null
        }
    }
    // 朗读中，用于「朗读」按钮切换为「停止」
    var speakRequested by remember { mutableStateOf(false) }
    var speechPreparationJob by remember { mutableStateOf<Job?>(null) }
    // 目录抽屉
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val vm: ReaderViewModel = viewModel()
    LaunchedEffect(bookId) { vm.resetForBook() }
    val store = vm.libraryStore
    val cloudSync = vm.cloudSync
    val automaticSync = vm.automaticSync
    val syncLogStore = vm.syncLogStore
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
    var pendingJump by remember { mutableStateOf<Pair<String, CompletableDeferred<ReaderEvent.JumpVerified>>?>(null) }

    suspend fun saveReadingPosition(id: String, position: Pair<String, Double?>) {
        if (!vm.saveProgress(id, position)) status = "无法保存本机阅读进度"
    }

    suspend fun flushProgress() {
        saveJob?.cancelAndJoin()
        val pos = vm.lastPosition ?: return
        val id = bookId ?: return
        saveReadingPosition(id, pos)
    }


    suspend fun jumpAndSave(
        id: String,
        wv: ReaderWebView,
        previous: Pair<String, Double?>,
        target: Pair<String, Double?>,
        targetXPointer: String? = null,
        expectedVersion: Long? = null,
    ): Boolean {
        if (pendingJump != null || leaving) return false
        val requestId = java.util.UUID.randomUUID().toString()
        val result = CompletableDeferred<ReaderEvent.JumpVerified>()
        pendingJump = requestId to result
        try {
            if (expectedVersion == null) saveJob?.cancelAndJoin()
            else if (vm.positionVersion != expectedVersion) return false
            wv.verifyJump(requestId, target.first, previous.first, previous.first.takeIf { expectedVersion != null })
            val jump = withTimeoutOrNull(8_000) { result.await() }
            if (jump == null && !leaving && expectedVersion == null) wv.goToCfi(previous.first)
            if (leaving) return false
            if (jump?.stale == true) {
                currentCfi?.takeIf { it != previous.first }?.let { cfi ->
                    val position = cfi to currentFraction?.toDouble()
                    vm.updatePosition(position) { wv.cfiToXPointer(position.first) }
                    saveJob?.cancelAndJoin()
                    saveReadingPosition(id, position)
                }
            }
            if (jump?.verified != true) return false
            val position = target.first to (jump.fraction ?: target.second)
            saveJob?.cancelAndJoin()
            vm.updatePosition(position, targetXPointer)
            saveReadingPosition(id, position)
            return true
        } finally {
            pendingJump = null
        }
    }

    fun autoPullRemoteProgress() {
        if (!latestPrefs.autoSync || vm.autoPulled) return
        val id = bookId ?: return
        val wv = webView ?: return
        val initial = vm.lastPosition ?: return
        val initialVersion = vm.positionVersion
        vm.autoPulled = true
        scope.launch {
            try {
                when (val result = automaticSync.pull(
                    bookId = id,
                    localCfi = initial.first,
                    isActive = { !leaving && webView === wv && latestPrefs.autoSync },
                    hasMoved = { vm.positionVersion != initialVersion },
                    acceptsAutomaticJump = { !vm.syncDialogOpen && !vm.syncBusy && !speaking && !speakRequested },
                    resolve = wv::xPointerToCfi,
                    compare = wv::comparePositions,
                    apply = { cfi, remote ->
                        jumpAndSave(id, wv, initial, cfi to remote.percent, remote.xpointer, initialVersion)
                    },
                )) {
                    ReaderAutomaticSync.PullResult.NoChange -> Unit
                    ReaderAutomaticSync.PullResult.Applied -> {
                        vm.undoPosition = initial
                        status = "已续读到云端进度，可在进度同步中撤销"
                        syncLogStore.append("pull", title, SyncLogEntity.Outcome.SUCCESS, "已续读到云端进度")
                    }
                    is ReaderAutomaticSync.PullResult.Offer -> {
                        val reason = when (result.reason) {
                            ReaderAutomaticSync.Reason.LOCAL_MOVED -> "等待同步期间已翻页"
                            ReaderAutomaticSync.Reason.REMOTE_BEHIND -> "云端位置比本机靠前"
                            ReaderAutomaticSync.Reason.CONFIRM_POSITION -> "需要确认云端位置"
                        }
                        vm.remoteOffer = RemoteProgressOffer(result.cfi, result.remote.xpointer, result.remote.percent, reason)
                        vm.offerResult = null
                        syncLogStore.append("pull", title, SyncLogEntity.Outcome.OFFER, "${reason}，保留本机位置")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                syncLogStore.append("pull", title, SyncLogEntity.Outcome.FAILED, e.message ?: "拉取失败")
            }
        }
    }

    /** 冻结同一次定位的位置和百分比，退出前短暂等待尚未完成的锚点转换。 */
    suspend fun positionForAutoUpload(): Pair<String, Double?>? {
        val position = vm.lastPosition ?: return null
        val conversion = vm.positionConversion ?: return null
        return automaticSync.uploadPosition(position, conversion) {
            vm.lastPosition === position && pendingJump == null
        }
    }

    /** 自动上传本机进度覆盖云端（自动同步开启时，退出/切出时调用）。 */
    suspend fun autoPushProgress(position: Pair<String, Double?>?) {
        val id = bookId ?: return
        if (vm.autoSyncBusy) return
        vm.autoSyncBusy = true
        try {
            if (position == null) {
                syncLogStore.append("push", title, SyncLogEntity.Outcome.OFFER, "当前位置尚未确认或转换，已跳过上传")
                return
            }
            val error = cloudSync.uploadLocalProgress(id, position.first, position.second)
            syncLogStore.append(
                    "push",
                    title,
                    if (error == null) SyncLogEntity.Outcome.SUCCESS else SyncLogEntity.Outcome.FAILED,
                    error ?: "已覆盖云端进度",
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncLogStore.append("push", title, SyncLogEntity.Outcome.FAILED, e.message ?: "上传失败")
        } finally {
            vm.autoSyncBusy = false
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
        if (bookId == null || vm.syncDialogOpen) return
        vm.syncLoading = true
        vm.syncInfo = null
        vm.syncResult = null
        vm.syncDialogOpen = true
    }

    LaunchedEffect(vm.syncDialogOpen, bookId) {
        val id = bookId ?: return@LaunchedEffect
        if (!vm.syncDialogOpen) return@LaunchedEffect
        try {
            vm.syncInfo = vm.loadProgressInfo(id)
        } finally {
            vm.syncLoading = false
        }
    }

    fun applyRemoteProgress() {
        val id = bookId ?: return
        val info = vm.syncInfo ?: return
        val remote = info.remoteLocation ?: return
        val previous = vm.lastPosition ?: return
        val wv = webView ?: return
        vm.syncBusy = true
        SpeechPlanStore.stop(context)
        scope.launch {
            try {
                val cfi = wv.xPointerToCfi(remote)
                val applied = cfi != null && jumpAndSave(id, wv, previous, cfi to info.remotePercent, remote)
                if (applied) vm.undoPosition = previous
                vm.syncResult = SyncActionResult(
                    if (applied) "已应用云端进度，可在进度同步中撤销"
                    else "无法确认云端落点，已保留本机进度",
                    isError = !applied,
                )
            } finally {
                vm.syncBusy = false
            }
        }
    }

    fun undoRemoteProgress() {
        val id = bookId ?: return
        val previous = vm.lastPosition ?: return
        val restore = vm.undoPosition ?: return
        val wv = webView ?: return
        vm.syncBusy = true
        SpeechPlanStore.stop(context)
        scope.launch {
            try {
                val restored = jumpAndSave(id, wv, previous, restore)
                if (restored) vm.undoPosition = null
                vm.syncResult = SyncActionResult(
                    if (restored) "已恢复到同步前位置" else "无法确认撤销位置",
                    isError = !restored,
                )
            } finally {
                vm.syncBusy = false
            }
        }
    }

    // 上传本机进度到云端：先把 CFI 转 XPointer 再上传
    fun uploadLocalProgress() {
        val id = bookId ?: return
        val pos = vm.lastPosition
        if (pos == null) {
            vm.syncResult = SyncActionResult("本机暂无进度可上传", isError = true)
            return
        }
        val cfi = pos.first
        val wv = webView
        if (wv == null) {
            vm.syncResult = SyncActionResult("阅读器尚未就绪，无法上传进度", isError = true)
            return
        }
        vm.syncBusy = true
        scope.launch {
            try {
                val error = vm.uploadProgress(id, cfi, pos.second) { wv.cfiToXPointer(it) }
                vm.syncResult = SyncActionResult(
                    error ?: "已上传本机进度",
                    isError = error != null,
                )
                if (error == null) vm.syncInfo = vm.loadProgressInfo(id)
            } finally {
                vm.syncBusy = false
            }
        }
    }

    // 朗读进度收集：服务写入 SpeechProgressTracker，这里只反映 UI（朗读中状态 + 高亮回写）。
    // 位置落库已移到服务侧（SpeechProgressSink），Activity 销毁后仍持续保存。
    LaunchedEffect(bookId, webView) {
        combine(SpeechProgressTracker.current, SpeechProgressTracker.paused) { snap, paused ->
            snap to paused
        }.collect { (snap, paused) ->
            speaking = snap != null && snap.bookId == bookId
            // 朗读停止时同步清空「朗读请求中」标记
            if (snap == null) speakRequested = false
            if (paused || snap == null || snap.bookId != bookId) webView?.clearSpeechHighlight()
            // 已确认朗读点 → 回写高亮到 WebView（起止锚点形成整句高亮）
            val anchor = snap?.sourceAnchor
            if (!paused && snap != null && snap.bookId == bookId && anchor != null && (anchor.nodeId.startsWith("epubcfi(") || anchor.nodeId.matches(Regex("\\d+:\\d+")))) {
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
    LaunchedEffect(readerOverlay, availableVoices.isEmpty()) {
        if (readerOverlay == ReaderOverlay.Speech && availableVoices.isEmpty()) {
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
        val engine = prefs.effectiveSpeechEngine(
            SpeechRuntime.kokoroAvailable,
            SpeechRuntime.networkTtsAvailable && SpeechRuntime.networkTtsRuleId != null,
        )
        android.util.Log.d("ReaderActivity", "startSpeech speechEngine=${prefs.speechEngine}, " +
            "nttAvail=${SpeechRuntime.networkTtsAvailable}, nttRuleId=${SpeechRuntime.networkTtsRuleId}, " +
            "kokoroAvail=${SpeechRuntime.kokoroAvailable} → engine=$engine")
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
                    prefs.kokoroModel, prefs.kokoroSpeaker, novelParsing, prefs.networkTtsRuleId.takeIf { it != 0L } ?: SpeechRuntime.networkTtsRuleId))
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
            if (event == Lifecycle.Event.ON_STOP) animationController?.suspendPreview()
            if (event == Lifecycle.Event.ON_START) animationController?.resumePreview()
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
                event.cfi?.let { animationController?.relocated(it) }
                currentCfi = event.cfi
                currentFraction = event.fraction
                currentIndex = event.index
                currentTocId = event.tocId
                currentTocHref = event.tocHref
                val cfi = event.cfi
                val fraction = event.fraction?.toDouble()?.takeIf { it in 0.0..1.0 }
                if (cfi != null && bookId != null && pendingJump == null && !leaving) {
                    vm.updatePosition(cfi to fraction) { target -> webView?.cfiToXPointer(target) }
                    // 去抖：300ms 内的连续翻页合并成一次写
                    saveJob?.cancel()
                    saveJob = scope.launch {
                        delay(300)
                        saveReadingPosition(bookId, cfi to fraction)
                    }
                    // 首次定位后比较云端位置。
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

    LaunchedEffect(animationController, openedSource, readerPrefs.pageAnimation, readerPrefs.vertical,
        readerPrefs.fontSize, readerPrefs.fontId, readerPrefs.fontWeight, readerPrefs.lineHeight,
        readerPrefs.pageMargin, readerPrefs.theme) {
        val controller = animationController ?: return@LaunchedEffect
        val source = openedSource ?: return@LaunchedEffect
        if (!readerPrefs.vertical && readerPrefs.pageAnimation in setOf("fade", "ios_curl", "ripple")) {
            controller.settings(readerPrefs.pageAnimation, layoutChanged = true)
            controller.background = ReaderPalette.background(readerPrefs.theme).let { color ->
                android.graphics.Color.argb((color.alpha * 255).toInt(), (color.red * 255).toInt(),
                    (color.green * 255).toInt(), (color.blue * 255).toInt())
            }
            controller.open(source.first, source.second)
            currentCfi?.let(controller::relocated)
        } else controller.disable()
    }

    // 水波纹动画参数单独监听，仅更新动画时长/带宽，不重排、不重开书籍。
    LaunchedEffect(animationController, readerPrefs.rippleDurationMs, readerPrefs.rippleRatio) {
        animationController?.setRippleParams(readerPrefs.rippleDurationMs, readerPrefs.rippleRatio)
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
                readerOverlay = if (readerOverlay == ReaderOverlay.Toolbar) ReaderOverlay.Hidden else ReaderOverlay.Toolbar
            } else {
                leaveReader()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ReaderPalette.background(readerPrefs.theme)),
        ) {
            // 正文 WebView（铺满，单击呼出面板）
            AndroidView(
                factory = { ctx ->
                    val host = android.widget.FrameLayout(ctx)
                    ReaderWebView(ctx).also { wv ->
                        wv.eventListener = eventListener
                        wv.onTap = {
                            readerOverlay = if (readerOverlay == ReaderOverlay.Hidden) ReaderOverlay.Layout else ReaderOverlay.Hidden
                        }
                        wv.onPageTap = { dir ->
                            if (animationController?.tap(dir) != true) {
                                if (dir > 0) wv.nextPage() else wv.prevPage()
                            }
                        }
                        wv.onBrightnessScroll = { delta ->
                            adjustBrightness(ctx, delta)
                        }
                        wv.verticalMode = readerPrefs.vertical
                        host.addView(wv, android.widget.FrameLayout.LayoutParams(-1, -1))
                        animationController = com.yuezhou.reader.animation.ReaderAnimationController(host, wv, scope,
                            configurePreview = { preview ->
                                val p = latestPrefs
                                preview.applySettings(p.fontSize, p.lineHeight, p.pageMargin, p.theme, false,
                                    p.fontId, p.fontWeight, "none")
                            }, reportError = { android.util.Log.w("ReaderAnimation", "Animation unavailable", it) })
                        wv.onExternalNavigation = { animationController?.cancel() }
                        wv.onAnimationTouch = { animationController?.touch(it) == true }
                        webView = wv
                    }
                    host
                },
                onRelease = {
                    animationController?.close(); animationController = null
                    webView?.release(); webView = null
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
                    speaking = (speaking || speakRequested) && !speechPaused,
                    onBack = { leaveReader() },
                    overlay = readerOverlay,
                    onOverlay = { readerOverlay = it },
                    onDismiss = { readerOverlay = ReaderOverlay.Hidden },
                    onOpenToc = {
                        readerOverlay = ReaderOverlay.Toolbar
                        scope.launch { drawerState.open() }
                    },
                    onImport = onImport,
                    onFractionSeek = { frac -> webView?.goToFraction(frac) },
                    onPrefsChange = { p ->
                        if (p.networkTtsRuleId != readerPrefs.networkTtsRuleId) {
                            SpeechRuntime.networkTtsRuleId = p.networkTtsRuleId.takeIf { it != 0L }
                        }
                        val speechConfigChanged = p.networkTtsRuleId != readerPrefs.networkTtsRuleId || p.speechVoice != readerPrefs.speechVoice ||
                            p.speechEngine != readerPrefs.speechEngine ||
                            p.kokoroModel != readerPrefs.kokoroModel ||
                            p.kokoroSpeaker != readerPrefs.kokoroSpeaker
                        val rateChanged = p.speechRate != readerPrefs.speechRate
                        scope.launch {
                            ReaderSettings.save(context, readerPrefs, p)
                            // 语速实时生效（Kokoro 重合成当前句，系统 TTS 应用到后续句），不打断队列。
                            if (rateChanged && speaking) {
                                SpeechPlanStore.setRate(context, p.speechRate)
                            }
                            // 引擎/音色变更且正在朗读：保存完成、等 DataStore flow 传播到 latestPrefs 后
                            // 再重启朗读，确保 startSpeech 读到新配置。
                            val deferredNetworkVoice = speechPaused &&
                                p.speechEngine == "network_tts" &&
                                p.networkTtsRuleId != readerPrefs.networkTtsRuleId &&
                                p.speechEngine == readerPrefs.speechEngine
                            if (deferredNetworkVoice) {
                                SpeechPlanStore.putConfig(SpeechPlanStore.peekConfig().copy(
                                    networkTtsRuleId = p.networkTtsRuleId.takeIf { it != 0L },
                                ))
                            }
                            if (speechConfigChanged && speaking && !deferredNetworkVoice) {
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
                        if (readerPrefs.speechEngine == "network_tts" && speaking) {
                            if (speechPaused) {
                                if (currentCfi != null && currentCfi != pausedPageCfi) {
                                    webView?.clearSpeechHighlight()
                                    startWithBackgroundGuide()
                                } else {
                                    SpeechPlanStore.resume(context)
                                    status = "已继续朗读"
                                }
                            } else {
                                pausedPageCfi = currentCfi
                                SpeechPlanStore.pause(context)
                                status = "已暂停朗读"
                            }
                        } else if (speaking || speakRequested) {
                            speechPreparationJob?.cancel()
                            speakRequested = false
                            // 朗读中 → 停止
                            webView?.clearSpeechHighlight()
                            SpeechPlanStore.stop(context)
                            status = "已停止朗读"
                        } else {
                            startWithBackgroundGuide()
                        }
                    },
                )
            }
        }

        val offer = vm.remoteOffer
        if (offer != null && readerPrefs.autoSync && !vm.syncDialogOpen) {
            AlertDialog(
                onDismissRequest = { vm.remoteOffer = null },
                title = { Text("发现云端进度") },
                text = {
                    Column {
                        Text("${offer.reason}。云端 ${formatSyncProgress(offer.xpointer, offer.percent)}，本机 " +
                            formatSyncProgress(vm.lastPosition?.first, vm.lastPosition?.second))
                        vm.offerResult?.let {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                it.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (it.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                confirmButton = {
                    if (vm.offerResult != null) {
                        TextButton(onClick = { vm.remoteOffer = null }) { Text("关闭") }
                    } else {
                        TextButton(onClick = { vm.remoteOffer = null }) { Text("保留本机位置") }
                    }
                },
                dismissButton = {
                    if (vm.offerResult == null && offer.cfi != null) {
                        TextButton(onClick = {
                            val id = bookId ?: return@TextButton
                            val wv = webView ?: return@TextButton
                            val previous = vm.lastPosition ?: return@TextButton
                            SpeechPlanStore.stop(context)
                            scope.launch {
                                val applied = jumpAndSave(id, wv, previous, offer.cfi to offer.percent, offer.xpointer)
                                if (applied) vm.undoPosition = previous
                                vm.offerResult = SyncActionResult(
                                    if (applied) "已跳到云端位置，可在进度同步中撤销"
                                    else "无法确认云端落点，已保留本机进度",
                                    isError = !applied,
                                )
                            }
                        }) { Text("跳到云端位置") }
                    }
                },
            )
        }

        // 进度同步面板（方案 §5.6：本机/云端对照 + 上传/应用两步交互）
        if (vm.syncDialogOpen) {
            ProgressSyncDialog(
                info = vm.syncInfo,
                localPosition = vm.lastPosition,
                loading = vm.syncLoading,
                busy = vm.syncBusy || pendingJump != null,
                result = vm.syncResult,
                onUpload = { uploadLocalProgress() },
                onApply = { applyRemoteProgress() },
                onUndo = if (vm.undoPosition != null) ::undoRemoteProgress else null,
                onDismiss = { vm.syncDialogOpen = false },
            )
        }

    }
}
