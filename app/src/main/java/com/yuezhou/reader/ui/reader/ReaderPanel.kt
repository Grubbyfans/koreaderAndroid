package com.yuezhou.reader.ui.reader

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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
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
import kotlinx.coroutines.flow.combine
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
import com.yuezhou.reader.ReaderFontPreference
import com.yuezhou.reader.ReaderPreferences
import com.yuezhou.reader.effectiveSpeechEngine
internal enum class ReaderOverlay(val title: String) {
    Hidden(""), Toolbar(""), Layout("排版"), Theme("主题"), Speech("朗读"), More("更多")
}

@Composable
internal fun ReaderPanel(
    title: String,
    fraction: Float?,
    sectionIndex: Int?,
    totalSections: Int,
    prefs: ReaderPreferences,
    voices: List<TtsVoice>,
    kokoroModels: List<KokoroModel>,
    speaking: Boolean,
    onBack: () -> Unit,
    overlay: ReaderOverlay,
    onOverlay: (ReaderOverlay) -> Unit,
    onDismiss: () -> Unit,
    onOpenToc: () -> Unit,
    onImport: () -> Unit,
    onFractionSeek: (Float) -> Unit,
    onPrefsChange: (ReaderPreferences) -> Unit,
    onSync: () -> Unit,
    onSpeak: () -> Unit,
) {
    // 进度滑块拖动中的本地预览值；null 表示未在拖动（跟随真实 fraction）
    var fractionPreview by remember { mutableStateOf<Float?>(null) }
    val appColors = MaterialTheme.colorScheme
    val panelColors = ReaderPalette.panelTheme(appColors, ReaderPalette.effectiveTheme(prefs))
    MaterialTheme(colorScheme = panelColors) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelMaxHeight = maxHeight * 0.45f
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
                // 固定为屏幕高度的 45%：切换栏目时大小稳定，内容超出时内部滚动
                modifier = Modifier.fillMaxWidth().height(panelMaxHeight),
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                        .padding(horizontal = 16.dp, vertical = 4.dp),
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
                    // 目录是独立入口，右侧标签切换设置内容。
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistChip(
                            onClick = onOpenToc,
                            label = { Text("目录") },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        Spacer(Modifier.width(4.dp))
                        listOf(ReaderOverlay.Layout, ReaderOverlay.Theme, ReaderOverlay.Speech, ReaderOverlay.More).forEach { item ->
                            FilterChip(
                                selected = overlay == item,
                                onClick = { onOverlay(if (overlay == item) ReaderOverlay.Toolbar else item) },
                                label = { Text(item.title, maxLines = 1) },
                            )
                        }
                    }
                    if (overlay != ReaderOverlay.Toolbar && overlay != ReaderOverlay.Hidden) {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            SettingsControls(
                                overlay = overlay,
                                prefs = prefs,
                                voices = voices,
                                kokoroModels = kokoroModels,
                                speaking = speaking,
                                onPrefsChange = onPrefsChange,
                                onSync = onSync,
                                onSpeak = onSpeak,
                            )
                        }
                    }
                }
            }
        }
        }
    }
    }
}

@Composable
private fun SettingsControls(
    overlay: ReaderOverlay,
    prefs: ReaderPreferences,
    voices: List<TtsVoice>,
    kokoroModels: List<KokoroModel>,
    speaking: Boolean,
    onPrefsChange: (ReaderPreferences) -> Unit,
    onSync: () -> Unit,
    onSpeak: () -> Unit,
) {
    // 拖动中的本地预览值；null 表示未拖动（跟随真实 prefs），松手才提交，避免连续写 DataStore + 重排全文
    var fontSizePreview by remember { mutableStateOf<Int?>(null) }
    var lineHeightPreview by remember { mutableStateOf<Double?>(null) }
    // 语速预览值；null 表示未拖动
    var ratePreview by remember { mutableStateOf<Float?>(null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (overlay == ReaderOverlay.Layout) {
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
            var marginPreview by remember { mutableStateOf<Int?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("页边距 ${marginPreview ?: prefs.pageMargin}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = (marginPreview ?: prefs.pageMargin).toFloat(),
                    onValueChange = { marginPreview = it.toInt() },
                    onValueChangeFinished = {
                        marginPreview?.let { onPrefsChange(prefs.copy(pageMargin = it)) }
                        marginPreview = null
                    },
                    valueRange = 0f..48f,
                    modifier = Modifier.weight(1f),
                )
            }
            // 翻页模式
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("翻页", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = !prefs.vertical,
                    onClick = { onPrefsChange(prefs.copy(vertical = false)) },
                    label = { Text("左右") },
                )
                FilterChip(
                    selected = prefs.vertical,
                    onClick = { onPrefsChange(prefs.copy(vertical = true)) },
                    label = { Text("上下") },
                )
            }
            // 翻页动画方式
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("动画", style = MaterialTheme.typography.bodyMedium)
                listOf(
                    "none" to "无动画",
                    "slide" to "滑动",
                    "fade" to "渐变",
                    "ios_curl" to "iOS 仿真",
                    "ripple" to "水波纹",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = prefs.pageAnimation == value,
                        onClick = { onPrefsChange(prefs.copy(pageAnimation = value)) },
                        label = { Text(label) },
                    )
                }
            }
            // 水波纹动画参数：仅选中水波纹时显示，实时预览、松手提交，下一次翻页生效。
            if (prefs.pageAnimation == "ripple") {
                var rippleDurationPreview by remember { mutableStateOf<Int?>(null) }
                var rippleRatioPreview by remember { mutableStateOf<Int?>(null) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("时长", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${rippleDurationPreview ?: prefs.rippleDurationMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.Center,
                    )
                    Slider(
                        value = (rippleDurationPreview ?: prefs.rippleDurationMs).toFloat(),
                        onValueChange = { rippleDurationPreview = it.toInt() },
                        onValueChangeFinished = {
                            rippleDurationPreview?.let { onPrefsChange(prefs.copy(rippleDurationMs = it)) }
                            rippleDurationPreview = null
                        },
                        valueRange = 100f..600f,
                        steps = 50,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("过渡区", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${rippleRatioPreview ?: prefs.rippleRatio}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.Center,
                    )
                    Slider(
                        value = (rippleRatioPreview ?: prefs.rippleRatio).toFloat(),
                        onValueChange = { rippleRatioPreview = it.toInt() },
                        onValueChangeFinished = {
                            rippleRatioPreview?.let { onPrefsChange(prefs.copy(rippleRatio = it)) }
                            rippleRatioPreview = null
                        },
                        valueRange = 10f..80f,
                        steps = 70,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedButton(
                    onClick = { onPrefsChange(prefs.copy(rippleDurationMs = 280, rippleRatio = 35)) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Text("恢复默认参数", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (overlay == ReaderOverlay.Theme) {
            ReaderThemeControls(prefs, onPrefsChange)
        }
        if (overlay == ReaderOverlay.Speech) {
            Button(
                onClick = onSpeak,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                Icon(
                    imageVector = if (speaking) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(if (speaking) {
                    if (prefs.speechEngine == "network_tts") "暂停朗读" else "停止朗读"
                } else "开始朗读")
            }
            Spacer(Modifier.height(8.dp))
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
            val kokoroAvailable = SpeechRuntime.kokoroAvailable
            val networkTtsAvailable = SpeechRuntime.networkTtsAvailable && SpeechRuntime.networkTtsRuleId != null
            val activeEngine = prefs.effectiveSpeechEngine(kokoroAvailable, networkTtsAvailable)
            val engines = listOfNotNull(
                "system" to "系统 TTS",
                if (kokoroAvailable) "kokoro" to "Kokoro" else null,
                if (networkTtsAvailable) "network_tts" to "网络 TTS" else null,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("引擎", style = MaterialTheme.typography.bodyMedium)
                engines.forEach { (tag, label) ->
                    val selected = when {
                        tag == "system" -> activeEngine == SpeechEngineType.SYSTEM_TTS
                        tag == "kokoro" -> activeEngine == SpeechEngineType.KOKORO
                        else -> activeEngine == SpeechEngineType.NETWORK_TTS
                    }
                    FilterChip(
                        selected = selected,
                        onClick = { onPrefsChange(prefs.copy(speechEngine = tag)) },
                        label = { Text(label) },
                    )
                }
            }
            if (activeEngine == SpeechEngineType.KOKORO && kokoroModels.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("模型", style = MaterialTheme.typography.bodyMedium)
                    kokoroModels.forEach { model ->
                        FilterChip(
                            selected = prefs.kokoroModel == model.id,
                            onClick = { onPrefsChange(prefs.copy(kokoroModel = model.id)) },
                            label = { Text(model.label) },
                        )
                    }
                }
                KokoroSpeakerPreference(prefs.kokoroSpeaker) { id ->
                    onPrefsChange(prefs.copy(kokoroSpeaker = id))
                }
            }
            if (activeEngine == SpeechEngineType.NETWORK_TTS) {
                com.yuezhou.reader.networktts.NetworkTtsVoicePreference(
                    prefs.networkTtsRuleId.takeIf { it != 0L } ?: SpeechRuntime.networkTtsRuleId,
                    voiceScope = prefs.networkTtsVoiceScope,
                    voiceGroup = prefs.networkTtsVoiceGroup,
                ) { id -> onPrefsChange(prefs.copy(networkTtsRuleId = id)) }
            }
            if (activeEngine == SpeechEngineType.SYSTEM_TTS) {
                SpeechVoicePreference(voices, prefs.speechVoice) { name ->
                    onPrefsChange(prefs.copy(speechVoice = name))
                }
            }
        }
        if (overlay == ReaderOverlay.More) {
            FilledTonalButton(
                onClick = onSync,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("阅读进度同步")
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
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(selectedLabel)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
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
        OutlinedButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

