package com.yuezhou.reader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.BookUploadControls
import com.yuezhou.reader.AppearanceOptions
import com.yuezhou.reader.BookUploadQueue
import com.yuezhou.reader.BuildConfig
import com.yuezhou.reader.CloudConfigSection
import com.yuezhou.reader.KokoroModelManagerDialog
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.db.SyncLogEntity
import com.yuezhou.reader.reader.DownloadDirectory
import com.yuezhou.reader.reader.SyncLogStore
import com.yuezhou.reader.ReaderFontPreference
import com.yuezhou.reader.ReaderPreferences
import com.yuezhou.reader.ReaderSettings
import com.yuezhou.reader.speech.KokoroImportState
import com.yuezhou.reader.speech.SpeechDiagnosticsState
import com.yuezhou.reader.speech.SpeechRuntime
import com.yuezhou.reader.SpeechBackgroundSettings
import com.yuezhou.reader.ui.LibraryUiPrefs
import com.yuezhou.reader.ui.components.LibraryPageHeading
import com.yuezhou.reader.ui.online.ServerSettingsScreen
import com.yuezhou.reader.ui.online.ZLibrarySettingsScreen
import com.yuezhou.reader.YuezhouColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SettingsRoute(val title: String, val description: String = "") {
    Home("设置"),
    Reading("阅读偏好", "字体、字号、行距、页边距和翻页"),
    Appearance("外观与主题", "书架布局和栏目滑动"),
    Sync("同步与备份", "书籍同步、进度、任务和诊断"),
    Sources("书库与书源", "百度网盘、WebDAV 和 Z-Library"),
    Speech("语音朗读", "后台权限与可用朗读模型"),
    Downloads("存储与下载", "下载目录与任务"),
    About("关于静读", "版本信息"),
    SyncLog("同步记录"),
    ZLibrary("Z-Library 设置"),
    Servers("服务器管理"),
    NetworkTts("网络 TTS 规则管理", "导入 Legado 原版网络朗读规则并试听");

    val parent: SettingsRoute
        get() = when (this) {
            SyncLog -> Sync
            ZLibrary -> Sources
            Servers -> ZLibrary
            NetworkTts -> Speech
            else -> Home
        }

    companion object {
        val groups = listOf(Reading, Appearance, Sync, Sources, Speech, Downloads, About)
    }
}

@Composable
internal fun SettingsPage(
    cloudSync: CloudSync,
    uploadQueue: BookUploadQueue,
    cloudLoading: Boolean,
    tabSwipeEnabled: Boolean,
    onTabSwipeChange: (Boolean) -> Unit,
    route: SettingsRoute,
    onNavigate: (SettingsRoute) -> Unit,
    localGridMode: Boolean,
    onLocalGridMode: (Boolean) -> Unit,
    onOpenDownloads: () -> Unit,
    cloudSetupProvider: String? = null,
    onCloudSetupHandled: () -> Unit = {},
    onCloudConfigured: (String) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by ReaderSettings.flow(context).collectAsState(
        initial = ReaderPreferences(),
    )

    val downloadDirectory = remember(context) { DownloadDirectory(context) }
    // Pager 组合销毁后设置页滚动位置仍保留（跨页切换/进程重建）。
    val settingsPrefs = remember(context) { LibraryUiPrefs(context) }
    val scrollKey = if (route == SettingsRoute.Home) "settings" else "settings.${route.name}"
    val settingsScroll = remember(scrollKey) {
        ScrollState(settingsPrefs.scroll(scrollKey).offset)
    }
    LaunchedEffect(settingsScroll) {
        snapshotFlow { settingsScroll.value }
            .distinctUntilChanged()
            .collectLatest { value ->
                delay(150)
                settingsPrefs.saveScroll(scrollKey, 0, value)
            }
    }
    // Pager 销毁该页时立即落盘，避免少于防抖时长的最后一次滚动丢失。
    DisposableEffect(settingsScroll) {
        onDispose { settingsPrefs.saveScroll(scrollKey, 0, settingsScroll.value) }
    }
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
    var rippleDurationPreview by remember { mutableStateOf<Int?>(null) }
    var rippleRatioPreview by remember { mutableStateOf<Int?>(null) }

    val credentialStore = remember(context) { CloudCredentialStore(context) }

    var modelBusy by remember { mutableStateOf(false) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var modelInfos by remember(route) { mutableStateOf(if (route == SettingsRoute.Speech) SpeechRuntime.kokoro?.modelInfos().orEmpty() else emptyList()) }
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

    when (route) {
        SettingsRoute.SyncLog -> { SyncLogPage(onBack = { onNavigate(route.parent) }); return }
        SettingsRoute.Servers -> { ServerSettingsScreen(onBack = { onNavigate(route.parent) }); return }
        SettingsRoute.NetworkTts -> { NetworkTtsRulesScreen(onBack = { onNavigate(route.parent) }); return }
        SettingsRoute.ZLibrary -> {
            ZLibrarySettingsScreen(onBack = { onNavigate(route.parent) },
                onOpenServerSettings = { onNavigate(SettingsRoute.Servers) })
            return
        }
        else -> Unit
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(settingsScroll)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (route == SettingsRoute.Home) {
            SettingsRoute.groups.forEach { group ->
                Surface(
                    Modifier.fillMaxWidth().clickable { onNavigate(group) },
                    shape = MaterialTheme.shapes.large,
                    color = YuezhouColors.Surface,
                    border = BorderStroke(1.dp, YuezhouColors.Divider),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(group.title, style = MaterialTheme.typography.titleMedium)
                            Text(group.description, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
                        }
                        Text("›", style = MaterialTheme.typography.titleLarge, color = YuezhouColors.BrandDark)
                    }
                }
            }
        } else {
            TextButton(onClick = { onNavigate(route.parent) }) { Text("返回") }
            LibraryPageHeading(route.title)
        }

        if (route == SettingsRoute.Sync) {
            SettingsCard("书籍同步", highlighted = true) {
                BookUploadControls(cloudSync, uploadQueue, cloudLoading)
            }

            SettingsCard("自动同步进度") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动同步", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "打开书籍时，云端位置更靠后则自动续读，更靠前则询问。退出或切出时，本机进度会覆盖云端。",
                            style = MaterialTheme.typography.bodySmall,
                            color = YuezhouColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = prefs.autoSync,
                        onCheckedChange = { enabled ->
                            scope.launch { ReaderSettings.save(context, prefs, prefs.copy(autoSync = enabled)) }
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 同步记录入口：点击进入独立页面
                TextButton(onClick = { onNavigate(SettingsRoute.SyncLog) }) {
                    Text("查看最近 20 次同步记录")
                }
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

        if (route == SettingsRoute.Appearance) {
            SettingsCard("主题与动效") { AppearanceOptions() }
            SettingsCard("书架布局") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("使用网格", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = localGridMode, onCheckedChange = onLocalGridMode)
                }
            }
            SettingsCard("栏目切换") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("左右滑动切换栏目", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "关闭后仅能点击底部导航与书源标签切换",
                            style = MaterialTheme.typography.bodySmall,
                            color = YuezhouColors.TextSecondary,
                        )
                    }
                    Switch(
                        checked = tabSwipeEnabled,
                        onCheckedChange = onTabSwipeChange,
                    )
                }
            }
        }

        if (route == SettingsRoute.Sources) {
            SettingsCard("书源设置") {
                Text(
                    "配置百度网盘、WebDAV 等云端书源账号。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                CloudConfigSection(
                    store = credentialStore,
                    requestedProvider = cloudSetupProvider,
                    onRequestHandled = onCloudSetupHandled,
                    onConfigured = onCloudConfigured,
                )

                // Z-Library 为检索型书源，配置与云同步账号分开管理。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigate(SettingsRoute.ZLibrary) }
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
        }

        if (route == SettingsRoute.Speech) {
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

            SettingsCard("网络 TTS") {
                Text(
                    "支持导入 Legado 原版网络朗读规则，选定后可作为连续朗读引擎。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
                val networkTtsAvailable = SpeechRuntime.networkTtsAvailable
                val selectedRuleId = prefs.networkTtsRuleId.takeIf { it != 0L } ?: SpeechRuntime.networkTtsRuleId
                val ruleList = remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
                val ctx = LocalContext.current
                LaunchedEffect(Unit) {
                    runCatching {
                        ruleList.value = com.yuezhou.reader.reader.networktts.NetworkTtsRuleRepository(ctx).list()
                            .map { it.id to it.name }
                    }
                }
                if (!networkTtsAvailable) {
                    Text(
                        "网络 TTS 未装配。",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                } else if (ruleList.value.isEmpty()) {
                    Text(
                        "尚未导入任何规则，请先到规则管理里导入。",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("当前规则：", style = MaterialTheme.typography.bodySmall)
                        var expanded by remember { mutableStateOf(false) }
                        val selected = ruleList.value.firstOrNull { it.first == selectedRuleId }
                        Box {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(selected?.second ?: "未选择")
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                ruleList.value.forEach { (id, name) ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            SpeechRuntime.networkTtsRuleId = id
                                            scope.launch {
                                                ReaderSettings.save(context, prefs, prefs.copy(networkTtsRuleId = id))
                                            }
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                Row {
                    TextButton(onClick = { onNavigate(SettingsRoute.NetworkTts) }) {
                        Text("规则管理")
                    }
                }
            }

            if (modelManagerOpen && SpeechRuntime.kokoro != null) {
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
                                ReaderSettings.save(context, prefs, prefs.copy(kokoroModel = ""))
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
        }

        if (route == SettingsRoute.Downloads) {
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
            TextButton(onClick = onOpenDownloads) { Text("下载管理") }
        }

        if (route == SettingsRoute.Reading) {
            SettingsCard("默认阅读排版") {
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
                                .clickable { scope.launch { ReaderSettings.save(context, prefs, prefs.copy(theme = value)) } }
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
                                .clickable { scope.launch { ReaderSettings.save(context, prefs, prefs.copy(vertical = value)) } }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 翻页动画方式
                Text("翻页动画", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "none" to "无动画",
                        "slide" to "滑动",
                        "fade" to "渐变",
                        "ios_curl" to "iOS 仿真",
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
                                .clickable { scope.launch { ReaderSettings.save(context, prefs, prefs.copy(pageAnimation = value)) } }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                // 水波纹动画参数：仅选中水波纹时显示，松手提交，下一次翻页生效。
                if (prefs.pageAnimation == "ripple") {
                    Text("水波纹时长：${rippleDurationPreview ?: prefs.rippleDurationMs}ms", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = (rippleDurationPreview ?: prefs.rippleDurationMs).toFloat(),
                        onValueChange = { rippleDurationPreview = it.toInt() },
                        onValueChangeFinished = {
                            val v = rippleDurationPreview
                            if (v != null) {
                                scope.launch { ReaderSettings.save(context, prefs, prefs.copy(rippleDurationMs = v)) }
                                rippleDurationPreview = null
                            }
                        },
                        valueRange = 100f..600f,
                        steps = 50,
                    )
                    Text("过渡区域：${rippleRatioPreview ?: prefs.rippleRatio}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = (rippleRatioPreview ?: prefs.rippleRatio).toFloat(),
                        onValueChange = { rippleRatioPreview = it.toInt() },
                        onValueChangeFinished = {
                            val v = rippleRatioPreview
                            if (v != null) {
                                scope.launch { ReaderSettings.save(context, prefs, prefs.copy(rippleRatio = v)) }
                                rippleRatioPreview = null
                            }
                        },
                        valueRange = 10f..80f,
                        steps = 70,
                    )
                    Text(
                        "恢复默认参数",
                        style = MaterialTheme.typography.bodyMedium,
                        color = YuezhouColors.BrandDark,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(YuezhouColors.BrandContainer)
                            .clickable { scope.launch { ReaderSettings.save(context, prefs, prefs.copy(rippleDurationMs = 280, rippleRatio = 35)) } }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                }

                ReaderFontPreference(prefs.fontId, prefs.fontWeight, onWeightChange = { w ->
                    scope.launch { ReaderSettings.save(context, prefs, prefs.copy(fontWeight = w)) }
                })

                // 字号
                Text("默认字号：${fontSizePreview ?: prefs.fontSize}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = (fontSizePreview ?: prefs.fontSize).toFloat(),
                    onValueChange = { fontSizePreview = it.toInt() },
                    onValueChangeFinished = {
                        val v = fontSizePreview
                        if (v != null) {
                            scope.launch { ReaderSettings.save(context, prefs, prefs.copy(fontSize = v)) }
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
                            scope.launch { ReaderSettings.save(context, prefs, prefs.copy(lineHeight = v)) }
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
                            scope.launch { ReaderSettings.save(context, prefs, prefs.copy(pageMargin = v)) }
                            marginPreview = null
                        }
                    },
                    valueRange = 0f..48f,
                )
            }
        }

        if (route == SettingsRoute.About) {
            SettingsCard("关于") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("当前版本", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = YuezhouColors.TextSecondary,
                    )
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
                    val (okLabel, okColor) = when (log.outcome) {
                        SyncLogEntity.Outcome.SUCCESS -> "成功" to YuezhouColors.BrandDark
                        SyncLogEntity.Outcome.OFFER -> "待确认" to MaterialTheme.colorScheme.tertiary
                        else -> "失败" to MaterialTheme.colorScheme.error
                    }
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
                                    okLabel,
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

/** 日志时间戳格式化：从 Composable 中移出，避免在组合期间读取非可观察的 Locale。 */
private fun formatLocalTime(pattern: String, epochMillis: Long): String =
    java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(epochMillis))
