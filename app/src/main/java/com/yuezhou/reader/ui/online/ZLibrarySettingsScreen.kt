package com.yuezhou.reader.ui.online

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.core.online.config.DiscoveryState
import com.yuezhou.reader.core.online.config.ServerMode
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import com.yuezhou.reader.reader.online.zlibrary.ZLibraryBookProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Z-Library 书源设置（阶段 C1）：服务器、偏好、连接测试、JSON 导入导出、回滚与恢复默认。
 *
 * 凭证不在此处导入导出，也不在界面明文显示；账号相关入口由阶段 C2 的网络层接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZLibrarySettingsScreen(onBack: () -> Unit, onOpenServerSettings: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ZLibraryConfigStore(context) }

    var config by remember { mutableStateOf(store.load()) }
    var message by remember { mutableStateOf<String?>(null) }
    var serverState by remember { mutableStateOf(store.loadState()) }
    var languageText by remember { mutableStateOf(config.preferences.defaultLanguage) }
    var pageSizeText by remember { mutableStateOf(config.preferences.pageSize.toString()) }
    val provider = remember(context, config) { ZLibraryBookProvider(context) }
    var account by remember { mutableStateOf(provider.currentAccount()) }
    var quotaText by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var loginUser by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }
    var loginInProgress by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }

    fun dismissLogin() {
        showLogin = false
        loginPassword = ""
        loginError = null
    }

    fun submitLogin() {
        if (loginInProgress) return
        val username = loginUser.trim()
        val password = loginPassword
        if (username.isEmpty() || password.isBlank()) {
            loginError = "请输入账号和密码"
            return
        }
        keyboard?.hide()
        loginError = null
        loginInProgress = true
        scope.launch {
            try {
                provider.login(username, password).getOrThrow()
                account = username
                dismissLogin()
                message = "登录成功"
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                loginError = "登录失败：无法连接服务器，请检查网络和服务器设置后重试"
            } catch (e: Exception) {
                loginError = "登录失败：${e.message?.takeIf { it.isNotBlank() } ?: "请稍后重试"}"
            } finally {
                loginInProgress = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            if (text == null) {
                message = "读取配置失败"
                return@launch
            }
            store.importText(text).fold(
                onSuccess = { imported ->
                    store.save(imported).fold(
                        onSuccess = {
                            config = imported
                            languageText = imported.preferences.defaultLanguage
                            pageSizeText = imported.preferences.pageSize.toString()
                            message = "配置已导入并应用"
                        },
                        onFailure = { message = "保存失败：${it.message ?: "未知错误"}" },
                    )
                },
                onFailure = { message = "配置无效，未覆盖当前配置：${it.message ?: "未知错误"}" },
            )
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(store.exportJson(config).toByteArray(Charsets.UTF_8))
                } != null
            } catch (e: Exception) {
                false
            }
            message = if (ok) "配置已导出（不含凭证）" else "导出失败"
        }
    }

    fun applyPreferences() {
        val pageSize = pageSizeText.toIntOrNull()
        if (pageSize == null) {
            message = "分页数量必须是数字"
            return
        }
        val updated = config.copy(
            preferences = config.preferences.copy(
                defaultLanguage = languageText.trim(),
                pageSize = pageSize,
            ),
        )
        scope.launch {
            store.save(updated).fold(
                onSuccess = { config = it; message = "偏好已保存" },
                onFailure = { message = "保存失败：${it.message ?: "未知错误"}" },
            )
        }
    }

    Scaffold(
        containerColor = YuezhouColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("Z-Library 设置") },
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
            // 服务器区域（方案 §2.1）：统一为单一「服务器管理」入口，只展示模式、当前地址、最近检测状态。
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenServerSettings() },
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("服务器管理", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            serverModeLabel(config) + " · " + serverConnectionLabel(config, serverState),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            currentServerAddress(config, serverState) ?: "尚未配置服务器地址",
                            style = MaterialTheme.typography.bodySmall,
                            color = YuezhouColors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "进入服务器管理",
                        tint = YuezhouColors.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("默认偏好", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = languageText,
                        onValueChange = { languageText = it },
                        label = { Text("默认语言（如 zh 或 zh-CN）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = pageSizeText,
                        onValueChange = { pageSizeText = it },
                        label = { Text("分页数量（1–30）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        "格式固定为 EPUB；搜索免登录，下载需登录并计额度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { applyPreferences() }) { Text("保存偏好") }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("配置管理", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "配置文件不包含账号、Token 或 Cookie；凭证独立加密保存，不随配置导出。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                            Text("导入 JSON")
                        }
                        TextButton(onClick = { exportLauncher.launch("zlibrary-config.json") }) {
                            Text("导出 JSON")
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            scope.launch {
                                store.rollback().fold(
                                    onSuccess = {
                                        config = it
                                        serverState = store.loadState()
                                        message = "已回滚到上一份配置"
                                    },
                                    onFailure = { message = "回滚失败：${it.message ?: "未知错误"}" },
                                )
                            }
                        }) { Text("回滚上一版本") }
                        TextButton(onClick = {
                            scope.launch {
                                store.reset().fold(
                                    onSuccess = {
                                        config = it
                                        serverState = store.loadState()
                                        message = "已恢复默认配置（可回滚）"
                                    },
                                    onFailure = { message = "恢复默认失败：${it.message ?: "未知错误"}" },
                                )
                            }
                        }) { Text("恢复默认") }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    val current = account
                    if (current == null) {
                        Text(
                            "未登录 · 搜索无需登录，下载需登录并计额度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { showLogin = true }) { Text("登录") }
                    } else {
                        Text("已登录：$current", style = MaterialTheme.typography.bodyMedium)
                        Row {
                            TextButton(onClick = {
                                scope.launch {
                                    provider.quota().fold(
                                        onSuccess = { quota ->
                                            quotaText = "今日下载 ${quota.downloadsToday ?: "未知"} / ${quota.downloadsLimit ?: "未知"}"
                                        },
                                        onFailure = { message = "额度查询失败：${it.message ?: "未知错误"}" },
                                    )
                                }
                            }) { Text("查询额度") }
                            TextButton(onClick = {
                                scope.launch {
                                    provider.logout()
                                    account = null
                                    quotaText = null
                                    message = "已退出登录"
                                }
                            }) { Text("退出登录") }
                        }
                        quotaText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showLogin && config.servers.baseUrls.isEmpty()) {
        AlertDialog(
            onDismissRequest = { dismissLogin() },
            title = { Text("请先设置服务器") },
            text = { Text("请在服务器管理中添加并启用服务器。手动模式下还需选中要使用的服务器。") },
            confirmButton = {
                TextButton(onClick = {
                    dismissLogin()
                    onOpenServerSettings()
                }) { Text("前往服务器管理") }
            },
            dismissButton = {
                TextButton(onClick = { dismissLogin() }) { Text("取消") }
            },
        )
    } else if (showLogin) {
        AlertDialog(
            onDismissRequest = { if (!loginInProgress) dismissLogin() },
            title = { Text("登录 Z-Library") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = loginUser,
                        onValueChange = { loginUser = it; loginError = null },
                        enabled = !loginInProgress,
                        label = { Text("账号") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it; loginError = null },
                        enabled = !loginInProgress,
                        label = { Text("密码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "凭证加密保存，不写入配置文件；请勿在不可信服务器上使用常用密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (loginInProgress) {
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在登录…")
                        }
                    }
                    loginError?.let { error ->
                        Text(
                            text = error,
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !loginInProgress, onClick = { submitLogin() }) { Text("登录") }
            },
            dismissButton = {
                TextButton(enabled = !loginInProgress, onClick = { dismissLogin() }) { Text("取消") }
            },
        )
    }
}

/** 服务器管理入口摘要：模式名（服务器设置优化 §4.2/§4.3 重命名）。 */
private fun serverModeLabel(config: ZLibraryConfig): String =
    if (ServerMode.fromWire(config.servers.mode) == ServerMode.MANUAL) "手动选择" else "自动选择"

/** 最近一次连接检测状态文案（来自运行状态，不使用模拟值）。 */
private fun serverConnectionLabel(config: ZLibraryConfig, state: DiscoveryState): String {
    if (config.servers.candidates.isEmpty()) return "未配置服务器"
    val checkedAt = state.lastCheckedAt ?: return "尚未检测"
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(checkedAt))
    return if (state.lastAvailableBaseUrl != null) "连接正常 · $time" else "连接失败 · $time"
}

/** 当前生效服务器地址（两页共用同一状态来源）：手动取选中项，自动优先取最近探测可用项。 */
private fun currentServerAddress(config: ZLibraryConfig, state: DiscoveryState): String? {
    val candidates = config.servers.candidates
    if (ServerMode.fromWire(config.servers.mode) == ServerMode.MANUAL) {
        return candidates.firstOrNull { it.id == config.servers.manualSelectedServerId }?.baseUrl
    }
    val recent = state.lastAvailableBaseUrl
    return candidates.firstOrNull { it.baseUrl.equals(recent, ignoreCase = true) }?.baseUrl
        ?: config.servers.baseUrls.firstOrNull()
}
