package com.yuezhou.reader

import android.content.Intent
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
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有阅读中的书籍", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onLibrary) { Text("前往本地书库") }
        }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近阅读", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { history = true }) { Text("阅读历史") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.width(112.dp).height(156.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text(book.title, Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(book.title, style = MaterialTheme.typography.headlineSmall)
                if (author.isNotBlank()) Text(author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("已阅读 ${(100 * (book.percent ?: 0.0)).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { (book.percent ?: 0.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            (1..5).forEach { star ->
                TextButton(onClick = { update { dao.rate(book.bookId, if (profile?.rating == star) 0 else star) } },
                    modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                    Text(if ((profile?.rating ?: 0) >= star) "★" else "☆", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
        Button(onClick = {
            context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_BOOK_ID, book.bookId))
        }, modifier = Modifier.fillMaxWidth()) { Text("继续阅读") }
        HorizontalDivider()
        Text("阅读统计", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ReadingMetric("$days 天", "阅读天数")
            ReadingMetric(readingDuration(duration), "阅读时长")
            ReadingMetric(remaining?.let(::readingDuration) ?: "暂无数据", "预计剩余")
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
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("阅读历史") },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(readBooks, key = { it.bookId }) { entry ->
                TextButton(onClick = { selected = entry.bookId; history = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(entry.title, modifier = Modifier.fillMaxWidth())
                }
            }
        } }, confirmButton = { TextButton(onClick = { history = false }) { Text("关闭") } })
}

@Composable
private fun ReadingMetric(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
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
