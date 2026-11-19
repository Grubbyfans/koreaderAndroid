package com.yuezhou.reader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import kotlinx.coroutines.launch

/**
 * 阅读界面：从书架点击进入（EXTRA_BOOK_ID），或书库为空时通过 SAF 选书。
 * foliate-js 渲染正文，捕获 CFI 并持久化阅读进度。
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
        // 在 onCreate 阶段注册 SAF 选书回调
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var title by remember { mutableStateOf(bookId ?: "阅读") }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var currentCfi by remember { mutableStateOf<String?>(null) }
    var currentFraction by remember { mutableStateOf<Float?>(null) }
    // 待打开的书：WebView 尚未创建时先暂存，WebView 就绪后自动消费
    var pendingOpen by remember { mutableStateOf<Pair<String, String?>?>(null) }

    val store = remember { LibraryStore(context) }

    // 监听 reader 事件
    val eventListener: (ReaderEvent) -> Unit = { event ->
        when (event) {
            is ReaderEvent.BookOpened -> {
                title = event.title.ifBlank { bookId ?: "阅读" }
                loading = false
                status = null
            }
            is ReaderEvent.Relocated -> {
                currentCfi = event.cfi
                currentFraction = event.fraction
                // 位置变化时异步持久化进度（用局部变量避免跨模块 smart cast 失败）
                val cfi = event.cfi
                val fraction = event.fraction
                if (cfi != null && bookId != null) {
                    scope.launch {
                        store.saveProgress(bookId, cfi, fraction?.toDouble())
                    }
                }
            }
            is ReaderEvent.Error -> {
                loading = false
                status = "错误：${event.message}"
            }
        }
    }

    // 打开书：优先按 bookId 从书库打开，其次 SAF 选书
    fun openBookFile(fileName: String, restoreCfi: String? = null) {
        val wv = webView
        if (wv != null) {
            wv.openEpub(fileName, restoreCfi)
        } else {
            pendingOpen = fileName to restoreCfi
        }
    }

    // 初始化：有 bookId 则从书库打开（含恢复进度）
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

    // SAF 选书回调：导入并打开
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(title, maxLines = 1)
                },
                navigationIcon = {
                    TextButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                        Text("返回")
                    }
                },
                actions = {
                    TextButton(onClick = onImport) {
                        Text("导入")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 状态栏
            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            currentFraction?.let { frac ->
                Text(
                    text = "进度 ${(frac * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 阅读正文
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        ReaderWebView(ctx).also { wv ->
                            wv.eventListener = eventListener
                            webView = wv
                            // WebView 创建完成后，若已有待打开的书，立即打开
                            pendingOpen?.let { (name, cfi) ->
                                pendingOpen = null
                                wv.openEpub(name, cfi)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
