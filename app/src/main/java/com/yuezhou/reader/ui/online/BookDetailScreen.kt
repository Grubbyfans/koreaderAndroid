package com.yuezhou.reader.ui.online

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.model.BookFormatState
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.ui.components.coverPlaceholderColor

/**
 * 检索型书源详情：展示搜索结果已有字段，并异步补全服务端详情。
 * 只有格式确认且具备 id:hash 的书籍才允许原生下载，其余状态明确提示并禁用按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    book: OnlineBook,
    state: ProviderUiState,
    vm: OnlineLibraryViewModel,
    providerId: String,
    onBack: () -> Unit,
    onDownloadQueued: () -> Unit,
    interactionsEnabled: Boolean = true,
) {
    var descriptionExpanded by remember(book.remoteId) { mutableStateOf(false) }
    val downloadBlockedReason = when {
        book.formatState != BookFormatState.CONFIRMED ->
            "格式未确认（${book.formatLabel}），暂不支持原生下载"
        book.remoteId.split(":", limit = 2).let { it.size != 2 || it.any(String::isBlank) } ->
            "缺少服务端 id:hash 标识，暂不支持原生下载，可使用网页搜索"
        else -> null
    }
    val context = LocalContext.current
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* 未授权也继续下载，仅不显示进度通知 */ }

    Scaffold(
        containerColor = YuezhouColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("书籍详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = YuezhouColors.Surface,
                    titleContentColor = YuezhouColors.TextPrimary,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
        ) {
            if (state.detailLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(134.dp),
                ) {
                    // 复用心形封面占位色，避免详情与列表视觉割裂；封面字节由列表页按 URL 缓存。
                    val bytes = vm.coverOf(book.coverUrl)
                    if (bytes != null) {
                        androidx.compose.foundation.Image(
                            bitmap = decodeCoverBitmap(bytes),
                            contentDescription = book.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Surface(
                            Modifier.fillMaxSize(),
                            color = coverPlaceholderColor(book.title),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    book.title.take(1).ifBlank { "书" },
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineMedium,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        book.authors.joinToString("、").ifBlank { "作者未知" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = YuezhouColors.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        buildString {
                            append(book.formatLabel)
                            book.fileSize?.let { append(" · ${formatDetailSize(it)}") }
                            book.language?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = YuezhouColors.Divider)
            Spacer(Modifier.height(12.dp))

            DetailLine("出版年份", book.publishYear?.toString() ?: "未知")
            DetailLine("出版社", book.publisher?.ifBlank { null } ?: "未知")
            DetailLine("ISBN", book.isbn?.ifBlank { null } ?: "未知")
            DetailLine("来源", "Z-Library")
            DetailLine("文件名", book.fileName)

            Spacer(Modifier.height(12.dp))
            val description = book.description?.ifBlank { null }
            Text("简介", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (description == null) {
                Text(
                    "暂无简介${if (state.detailLoading) "，正在获取完整详情…" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = YuezhouColors.TextSecondary,
                )
            } else {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { descriptionExpanded = !descriptionExpanded },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = YuezhouColors.Divider)
            Spacer(Modifier.height(12.dp))
            Text("下载完成后自动导入本地书库", style = MaterialTheme.typography.bodyMedium)

            state.detailMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            downloadBlockedReason?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    vm.enqueueDownload(book, providerId, onDownloadQueued)
                },
                enabled = downloadBlockedReason == null && interactionsEnabled && !state.downloadEnqueuing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.downloadEnqueuing) "正在创建下载任务…" else "下载书籍")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "下载将校验文件并按文件名判断同名书籍；覆盖需你确认。下载可能消耗额度。",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = YuezhouColors.TextSecondary,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun decodeCoverBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap {
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
    return bitmap.asImageBitmap()
}

private fun formatDetailSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024f)
    else -> "${bytes}B"
}
