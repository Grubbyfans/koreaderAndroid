package com.yuezhou.reader.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.networktts.NetworkTtsAudition
import com.yuezhou.reader.networktts.NetworkTtsRuleChecker
import com.yuezhou.reader.networktts.RuleCheckResult
import com.yuezhou.reader.reader.networktts.NetworkTtsRuleRepository
import com.yuezhou.reader.ui.components.LibraryPageHeading
import io.legado.app.data.entities.BaseSource.LoginRow
import io.legado.app.data.entities.HttpTTS
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络 TTS 规则管理（设置 → 语音朗读 → 网络 TTS 规则管理）。
 *
 * 阶段 A 能力：导入（文件/剪贴板/链接/文本）、新建、编辑、删除、导出、单规则试听。
 * 规则字段与 Legado 原版一致；导入保留原始 JSON，导出过滤常见凭证并由用户确认。
 */
@Composable
internal fun NetworkTtsRulesScreen(onBack: () -> Unit, externalImportUrl: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { NetworkTtsRuleRepository(context) }
    val audition = remember(context) { NetworkTtsAudition(context) }
    val checker = remember(context) { NetworkTtsRuleChecker(context) }
    var checkResults by remember { mutableStateOf(checker.load()) }
    var checkJob by remember { mutableStateOf<Job?>(null) }
    var checkingId by remember { mutableStateOf<Long?>(null) }
    var checkCompleted by remember { mutableStateOf(0) }
    var checkTotal by remember { mutableStateOf(0) }
    DisposableEffect(audition) { onDispose { audition.release() } }

    var rules by remember { mutableStateOf<List<HttpTTS>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var importOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HttpTTS?>(null) }
    var auditioning by remember { mutableStateOf<HttpTTS?>(null) }
    var loginTarget by remember { mutableStateOf<HttpTTS?>(null) }
    var deleteTarget by remember { mutableStateOf<HttpTTS?>(null) }
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var batchDeleteOpen by remember { mutableStateOf(false) }
    val organizer = remember(context) { RuleOrganizer(context) }
    var organization by remember { mutableStateOf(organizer.load()) }
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(organization.sort) }
    var groupPicker by remember { mutableStateOf(false) }
    var moveIds by remember { mutableStateOf<Set<Long>?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var importGroup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var choosingImportGroup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(externalImportUrl != null) }
    var pendingExternalUrl by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(externalImportUrl) }
    var importFromFile by remember { mutableStateOf(false) }
    fun updateOrganization(value: RuleOrganization) {
        organizer.save(value)
        organization = value
    }
    val visibleRules = rules.filter {
        it.name.contains(query.trim(), ignoreCase = true) &&
            (!favoritesOnly || it.id in organization.favorites) &&
            (groupFilter == null || organization.memberships[it.id].orEmpty() == groupFilter)
    }.let { list ->
        if (sort == "updated") list.sortedByDescending { it.lastUpdateTime }
        else list.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
    }
    fun toggleFavorites(ids: Set<Long>) {
        val remove = ids.all { it in organization.favorites }
        updateOrganization(organization.copy(favorites =
            if (remove) organization.favorites - ids else organization.favorites + ids))
    }

    fun reload() {
        scope.launch {
            val loaded = runCatching { repository.list() }.getOrDefault(emptyList())
            rules = loaded
            if (selectedIds.isNotEmpty()) {
                selectedIds = selectedIds.intersect(loaded.map { it.id }.toSet())
                if (selectedIds.isEmpty()) selectionMode = false
            }
            loading = false
        }
    }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    LaunchedEffect(Unit) { reload() }

    BackHandler(enabled = selectionMode) { exitSelection() }

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
                message = importText(repository, text) { ids ->
                    if (importGroup.isNotEmpty()) updateOrganization(organization.copy(
                        memberships = organization.memberships + ids.associateWith { importGroup }))
                }
                reload()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (selectionMode) exitSelection() else onBack() }) {
                Text(if (selectionMode) "取消" else "返回")
            }
            Text(
                if (selectionMode) "已选 ${selectedIds.size} 项" else "网络 TTS 规则",
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
                "支持 Legado 网络朗读规则、登录参数与试听；具体规则兼容性以实际验收为准。",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
            if (selectionMode) {
                val allSelected = visibleRules.isNotEmpty() && visibleRules.all { it.id in selectedIds }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        selectedIds = if (allSelected) selectedIds - visibleRules.map { it.id }.toSet()
                        else selectedIds + visibleRules.map { it.id }.toSet()
                    }) { Text(if (allSelected) "取消全选" else "全选结果") }
                    TextButton(enabled = selectedIds.isNotEmpty(), onClick = {
                        val orderedIds = rules.filter { it.id in selectedIds }.map { it.id }
                        scope.launch {
                            val text = runCatching { repository.exportMany(orderedIds) }.getOrNull()
                            if (text.isNullOrBlank()) {
                                message = "导出失败"
                            } else {
                                pendingExport = "已选 ${orderedIds.size} 条规则" to text
                            }
                        }
                    }) { Text("导出选中") }
                    TextButton(enabled = selectedIds.isNotEmpty(), onClick = { batchDeleteOpen = true }) {
                        Text("删除选中")
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { importFromFile = false; choosingImportGroup = true }) { Text("导入") }
                    TextButton(onClick = { importFromFile = true; choosingImportGroup = true }) {
                        Text("从文件导入")
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sortMenu = true }) { Text("排序 ▾", maxLines = 1) }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf("name" to "名称", "updated" to "最近更新").forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(if (sort == key) "✓ $label" else label) }, onClick = {
                                    sort = key
                                    updateOrganization(organization.copy(sort = key))
                                    sortMenu = false
                                })
                            }
                        }
                    }
                }
            }
            if (selectionMode) {
                Row {
                    TextButton(enabled = selectedIds.isNotEmpty(), onClick = { moveIds = selectedIds.toSet() }) { Text("移动到分组") }
                    TextButton(enabled = selectedIds.isNotEmpty(), onClick = { toggleFavorites(selectedIds) }) {
                        Text(if (selectedIds.isNotEmpty() && selectedIds.all { it in organization.favorites }) "取消收藏" else "收藏")
                    }
                    TextButton(enabled = selectedIds.isNotEmpty() && checkJob == null, onClick = {
                        val targets = rules.filter { it.id in selectedIds }.map { it.copy() }
                        checkCompleted = 0
                        checkTotal = targets.size
                        checkJob = scope.launch {
                            var succeeded = 0
                            try {
                                targets.forEach { rule ->
                                    checkingId = rule.id
                                    val result = checker.check(rule)
                                    checker.save(rule.id, result)
                                    checkResults = checkResults + (rule.id to result)
                                    checkCompleted++
                                    if (result.success) succeeded++
                                }
                            } finally {
                                checkingId = null
                                checkJob = null
                                val status = if (currentCoroutineContext().isActive) "检测完成" else "检测已取消"
                                message = "$status $checkCompleted / $checkTotal 条：可用 $succeeded 条，失败 ${checkCompleted - succeeded} 条"
                            }
                        }
                    }) { Text("检测") }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索规则名称") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !favoritesOnly, onClick = { favoritesOnly = false }, label = { Text("全部") })
                FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = true }, label = { Text("收藏") })
                TextButton(onClick = { groupPicker = true }, modifier = Modifier.weight(1f)) {
                    Text("分组：${groupFilter ?: "全部"}${if (groupFilter == "") "未分组" else ""} ▾", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("共 ${visibleRules.size} / ${rules.size} 条", style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary)
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            }
        }

        if (visibleRules.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (rules.isEmpty()) "还没有规则，点击「导入」添加" else "没有符合条件的规则", color = YuezhouColors.TextSecondary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visibleRules, key = { it.id }) { rule ->
                    RuleRow(
                        rule = rule,
                        group = organization.memberships[rule.id] ?: "未分组",
                        checkResult = checkResults[rule.id]?.takeIf { it.matches(rule) },
                        checking = checkingId == rule.id,
                        favorite = rule.id in organization.favorites,
                        onFavorite = { toggleFavorites(setOf(rule.id)) },
                        onMove = { moveIds = setOf(rule.id) },
                        selectionMode = selectionMode,
                        selected = rule.id in selectedIds,
                        onClick = {
                            if (selectionMode) {
                                selectedIds = if (rule.id in selectedIds) {
                                    selectedIds - rule.id
                                } else {
                                    selectedIds + rule.id
                                }
                            }
                        },
                        onLongClick = {
                            selectionMode = true
                            selectedIds = selectedIds + rule.id
                        },
                        onAudition = { auditioning = rule },
                        onEdit = { editing = rule },
                        onLogin = if (rule.hasLogin()) ({ loginTarget = rule }) else null,
                        onDelete = { deleteTarget = rule },
                        onExport = {
                            scope.launch {
                                val text = runCatching { repository.exportOne(rule.id) }.getOrNull()
                                if (text.isNullOrBlank()) {
                                    message = "导出失败"
                                } else {
                                    pendingExport = "「${rule.name}」" to text
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (checkJob != null) {
        AlertDialog(
            onDismissRequest = { checkJob?.cancel() },
            title = { Text("检测试听可用性") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已完成 $checkCompleted / $checkTotal 条")
                    rules.firstOrNull { it.id == checkingId }?.let {
                        Text(it.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    NetworkTtsLoading("正在请求音频并静音验证解码…")
                }
            },
            confirmButton = { TextButton(onClick = { checkJob?.cancel() }) { Text("取消检测") } },
        )
    }

    if (choosingImportGroup) {
        RuleGroupSheet(organization, rules, importGroup, true,
            onChange = { updateOrganization(it) },
            onSelect = {
                importGroup = it.orEmpty()
                choosingImportGroup = false
                if (importFromFile) fileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) else importOpen = true
            }, onDismiss = { choosingImportGroup = false; pendingExternalUrl = null }, importing = true)
    }
    if (groupPicker || moveIds != null) {
        RuleGroupSheet(
            organization = organization,
            rules = rules,
            selectedGroup = if (moveIds == null) groupFilter else null,
            moving = moveIds != null,
            onChange = { updated ->
                if (groupFilter != null && groupFilter != "" && groupFilter !in updated.groups) groupFilter = null
                updateOrganization(updated)
            },
            onSelect = { group ->
                val ids = moveIds
                if (ids == null) {
                    groupFilter = group
                } else {
                    updateOrganization(organization.copy(memberships = organization.memberships.toMutableMap().apply {
                        ids.forEach { id -> if (group.isNullOrEmpty()) remove(id) else put(id, group) }
                    }))
                }
                groupPicker = false
                moveIds = null
            },
            onDismiss = { groupPicker = false; moveIds = null },
        )
    }

    if (importOpen) {
        ImportDialog(
            initialUrl = pendingExternalUrl.orEmpty(),
            onDismiss = { importOpen = false; pendingExternalUrl = null },
            onImportText = { text ->
                importOpen = false
                pendingExternalUrl = null
                scope.launch {
                    message = importText(repository, text) { ids ->
                        if (importGroup.isNotEmpty()) updateOrganization(organization.copy(
                            memberships = organization.memberships + ids.associateWith { importGroup }))
                    }
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

    loginTarget?.let { rule ->
        LoginDialog(
            rule = rule,
            onDismiss = { loginTarget = null },
            onMessage = { message = it },
        )
    }

    pendingExport?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { pendingExport = null },
            title = { Text("确认导出") },
            text = {
                Text("已递归过滤常见凭证字段，并遮盖静态请求头与链接中的敏感参数。脚本中的硬编码敏感值不一定能被识别，导出后仍需自行检查。")
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, text)
                    pendingExport = null
                    message = "已复制$title（常见凭证已脱敏，请检查脚本与链接）"
                }) { Text("确认并复制") }
            },
            dismissButton = { TextButton(onClick = { pendingExport = null }) { Text("取消") } },
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
                            .onSuccess {
                                updateOrganization(organization.copy(
                                    memberships = organization.memberships - rule.id,
                                    favorites = organization.favorites - rule.id))
                                message = "已删除「${rule.name}」"
                            }
                            .onFailure { message = "删除失败：${it.message}" }
                        reload()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (batchDeleteOpen) {
        AlertDialog(
            onDismissRequest = { batchDeleteOpen = false },
            title = { Text("删除规则") },
            text = { Text("确定删除选中的 ${selectedIds.size} 条规则？该操作不影响已试听缓存。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    batchDeleteOpen = false
                    scope.launch {
                        runCatching { repository.deleteMany(ids) }
                            .onSuccess {
                                updateOrganization(organization.copy(
                                    memberships = organization.memberships - ids.toSet(),
                                    favorites = organization.favorites - ids.toSet()))
                                message = "已删除 ${ids.size} 条规则"
                                exitSelection()
                            }
                            .onFailure { message = "删除失败：${it.message}" }
                        reload()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { batchDeleteOpen = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuleRow(
    rule: HttpTTS,
    group: String,
    checkResult: RuleCheckResult?,
    checking: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onMove: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAudition: () -> Unit,
    onEdit: () -> Unit,
    onLogin: (() -> Unit)?,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    YuezhouColors.Surface
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = if (selectionMode) Role.Checkbox else null,
            )
            .then(if (selectionMode) Modifier.semantics {
                toggleableState = ToggleableState(selected)
            } else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                Modifier.size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(group, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
            Text(
                when {
                    checking -> "检测中…"
                    checkResult == null -> "未检测"
                    checkResult.success -> "检测可用"
                    else -> "检测失败：${checkResult.detail}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    checking || checkResult == null -> YuezhouColors.TextSecondary
                    checkResult.success -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!selectionMode) {
                var menu by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onFavorite) { Text(if (favorite) "★" else "☆") }
                    TextButton(onClick = onAudition) { Text("试听") }
                    Box {
                        TextButton(onClick = { menu = true }) { Text("更多") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("移动到分组") }, onClick = { menu = false; onMove() })
                            DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                            onLogin?.let { action -> DropdownMenuItem(text = { Text("登录") }, onClick = { menu = false; action() }) }
                            DropdownMenuItem(text = { Text("导出") }, onClick = { menu = false; onExport() })
                            DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDialog(initialUrl: String = "", onDismiss: () -> Unit, onImportText: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var url by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialUrl) }
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
                if (busy) NetworkTtsLoading("正在下载规则…")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = { onImportText(text) }) { Text("导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun LoginDialog(
    rule: HttpTTS,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rows by remember(rule.id) { mutableStateOf<List<LoginRow>>(emptyList()) }
    var values by remember(rule.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember(rule.id) { mutableStateOf(true) }
    var busy by remember(rule.id) { mutableStateOf(false) }
    var error by remember(rule.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(rule.id) {
        try {
            val loaded = withContext(Dispatchers.IO) {
                withTimeout(10_000) {
                    val parsedRows = rule.getLoginRows(currentCoroutineContext())
                    parsedRows to rule.getLoginInfoMap(parsedRows)
                }
            }
            rows = loaded.first
            values = loaded.second
            loading = false
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            error = "登录表单脚本执行超时"
            loading = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            error = NetworkTtsLog.sanitize(e.message ?: "登录表单加载失败")
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("登录 / 参数 · ${rule.name}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "表单值与 Cookie 会加密保存在本机。登录脚本由规则提供，仅在点击登录时执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
                if (loading) {
                    CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp)
                } else if (rows.isEmpty()) {
                    Text(
                        "该规则没有登录表单，点击登录将直接执行 loginUrl 脚本。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                rows.forEach { row ->
                    LoginField(
                        row = row,
                        value = values[row.name].orEmpty(),
                        enabled = !busy,
                        onChange = { values = values + (row.name to it) },
                    )
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(16.dp).padding(end = 6.dp), strokeWidth = 2.dp)
                        Text("正在执行登录脚本…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            withTimeout(120_000) {
                                if (values.isNotEmpty() && !rule.putLoginInfo(org.json.JSONObject(values).toString())) {
                                    throw IllegalStateException("登录信息保存失败")
                                }
                                rule.loginWithContext(currentCoroutineContext())
                            }
                        }
                        busy = false
                        onMessage("「${rule.name}」登录参数已保存，脚本已执行；请通过试听确认鉴权结果")
                        onDismiss()
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        busy = false
                        error = "登录脚本执行超时"
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        busy = false
                        error = NetworkTtsLog.sanitize(e.message ?: "登录失败")
                    }
                }
            }) { Text("登录") }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = {
                    runCatching {
                        rule.removeLoginHeader()
                        rule.removeLoginInfo()
                    }.onSuccess {
                        values = rows.associate { it.name to it.default.orEmpty() }
                        error = null
                        onMessage("已清除「${rule.name}」的登录信息与登录头")
                    }.onFailure {
                        error = NetworkTtsLog.sanitize(it.message ?: "清除登录态失败")
                    }
                }) { Text("清除登录态") }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun LoginField(
    row: LoginRow,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val label = row.hint?.takeIf { it.isNotBlank() } ?: row.name
    val choices = row.choices()
    if (row.type.equals("select", ignoreCase = true) || choices.isNotEmpty()) {
        var expanded by remember(row.name) { mutableStateOf(false) }
        Box(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = {},
                label = { Text(label) },
                readOnly = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { expanded = true },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice) },
                        onClick = {
                            onChange(choice)
                            expanded = false
                        },
                    )
                }
            }
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (row.type.equals("password", ignoreCase = true)) {
                PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    var speed by remember { mutableStateOf(NetworkTtsSynthesizer.DEFAULT_SPEECH_RATE.toFloat()) }
    val playing = state.phase == NetworkTtsAudition.Phase.PLAYING
    val busy = state.phase == NetworkTtsAudition.Phase.SYNTHESIZING

    LaunchedEffect(rule.id) {
        audition.audition(rule, text, speed.toInt())
    }
    DisposableEffect(audition, rule.id) {
        onDispose { audition.stop() }
    }

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
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("试听文本") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 显示最终 speakSpeed，与实际下发给服务器的请求参数一致（范围 5–20，默认 10）。
                Text(
                    "语速参数 speakSpeed = ${speed.toInt()}",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(value = speed, onValueChange = { speed = it }, valueRange = NetworkTtsSynthesizer.MIN_SPEECH_RATE.toFloat()..NetworkTtsSynthesizer.MAX_SPEECH_RATE.toFloat(), steps = NetworkTtsSynthesizer.MAX_SPEECH_RATE - NetworkTtsSynthesizer.MIN_SPEECH_RATE - 1)
                state.message?.takeUnless { busy }?.let {
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
                    NetworkTtsLoading("正在合成试听音频…")
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

@Composable
private fun NetworkTtsLoading(message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)))
    }
}

private suspend fun importText(repository: NetworkTtsRuleRepository, text: String, onImported: (List<Long>) -> Unit): String {
    val outcome = runCatching { repository.import(text) }.getOrElse {
        return "导入失败：${it.message}"
    }
    onImported(outcome.ruleIds)
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
        val source = URL(url)
        require(source.protocol in setOf("http", "https") && source.host.isNotBlank())
        val connection = (source.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        try {
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
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
