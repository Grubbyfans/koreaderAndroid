package com.yuezhou.reader

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import kotlinx.coroutines.launch

/**
 * P1 最小阅读闭环：SAF 选书 → 导入 → foliate-js 渲染 → 捕获 CFI。
 * 完整阅读 UI（目录/搜索/设置）在后续节点按方案 §6.1 补齐。
 */
class ReaderActivity : ComponentActivity() {

    private val pickEpub = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onEpubPicked(uri)
    }

    private var pendingUri: Uri? = null
    private var onEpubReady: ((Uri) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReaderScreen(
                onPick = { pickEpub.launch(arrayOf("application/epub+zip")) },
                onEpubReadySetter = { setter -> onEpubReady = setter },
            )
        }
    }

    private fun onEpubPicked(uri: Uri) {
        onEpubReady?.invoke(uri)
    }
}

@Composable
private fun ReaderScreen(
    onPick: () -> Unit,
    onEpubReadySetter: ((Uri) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<ReaderWebView?>(null) }
    var currentCfi by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("请选择 EPUB 文件") }
    // 待打开的书名：WebView 尚未创建时先暂存，WebView 就绪后自动消费
    var pendingBook by remember { mutableStateOf<String?>(null) }

    // 监听 reader 事件
    val eventListener: (ReaderEvent) -> Unit = { event ->
        when (event) {
            is ReaderEvent.BookOpened ->
                status = "已打开：${event.title.ifBlank { "（无题）" }}（${event.sections} 节）"
            is ReaderEvent.Relocated -> currentCfi = event.cfi
            is ReaderEvent.Error -> status = "错误：${event.message}"
        }
    }

    // 把「打开某 URI」的动作注册给 Activity 的选书回调
    LaunchedEffect(Unit) {
        onEpubReadySetter { uri ->
            scope.launch {
                status = "导入中…"
                val result = EpubImporter.import(context, uri)
                result.fold(
                    onSuccess = { file ->
                        // 若 WebView 已就绪则直接打开；否则暂存，待 WebView 创建后消费
                        val wv = webView
                        if (wv != null) {
                            wv.openEpub(file.name)
                        } else {
                            pendingBook = file.name
                        }
                        status = "已加载：${file.name}"
                    },
                    onFailure = { e ->
                        status = "导入失败：${e.message}"
                    },
                )
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // edge-to-edge 下避开状态栏与导航栏，避免顶部按钮被状态栏遮挡
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Button(onClick = onPick) { Text("选择 EPUB") }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "CFI: ${currentCfi ?: "（未捕获）"}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        ReaderWebView(ctx).also { wv ->
                            wv.eventListener = eventListener
                            webView = wv
                            // WebView 创建完成后，若已有待打开的书，立即打开
                            pendingBook?.let { name ->
                                pendingBook = null
                                wv.openEpub(name)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
