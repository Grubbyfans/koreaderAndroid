package com.yuezhou.reader.ui.online

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.api.ServerProbeClient
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ProbeStatus
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ServerMode
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.reader.online.config.ServerConfigController
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import kotlinx.coroutines.launch

/**
 * 服务器配置页（服务器发现增量方案 §2/§D）。
 *
 * 手动/自动模式切换 + 候选列表增删改、启停、排序、单项/全部检测、重新选择、导入导出与恢复默认。
 * 视觉与静读主题一致（YuezhouColors 白色卡片 + 浅灰描边）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { ServerConfigController(ZLibraryConfigStore(context)) }

    var config by remember { mutableStateOf(controller.loadConfig()) }
    var probeResults by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }
    var selectedBaseUrl by remember { mutableStateOf(controller.loadState().lastAvailableBaseUrl) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerCandidate?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ServerImportPreview?>(null) }
    var replaceImport by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = readText(context, uri)
            if (text == null) {
                message = "读取导入文件失败"
                return@launch
            }
            controller.previewImport(text).fold(
                onSuccess = { preview = it },
                onFailure = { message = "导入解析失败：${it.message ?: "未知错误"}" },
            )
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = writeText(context, uri, controller.exportJson(config))
            message = if (ok) "列表已导出（不含凭证）" else "导出失败"
        }
    }

    fun persist(updated: ZLibraryConfig) {
        scope.launch {
            busy = true
            controller.saveConfig(updated).fold(
                onSuccess = {
                    config = it
                    probeResults = emptyMap()
                    message = "配置已保存"
                },
                onFailure = { message = "保存失败：${it.message ?: "未知错误"}" },
            )
            busy = false
        }
    }

    fun saveCandidate(candidate: ServerCandidate, isNew: Boolean) {
        val list = config.servers.candidates.toMutableList()
        if (isNew) list.add(candidate) else {
            val index = list.indexOfFirst { it.id == candidate.id }
            if (index >= 0) list[index] = candidate
        }
        persist(
            config.copy(
                servers = config.servers.copy(
                    candidates = list.sortedBy { it.priority }.mapIndexed { i, c -> c.copy(priority = i + 1) },
                ),
            ),
        )
    }

    fun move(candidate: ServerCandidate, delta: Int) {
        val sorted = config.servers.candidates.sortedBy { it.priority }.toMutableList()
        val index = sorted.indexOfFirst { it.id == candidate.id }
        val target = index + delta
        if (index < 0 || target !in sorted.indices) return
        sorted.add(target, sorted.removeAt(index))
        persist(config.copy(servers = config.servers.copy(candidates = sorted.mapIndexed { i, c -> c.copy(priority = i + 1) })))
    }

    fun probeOne(candidate: ServerCandidate) {
        scope.launch {
            val result = ServerProbeClient.probe(candidate.baseUrl, config, candidate.id)
            probeResults = probeResults + (candidate.id to result)
            message = "单项检测完成"
        }
    }

    fun detectAll() {
        scope.launch {
            busy = true
            message = "正在检测候选服务器…"
            controller.detectAll(config, onResult = { result ->
                probeResults = probeResults + (result.candidateId to result)
            }).also { decision ->
                selectedBaseUrl = decision.selectedBaseUrl
                message = decision.selectedBaseUrl?.let { "已选择：$it" } ?: (decision.reason ?: "检测完成，暂无可用服务器")
            }
            busy = false
        }
    }

    Scaffold(
        containerColor = YuezhouColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("服务器配置") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // 模式卡片
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("服务器模式", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = ServerMode.fromWire(config.servers.mode) == ServerMode.MANUAL,
                            onClick = { persist(config.copy(servers = config.servers.copy(mode = ServerMode.MANUAL.wire))) },
                            label = { Text("手动指定") },
                            colors = yuezhouFilterChipColors(),
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        FilterChip(
                            selected = ServerMode.fromWire(config.servers.mode) == ServerMode.AUTO,
                            onClick = { persist(config.copy(servers = config.servers.copy(mode = ServerMode.AUTO.wire))) },
                            label = { Text("自动检测") },
                            colors = yuezhouFilterChipColors(),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前服务器：${selectedBaseUrl ?: config.servers.baseUrls.firstOrNull() ?: "未选择"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "最近检测：${formatTime(controller.loadState().lastCheckedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (ServerMode.fromWire(config.servers.mode) == ServerMode.MANUAL) {
                ManualServerCard(
                    config = config,
                    onSave = { url ->
                        persist(config.copy(servers = config.servers.copy(manualBaseUrl = url)))
                    },
                    onTest = { url, id ->
                        scope.launch {
                            val result = ServerProbeClient.probe(url, config, id)
                            probeResults = probeResults + (id to result)
                        }
                    },
                    probeResults = probeResults,
                )
            } else {
                CandidateListCard(
                    config = config,
                    probeResults = probeResults,
                    selectedBaseUrl = selectedBaseUrl,
                    busy = busy,
                    onAdd = { showAdd = true },
                    onEdit = { editing = it },
                    onToggle = { candidate ->
                        persist(
                            config.copy(
                                servers = config.servers.copy(
                                    candidates = config.servers.candidates.map {
                                        if (it.id == candidate.id) it.copy(enabled = !it.enabled) else it
                                    },
                                ),
                            ),
                        )
                    },
                    onMove = { candidate, delta -> move(candidate, delta) },
                    onDelete = { candidate ->
                        persist(
                            config.copy(
                                servers = config.servers.copy(
                                    candidates = config.servers.candidates.filter { it.id != candidate.id }
                                        .mapIndexed { i, c -> c.copy(priority = i + 1) },
                                ),
                            ),
                        )
                    },
                    onProbe = { candidate -> probeOne(candidate) },
                    onDetectAll = { detectAll() },
                    onReselect = { detectAll() },
                    onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                    onExport = { exportLauncher.launch("zlibrary-servers.json") },
                    onReset = { confirmReset = true },
                )
            }

            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = YuezhouColors.Brand)
            }
        }
    }

    if (showAdd) {
        CandidateEditorDialog(
            title = "添加候选服务器",
            initial = ServerCandidate(controller.newCandidateId(), "https://", true, config.servers.candidates.size + 1),
            onDismiss = { showAdd = false },
            onSave = { candidate -> saveCandidate(candidate, true); showAdd = false },
        )
    }
    editing?.let { candidate ->
        CandidateEditorDialog(
            title = "编辑候选服务器",
            initial = candidate,
            onDismiss = { editing = null },
            onSave = { updated -> saveCandidate(updated, false); editing = null },
        )
    }
    preview?.let { p ->
        ImportPreviewDialog(
            preview = p,
            replace = replaceImport,
            onReplaceChange = { replaceImport = it },
            onDismiss = { preview = null },
            onConfirm = {
                scope.launch {
                    busy = true
                    controller.applyImport(p, replaceImport).fold(
                        onSuccess = {
                            config = it
                            probeResults = emptyMap()
                            message = "列表已${if (replaceImport) "替换" else "合并"}"
                        },
                        onFailure = { message = "应用导入失败：${it.message ?: "未知错误"}" },
                    )
                    busy = false
                    preview = null
                }
            },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复内置默认列表") },
            text = { Text("将用内置默认候选列表替换当前列表，不影响账号及其他书源设置。该操作可回滚。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        controller.resetServers().fold(
                            onSuccess = { config = it; probeResults = emptyMap(); message = "已恢复默认列表" },
                            onFailure = { message = "恢复失败：${it.message ?: "未知错误"}" },
                        )
                        confirmReset = false
                    }
                }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun yuezhouFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = YuezhouColors.BrandContainer,
    selectedLabelColor = YuezhouColors.BrandDark,
    containerColor = YuezhouColors.Surface,
    labelColor = YuezhouColors.TextSecondary,
)

private fun formatTime(timestamp: Long?): String = timestamp?.let {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    formatter.format(java.util.Date(it))
} ?: "尚未检测"

private suspend fun readText(context: android.content.Context, uri: Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
}

private suspend fun writeText(context: android.content.Context, uri: Uri, text: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    } catch (e: Exception) {
        false
    }
}
