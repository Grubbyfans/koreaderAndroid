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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ProbeStatus
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ServerMode
import com.yuezhou.reader.core.online.config.ServerUrlPolicy
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.reader.online.config.ServerConfigController
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import kotlinx.coroutines.launch

/**
 * 服务器管理页（服务器设置优化 §3/§4/§5/§8/§9/§10）。
 *
 * 单一二级页面：模式标签（自动选择/手动选择）共用同一份服务器列表；
 * 低频操作收进列表项 ⋮ 菜单与顶部 ⋮ 菜单；统一「检测服务器」入口；
 * 所有服务器配置操作自动保存，配置变更即作废旧探测缓存。
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
    var lastCheckedAt by remember { mutableStateOf(controller.loadState().lastCheckedAt) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServerCandidate?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerCandidate?>(null) }
    var preview by remember { mutableStateOf<ServerImportPreview?>(null) }
    var replaceImport by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var topMenuOpen by remember { mutableStateOf(false) }

    val mode = ServerMode.fromWire(config.servers.mode)
    val manualMode = mode == ServerMode.MANUAL
    val selectedId = config.servers.manualSelectedServerId

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

    /** 配置写盘失败时保持界面与原配置一致，并明确提示。 */
    fun persist(updated: ZLibraryConfig, afterSuccess: (ZLibraryConfig) -> Unit = {}) {
        scope.launch {
            busy = true
            controller.saveConfig(updated).fold(
                onSuccess = { saved ->
                    config = saved
                    probeResults = emptyMap()
                    lastCheckedAt = controller.loadState().lastCheckedAt
                    selectedBaseUrl = controller.loadState().lastAvailableBaseUrl
                    message = null
                    afterSuccess(saved)
                },
                onFailure = { message = "保存失败：${it.message ?: "未知错误"}（界面未更新）" },
            )
            busy = false
        }
    }

    /** 自动模式重新检测并选择；检测完成后刷新当前地址与状态。 */
    fun redetect() {
        scope.launch {
            busy = true
            message = "正在检测服务器…"
            val decision = controller.detectAll(config, onResult = { result ->
                probeResults = probeResults + (result.candidateId to result)
            })
            selectedBaseUrl = decision.selectedBaseUrl
            lastCheckedAt = controller.loadState().lastCheckedAt
            message = decision.selectedBaseUrl?.let { "已选择：$it" }
                ?: (decision.reason ?: "检测完成，暂无可用服务器")
            busy = false
        }
    }

    fun setMode(next: ServerMode) {
        if (next == mode) return
        val updated = if (next == ServerMode.MANUAL) {
            val suggestion = controller.suggestManualSelection(config, selectedBaseUrl)
            config.copy(
                servers = config.servers.copy(
                    mode = next.wire,
                    manualSelectedServerId = suggestion,
                ),
            )
        } else {
            config.copy(servers = config.servers.copy(mode = next.wire))
        }
        persist(updated)
        if (suggestionMissing(config, next, selectedBaseUrl)) {
            message = "请选择服务器：当前没有自动选中的可用服务器"
        }
    }

    fun saveCandidate(candidate: ServerCandidate, isNew: Boolean) {
        val duplicate = config.servers.candidates.any { other ->
            other.id != candidate.id &&
                normalizeUrl(other.baseUrl) == normalizeUrl(candidate.baseUrl)
        }
        if (duplicate) {
            message = "该地址已在服务器列表中"
            return
        }
        val list = config.servers.candidates.toMutableList()
        if (isNew) list.add(candidate) else {
            val index = list.indexOfFirst { it.id == candidate.id }
            if (index >= 0) list[index] = candidate
        }
        val sorted = list.sortedBy { it.priority }.mapIndexed { i, c -> c.copy(priority = i + 1) }
        persist(
            config.copy(servers = config.servers.copy(candidates = sorted)),
            afterSuccess = { probeResults = probeResults - candidate.id }, // 地址变更后标记未检测
        )
    }

    fun move(candidate: ServerCandidate, delta: Int) {
        val sorted = config.servers.candidates.sortedBy { it.priority }.toMutableList()
        val index = sorted.indexOfFirst { it.id == candidate.id }
        val target = index + delta
        if (index < 0 || target !in sorted.indices) return
        sorted.add(target, sorted.removeAt(index))
        // 排序只调整优先级，不中断当前连接；下一次自动检测按新优先级选择。
        persist(config.copy(servers = config.servers.copy(candidates = sorted.mapIndexed { i, c -> c.copy(priority = i + 1) })))
    }

    fun probeOne(candidate: ServerCandidate) {
        scope.launch {
            val result = controller.probeOne(candidate.baseUrl, candidate.id, config)
            probeResults = probeResults + (candidate.id to result)
            message = "检测完成：${candidate.baseUrl}"
        }
    }

    fun isCurrent(candidate: ServerCandidate): Boolean =
        if (manualMode) selectedId == candidate.id
        else selectedBaseUrl?.equals(candidate.baseUrl, ignoreCase = true) == true

    fun toggleEnabled(candidate: ServerCandidate) {
        val wasCurrent = isCurrent(candidate)
        val disablingCurrent = candidate.enabled && wasCurrent
        val newEnabled = !candidate.enabled
        val updated = config.copy(
            servers = config.servers.copy(
                candidates = config.servers.candidates.map {
                    if (it.id == candidate.id) it.copy(enabled = newEnabled) else it
                },
                // 手动模式禁用当前项：同一次写盘即清除选择，不残留指向禁用项的关系。
                manualSelectedServerId = if (manualMode && disablingCurrent) null
                    else config.servers.manualSelectedServerId,
            ),
        )
        persist(updated, afterSuccess = {
            if (disablingCurrent) {
                if (manualMode) {
                    message = "当前服务器已禁用，请重新选择"
                } else {
                    // 自动模式：清除旧缓存后检测剩余已启用服务器并重新选择。
                    redetect()
                }
            }
        })
    }

    fun deleteCandidate(candidate: ServerCandidate) {
        val wasCurrent = isCurrent(candidate)
        val updated = config.copy(
            servers = config.servers.copy(
                candidates = config.servers.candidates.filter { it.id != candidate.id }
                    .mapIndexed { i, c -> c.copy(priority = i + 1) },
                manualSelectedServerId = if (config.servers.manualSelectedServerId == candidate.id) null
                    else config.servers.manualSelectedServerId,
            ),
        )
        persist(updated, afterSuccess = {
            probeResults = probeResults - candidate.id
            if (wasCurrent) {
                if (manualMode) message = "当前服务器已删除，请重新选择"
                else redetect()
            }
        })
    }

    Scaffold(
        containerColor = YuezhouColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("服务器管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { topMenuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
                    }
                    DropdownMenu(expanded = topMenuOpen, onDismissRequest = { topMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("批量导入服务器") },
                            onClick = { topMenuOpen = false; importLauncher.launch(arrayOf("application/json", "text/plain")) },
                        )
                        DropdownMenuItem(
                            text = { Text("导出服务器列表") },
                            onClick = { topMenuOpen = false; exportLauncher.launch("zlibrary-servers.json") },
                        )
                        DropdownMenuItem(
                            text = { Text("恢复默认服务器列表") },
                            onClick = { topMenuOpen = false; confirmReset = true },
                        )
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // 服务器选择方式
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text("服务器选择方式", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = !manualMode,
                            onClick = { setMode(ServerMode.AUTO) },
                            label = { Text("自动选择") },
                            colors = yuezhouFilterChipColors(),
                            enabled = !busy,
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        FilterChip(
                            selected = manualMode,
                            onClick = { setMode(ServerMode.MANUAL) },
                            label = { Text("手动选择") },
                            colors = yuezhouFilterChipColors(),
                            enabled = !busy,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (manualMode) "始终使用选中的服务器，连接失败时不会自动切换。"
                        else "按优先级自动选择可用服务器，连接失败时自动切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 当前服务器状态
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    val currentSummary = currentServerSummary(manualMode, selectedBaseUrl, selectedId, probeResults, config, lastCheckedAt)
                    Text(currentSummary.label, style = MaterialTheme.typography.titleMedium, color = currentSummary.color)
                    if (currentSummary.address != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(currentSummary.address, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    }
                    if (currentSummary.detail != null) {
                        Text(currentSummary.detail, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            ServerListCard(
                candidates = config.servers.candidates,
                probeResults = probeResults,
                selectedBaseUrl = selectedBaseUrl,
                selectedId = selectedId,
                manualMode = manualMode,
                enabled = !busy,
                onAdd = { showAdd = true },
                onSelect = { candidate ->
                    persist(config.copy(servers = config.servers.copy(manualSelectedServerId = candidate.id)))
                },
                onToggle = { candidate -> toggleEnabled(candidate) },
                onMove = { candidate, delta -> move(candidate, delta) },
                onEdit = { editing = it },
                onDelete = { pendingDelete = it },
                onProbe = { candidate -> probeOne(candidate) },
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { redetect() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "检测中…" else "检测服务器") }

            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = YuezhouColors.Brand)
            }
        }
    }

    if (showAdd) {
        CandidateEditorDialog(
            title = "添加服务器",
            initial = ServerCandidate(controller.newCandidateId(), "https://", true, config.servers.candidates.size + 1),
            onDismiss = { showAdd = false },
            onSave = { candidate -> saveCandidate(candidate, true); showAdd = false },
        )
    }
    editing?.let { candidate ->
        CandidateEditorDialog(
            title = "编辑服务器地址",
            initial = candidate,
            onDismiss = { editing = null },
            onSave = { updated -> saveCandidate(updated, false); editing = null },
        )
    }
    pendingDelete?.let { candidate ->
        ConfirmDeleteDialog(
            candidate = candidate,
            onDismiss = { pendingDelete = null },
            onConfirm = { deleteCandidate(candidate); pendingDelete = null },
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
                        onSuccess = { imported ->
                            config = imported
                            probeResults = emptyMap()
                            selectedBaseUrl = controller.loadState().lastAvailableBaseUrl
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
        ConfirmResetDialog(
            onDismiss = { confirmReset = false },
            onConfirm = {
                scope.launch {
                    busy = true
                    controller.resetServers().fold(
                        onSuccess = { reset ->
                            config = reset
                            probeResults = emptyMap()
                            selectedBaseUrl = null
                            message = "已恢复默认服务器列表（空列表，可回滚）"
                        },
                        onFailure = { message = "恢复失败：${it.message ?: "未知错误"}" },
                    )
                    busy = false
                    confirmReset = false
                }
            },
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

/** 当前服务器状态摘要（§9）：区分未配置/未选择/未检测/检测中/连接正常/连接失败。 */
private data class CurrentSummary(
    val label: String,
    val address: String?,
    val detail: String?,
    val color: androidx.compose.ui.graphics.Color,
)

@Composable
private fun currentServerSummary(
    manualMode: Boolean,
    selectedBaseUrl: String?,
    selectedId: String?,
    probeResults: Map<String, ProbeResult>,
    config: ZLibraryConfig,
    lastCheckedAt: Long?,
): CurrentSummary {
    if (config.servers.candidates.isEmpty()) {
        return CurrentSummary("尚未连接服务器", null, "请添加服务器地址", YuezhouColors.TextSecondary)
    }
    if (manualMode && selectedId == null) {
        return CurrentSummary("请选择服务器", null, "手动模式下请选择一个已启用的服务器", YuezhouColors.TextSecondary)
    }
    if (!manualMode && selectedBaseUrl == null && config.servers.candidates.none { it.enabled }) {
        return CurrentSummary("无可用服务器", null, "没有已启用的服务器", MaterialTheme.colorScheme.error)
    }
    val address = if (manualMode) {
        config.servers.candidates.firstOrNull { it.id == selectedId }?.baseUrl
    } else {
        selectedBaseUrl ?: config.servers.baseUrls.firstOrNull()
    }
    if (address == null) {
        return CurrentSummary("尚未连接服务器", null, "自动检测后将显示当前服务器", YuezhouColors.TextSecondary)
    }
    val candidateId = config.servers.candidates.firstOrNull {
        it.baseUrl.equals(address, ignoreCase = true)
    }?.id
    val result = candidateId?.let { probeResults[it] }
    val status = result?.let { runCatching { ProbeStatus.valueOf(it.status) }.getOrNull() }
    val (label, color) = when (status) {
        ProbeStatus.CHECKING -> "检测中" to YuezhouColors.TextSecondary
        ProbeStatus.AVAILABLE -> "连接正常" to YuezhouColors.Brand
        ProbeStatus.TIMEOUT, ProbeStatus.NETWORK_ERROR, ProbeStatus.TLS_ERROR,
        ProbeStatus.API_INCOMPATIBLE, ProbeStatus.CANCELLED -> "连接失败" to MaterialTheme.colorScheme.error
        else -> "已选择 · 尚未检测" to YuezhouColors.TextSecondary
    }
    val lastCheckedAtText = lastCheckedAt?.let { formatServerTime(it) }
    val detail = buildString {
        if (result?.latencyMs != null) append("响应耗时：${result.latencyMs}ms")
        if (lastCheckedAtText != null) {
            if (isNotEmpty()) append(" · ")
            append("最近检测：$lastCheckedAtText")
        }
    }.ifEmpty { null }
    return CurrentSummary(label, address, detail, color)
}

private fun normalizeUrl(url: String): String =
    runCatching { ServerUrlPolicy.normalize(url) }.getOrNull() ?: url.trim().trimEnd('/').lowercase()

/** 检测时间格式化：从 Composable 中移出，避免在组合期间读取非可观察的 Locale。 */
private fun formatServerTime(epochMillis: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMillis))

private fun suggestionMissing(config: ZLibraryConfig, next: ServerMode, currentAutoUrl: String?): Boolean {
    if (next != ServerMode.MANUAL) return false
    val enabled = config.servers.candidates.filter { it.enabled }
    val normalizedAuto = currentAutoUrl?.trim()?.trimEnd('/')?.lowercase()
    return enabled.none { it.baseUrl.trim().trimEnd('/').lowercase() == normalizedAuto } &&
        enabled.none { it.id == config.servers.manualSelectedServerId }
}

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
