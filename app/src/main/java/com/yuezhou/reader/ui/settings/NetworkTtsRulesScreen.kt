package com.yuezhou.reader.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.networktts.NetworkTtsAudition
import com.yuezhou.reader.reader.networktts.NetworkTtsRuleRepository
import com.yuezhou.reader.ui.components.LibraryPageHeading
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络 TTS 规则管理（设置 → 语音朗读 → 网络 TTS 规则管理）。
 *
 * 阶段 A 能力：导入（文件/剪贴板/链接/文本）、新建、编辑、删除、导出、单规则试听。
 * 规则字段与 Legado 原版一致；导入保留原始 JSON，导出以编辑后的字段覆盖且不带凭证。
 */
@Composable
internal fun NetworkTtsRulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { NetworkTtsRuleRepository(context) }
    val audition = remember(context) { NetworkTtsAudition(context) }
    DisposableEffect(audition) { onDispose { audition.release() } }

    var rules by remember { mutableStateOf<List<HttpTTS>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HttpTTS?>(null) }
    var auditioning by remember { mutableStateOf<HttpTTS?>(null) }
    var deleteTarget by remember { mutableStateOf<HttpTTS?>(null) }

    fun reload() {
        scope.launch {
            rules = runCatching { repository.list() }.getOrDefault(emptyList())
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                message = "读取文件失败"
            } else {
                message = importText(repository, text)
                reload()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text(
                "网络 TTS 规则",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (loading) CircularProgressIndicator(Modifier.height(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryPageHeading("网络 TTS 规则")
            Text(
                "兼容 Legado 原版网络朗读规则；阶段 A 仅提供规则管理与单条试听。",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { importOpen = true }) { Text("导入") }
                TextButton(onClick = { fileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text("从文件导入")
                }
                TextButton(onClick = {
                    scope.launch {
                        val exported = runCatching { repository.exportAll() }.getOrNull()
                        if (exported.isNullOrBlank()) {
                            message = "暂无可导出的规则"
                        } else {
                            copyToClipboard(context, exported)
                            message = "已复制全部规则到剪贴板（不含登录凭证）"
                        }
                    }
                }) { Text("导出到剪贴板") }
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            }
        }

        if (rules.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有规则，点击「导入」添加", color = YuezhouColors.TextSecondary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        onAudition = { auditioning = rule },
                        onEdit = { editing = rule },
                        onDelete = { deleteTarget = rule },
                        onExport = {
                            scope.launch {
                                val text = runCatching { repository.exportOne(rule.id) }.getOrNull()
                                if (text.isNullOrBlank()) {
                                    message = "导出失败"
                                } else {
                                    copyToClipboard(context, text)
                                    message = "已复制「${rule.name}」到剪贴板（不含登录凭证）"
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (importOpen) {
        ImportDialog(
            onDismiss = { importOpen = false },
            onImportText = { text ->
                importOpen = false
                scope.launch {
                    message = importText(repository, text)
                    reload()
                }
            },
        )
    }

    editing?.let { rule ->
        RuleEditDialog(
            initial = rule,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                scope.launch {
                    val saved = updated.copy(lastUpdateTime = System.currentTimeMillis())
                    runCatching { repository.save(saved) }
                        .onSuccess { message = "已保存「${saved.name}」" }
                        .onFailure { message = "保存失败：${it.message}" }
                    reload()
                }
            },
        )
    }

    auditioning?.let { rule ->
        AuditionDialog(
            rule = rule,
            audition = audition,
            onDismiss = {
                audition.stop()
                auditioning = null
            },
        )
    }

    deleteTarget?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除规则") },
            text = { Text("确定删除「${rule.name}」？该操作不影响已试听缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        runCatching { repository.delete(rule.id) }
                            .onSuccess { message = "已删除「${rule.name}」" }
                            .onFailure { message = "删除失败：${it.message}" }
                        reload()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun RuleRow(
    rule: HttpTTS,
    onAudition: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(YuezhouColors.Surface)
            .padding(12.dp),
    ) {
        Text(rule.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            rule.url,
            style = MaterialTheme.typography.bodySmall,
            color = YuezhouColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val extras = buildList {
            if (rule.pauseDuration > 0) add("停顿 ${rule.pauseDuration}ms")
            if (!rule.contentType.isNullOrBlank()) add("类型 ${rule.contentType}")
            if (!rule.loginUrl.isNullOrBlank() || !rule.loginUi.isNullOrBlank()) add("需登录")
            if (!rule.concurrentRate.isNullOrBlank() && rule.concurrentRate != "0") add("并发 ${rule.concurrentRate}")
        }
        if (extras.isNotEmpty()) {
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = YuezhouColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onAudition) { Text("试听") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onExport) { Text("导出") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImportText: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入规则") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "支持单条规则 JSON、规则数组，或 Legado 分享链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("从链接导入（https://...）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(enabled = !busy && url.isNotBlank(), onClick = {
                        busy = true
                        error = null
                        scope.launch {
                            val fetched = withContext(Dispatchers.IO) { fetchText(url) }
                            busy = false
                            if (fetched.isNullOrBlank()) {
                                error = "下载失败，请检查链接或网络"
                            } else {
                                onImportText(fetched)
                            }
                        }
                    }) { Text("下载并导入") }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("规则 JSON 文本") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row {
                    TextButton(onClick = {
                        val clip = readClipboard(context)
                        if (clip.isNullOrBlank()) error = "剪贴板为空" else text = clip
                    }) { Text("从剪贴板粘贴") }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onImportText(text) }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RuleEditDialog(
    initial: HttpTTS,
    onDismiss: () -> Unit,
    onSave: (HttpTTS) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var contentType by remember { mutableStateOf(initial.contentType.orEmpty()) }
    var pauseDuration by remember { mutableStateOf(initial.pauseDuration.toString()) }
    var concurrentRate by remember { mutableStateOf(initial.concurrentRate.orEmpty()) }
    var header by remember { mutableStateOf(initial.header.orEmpty()) }
    var jsLib by remember { mutableStateOf(initial.jsLib.orEmpty()) }
    var loginUrl by remember { mutableStateOf(initial.loginUrl.orEmpty()) }
    var loginUi by remember { mutableStateOf(initial.loginUi.orEmpty()) }
    var loginCheckJs by remember { mutableStateOf(initial.loginCheckJs.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.rawJson == null) "新建规则" else "编辑规则") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                EditField("名称", name, singleLine = true) { name = it }
                EditField("URL 规则", url, minLines = 3) { url = it }
                EditField("Content-Type 正则（可空）", contentType, singleLine = true) { contentType = it }
                EditField("段间停顿（毫秒，0-10000）", pauseDuration, singleLine = true) { pauseDuration = it }
                EditField("并发率（0 或 1500 或 3/1000）", concurrentRate, singleLine = true) { concurrentRate = it }
                EditField("请求头 JSON / @js（可空）", header, minLines = 2) { header = it }
                EditField("jsLib（可空）", jsLib, minLines = 3) { jsLib = it }
                EditField("登录脚本 loginUrl（可空）", loginUrl, minLines = 2) { loginUrl = it }
                EditField("登录表单 loginUi（可空）", loginUi, minLines = 2) { loginUi = it }
                EditField("登录检查 loginCheckJs（可空）", loginCheckJs, minLines = 2) { loginCheckJs = it }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank() || url.isBlank()) {
                    error = "名称与 URL 规则不能为空"
                    return@TextButton
                }
                val pause = pauseDuration.toIntOrNull()?.coerceIn(0, 10_000) ?: 0
                onSave(
                    initial.copy(
                        name = name.trim(),
                        url = url.trim(),
                        contentType = contentType.ifBlank { null },
                        pauseDuration = pause,
                        concurrentRate = concurrentRate.ifBlank { "0" },
                        header = header.ifBlank { null },
                        jsLib = jsLib.ifBlank { null },
                        loginUrl = loginUrl.ifBlank { null },
                        loginUi = loginUi.ifBlank { null },
                        loginCheckJs = loginCheckJs.ifBlank { null },
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EditField(
    label: String,
    value: String,
    singleLine: Boolean = false,
    minLines: Int = 1,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AuditionDialog(
    rule: HttpTTS,
    audition: NetworkTtsAudition,
    onDismiss: () -> Unit,
) {
    val state by audition.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("你好，这是网络朗读规则试听。") }
    var speed by remember { mutableStateOf(5f) }
    val playing = state.phase == NetworkTtsAudition.Phase.PLAYING
    val busy = state.phase == NetworkTtsAudition.Phase.SYNTHESIZING

    LaunchedEffect(state.phase) {
        while (state.phase == NetworkTtsAudition.Phase.PLAYING && isActive) {
            audition.refreshProgress()
            delay(300)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("试听 · ${rule.name}") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("试听文本") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("语速参数 speakSpeed = ${speed.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0f..20f, steps = 19)
                state.message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.phase == NetworkTtsAudition.Phase.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            YuezhouColors.TextSecondary
                        },
                    )
                }
                if (state.durationMs > 0) {
                    Text(
                        "进度 ${state.positionMs / 1000}s / ${state.durationMs / 1000}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(16.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                        Text("合成中…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    audition.audition(rule, text, speed.toInt())
                }) { Text(if (state.phase == NetworkTtsAudition.Phase.IDLE) "播放" else "重新合成") }
                TextButton(
                    enabled = state.phase == NetworkTtsAudition.Phase.PLAYING ||
                        state.phase == NetworkTtsAudition.Phase.PAUSED,
                    onClick = { audition.togglePlayPause() },
                ) { Text(if (playing) "暂停" else "继续") }
                TextButton(
                    enabled = state.phase != NetworkTtsAudition.Phase.IDLE,
                    onClick = { audition.stop() },
                ) { Text("停止") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private suspend fun importText(repository: NetworkTtsRuleRepository, text: String): String {
    val outcome = runCatching { repository.import(text) }.getOrElse {
        return "导入失败：${it.message}"
    }
    return buildString {
        append("导入 ${outcome.imported} 条")
        if (outcome.skipped > 0) append("，跳过重复 ${outcome.skipped} 条")
        if (outcome.errors.isNotEmpty()) {
            append("，失败 ${outcome.errors.size} 条：")
            append(outcome.errors.take(3).joinToString("；"))
        }
    }
}

private fun fetchText(url: String): String? {
    return runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("network_tts_rule", text))
}

private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}
