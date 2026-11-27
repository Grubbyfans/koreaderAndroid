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
import com.yuezhou.reader.YuezhouColors
private data class TocRow(
    val key: String,
    val item: TocItem,
    val ancestors: List<String>,
)

@Composable
internal fun TocDrawer(
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
    var pendingScrollKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(isOpen, active?.key, rows) {
        pendingScrollKey = null
        if (isOpen && active != null) {
            expanded = expanded + active.ancestors
            pendingScrollKey = active.key
        }
    }
    // 等待展开后的行进入 LazyColumn，再定位；旧列表上的滚动请求可能被截断或被 key 锚定覆盖。
    LaunchedEffect(isOpen, pendingScrollKey, visible) {
        val target = pendingScrollKey
        if (isOpen && target != null) {
            val index = visible.indexOfFirst { it.key == target }
            if (index >= 0) {
                listState.scrollToItem(index)
                pendingScrollKey = null
            }
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
                                    .background(if (isExpanded) YuezhouColors.BrandContainer else MaterialTheme.colorScheme.surfaceVariant)
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
                            color = if (current) YuezhouColors.BrandDark else if (depth == 0) YuezhouColors.TextPrimary else YuezhouColors.TextSecondary,
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
