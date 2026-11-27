package com.yuezhou.reader

import android.content.Intent
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.db.*
import com.yuezhou.reader.reader.ReadingStore
import com.yuezhou.reader.ui.components.*
import androidx.compose.ui.text.style.TextOverflow
import com.yuezhou.reader.ui.shelf.rememberShelfCover
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun ReadingHome(books: List<LibraryStore.Entry>, store: LibraryStore, refresh: Int, onLibrary: () -> Unit) {
    val context = LocalContext.current
    val dao = remember { ReadingStore.dao(context) }
    val profiles by remember { dao.profiles() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 旧书复用已有进度判定已读，首次时间未知时不回填虚构日期。
    val readBooks = remember(books, profiles) {
        books.filter { it.lastReadAt != null || profiles.any { p -> p.bookId == it.bookId } }
            .sortedByDescending { b -> maxOf(b.lastReadAt ?: 0, profiles.find { it.bookId == b.bookId }?.lastRead ?: 0) }
    }
    LaunchedEffect(readBooks.firstOrNull()?.bookId) { selected = null }
    val book = readBooks.find { it.bookId == selected } ?: readBooks.firstOrNull()
    if (book == null) {
        com.yuezhou.reader.ui.shelf.EmptyState(androidx.compose.material.icons.Icons.AutoMirrored.Filled.List, "还没有阅读中的书籍", "从书库打开一本书，继续你的阅读", "前往本地书库", onLibrary)
        return
    }
    val profile = profiles.find { it.bookId == book.bookId }
    val sessionFlow = remember(book.bookId) { dao.sessions(book.bookId) }
    val sessions by sessionFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var author by remember(book.bookId) { mutableStateOf("") }
    LaunchedEffect(book.bookId, refresh) { author = store.authorsFor(book).joinToString("、") }
    val cover = rememberShelfCover(book, store, 512)
    val duration = sessions.sumOf { it.durationMs }
    val days = sessions.filter { it.durationMs > 0 }.map {
        Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
    }.distinct().size
    val sampled = sessions.filter {
        val delta = (it.endProgress ?: 0.0) - (it.startProgress ?: 0.0)
        it.startProgress != null && it.endProgress != null && delta >= 0 && delta <= 0.02
    }
    val advance = sampled.sumOf { it.endProgress!! - it.startProgress!! }
    val sampleTime = sampled.sumOf { it.durationMs }
    val remaining = if (sampleTime >= 60_000 && advance >= 0.005 && book.percent != null && book.percent!! < 1)
        (sampleTime / advance * (1 - book.percent!!)).takeIf { it.isFinite() }?.toLong() else null
    fun update(action: suspend () -> Unit) {
        scope.launch {
            try {
                // 对旧书，仅从本次使用档案功能开始记录可知时间。
                val now = System.currentTimeMillis()
                dao.insertProfile(ReadingProfile(book.bookId, firstRead = null, lastRead = book.lastReadAt ?: now))
                action()
            } catch (e: Exception) { error = "保存失败：${e.message}" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = YuezhouDimens.PageInset).padding(top = YuezhouDimens.GapSmall, bottom = YuezhouDimens.GapMedium),
        verticalArrangement = Arrangement.spacedBy(YuezhouDimens.SectionGap)) {
        Column(verticalArrangement = Arrangement.spacedBy(YuezhouDimens.Gap)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("最近阅读", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { history = true }) { Text("阅读历史") }
            }
            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(YuezhouDimens.PageInset)) {
                    Box(Modifier.width(96.dp).height(144.dp).clip(MaterialTheme.shapes.extraSmall), contentAlignment = Alignment.Center) {
                        if (cover != null) Image(cover, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else BookCoverPlaceholder(book.title, book.bookId)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(YuezhouDimens.GapMedium)) {
                        Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodyMedium, color = YuezhouColors.TextSecondary)
                        Text("已阅读 ${(100 * (book.percent ?: 0.0)).toInt()}%", style = MaterialTheme.typography.bodySmall)
                        BookReadingProgress(book.percent ?: 0.0, Modifier.fillMaxWidth())
                    }
                }
                Button(onClick = {
                    context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.bookId))
                }, modifier = Modifier.fillMaxWidth()) { Text("继续阅读") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                (1..5).forEach { star ->
                    TextButton(onClick = { update { dao.rate(book.bookId, if (profile?.rating == star) 0 else star) } },
                        modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(if ((profile?.rating ?: 0) >= star) "★" else "☆", style = MaterialTheme.typography.titleLarge, color = YuezhouColors.TextSecondary)
                    }
                }
            }
            HorizontalDivider()
        }
        Text("阅读统计", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ReadingMetric("$days 天", "阅读天数", Modifier.weight(1f))
            ReadingMetric(readingDuration(duration), "阅读时长", Modifier.weight(1f))
            ReadingMetric(remaining?.let(::readingDuration) ?: "暂无数据", "预计剩余", Modifier.weight(1f))
        }
        ReadingDate("首次阅读", sessions.firstOrNull()?.startTime ?: profile?.firstRead)
        ReadingDate("最近阅读", maxOf(book.lastReadAt ?: 0, profile?.lastRead ?: 0).takeIf { it > 0 })
        HorizontalDivider()
        Text("书籍状态", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("在读", "搁置", "读完").forEach { status ->
                FilterChip(selected = (profile?.status ?: "在读") == status,
                    onClick = { update { dao.status(book.bookId, status) } }, label = { Text(status) })
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (history) AppBottomSheet("阅读历史", onDismiss = { history = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            items(readBooks, key = { it.bookId }) { entry ->
                val historyCover = rememberShelfCover(entry, store, 128)
                Surface(onClick = { selected = entry.bookId; history = false }, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(vertical = YuezhouDimens.GapMedium),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(YuezhouDimens.GapMedium)) {
                        Box(Modifier.width(40.dp).height(60.dp).clip(MaterialTheme.shapes.extraSmall)) {
                            if (historyCover != null) Image(historyCover, entry.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else BookCoverPlaceholder(entry.title, entry.bookId)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(YuezhouDimens.GapSmall)) {
                            Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            BookReadingProgress(entry.percent ?: 0.0, Modifier.fillMaxWidth())
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ReadingMetric(value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(end = YuezhouDimens.GapSmall), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val valueStyle = MaterialTheme.typography.headlineSmall
        Box(Modifier.heightIn(min = with(androidx.compose.ui.platform.LocalDensity.current) { valueStyle.lineHeight.toDp() }),
            contentAlignment = Alignment.BottomStart) {
            Text(value, style = if (value == "暂无数据") MaterialTheme.typography.bodyLarge else valueStyle)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun ReadingDate(label: String, at: Long?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) } ?: "暂无数据")
    }
}
private fun readingDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
}
