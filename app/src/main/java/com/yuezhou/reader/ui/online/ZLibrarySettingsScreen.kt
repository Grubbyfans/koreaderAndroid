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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import com.yuezhou.reader.YuezhouColors
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import com.yuezhou.reader.reader.online.zlibrary.ZLibraryBookProvider
import kotlinx.coroutines.launch

/**
 * Z-Library 书源设置（阶段 C1）：服务器、偏好、连接测试、JSON 导入导出、回滚与恢复默认。
 *
 * 凭证不在此处导入导出，也不在界面明文显示；账号相关入口由阶段 C2 的网络层接入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZLibrarySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { ZLibraryConfigStore(context) }

    var config by remember { mutableStateOf(store.load()) }
    var message by remember { mutableStateOf<String?>(null) }
    var connectionTestResult by remember { mutableStateOf<String?>(null) }
    var showEdit by remember { mutableStateOf(false) }
    var serverText by remember { mutableStateOf(config.servers.baseUrls.joinToString("\n")) }
    var languageText by remember { mutableStateOf(config.preferences.defaultLanguage) }
    var pageSizeText by remember { mutableStateOf(config.preferences.pageSize.toString()) }
    val provider = remember(context, config) { ZLibraryBookProvider(context) }
    var account by remember { mutableStateOf(provider.currentAccount()) }
    var quotaText by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var loginUser by remember { mutableStateOf("") }
    var loginPassword by remember { mutableStateOf("") }

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
                            serverText = imported.servers.baseUrls.joinToString("\n")
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
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = YuezhouColors.Surface,
                border = BorderStroke(1.dp, YuezhouColors.Divider),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("服务器", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (store.hasCustomConfig()) config.servers.baseUrls.joinToString("\n")
                        else "当前使用内置默认占位地址，请配置你有权访问的 HTTPS 服务器",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "配置版本 ${config.configVersion} · ${config.servers.mode} 模式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row {
                        TextButton(onClick = { serverText = config.servers.baseUrls.joinToString("\n"); showEdit = true }) {
                            Text("编辑地址")
                        }
                        TextButton(onClick = {
                            connectionTestResult = "正在测试…"
                            scope.launch {
                                store.testConnection(config).fold(
                                    onSuccess = { connectionTestResult = it },
                                    onFailure = { connectionTestResult = "连接测试失败：${it.message ?: "未知错误"}" },
                                )
                            }
                        }) { Text("连接测试") }
                    }
                    connectionTestResult?.let { result ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.startsWith("连接测试失败")) MaterialTheme.colorScheme.error
                                    else YuezhouColors.TextSecondary,
                        )
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
                                        serverText = it.servers.baseUrls.joinToString("\n")
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
                                        serverText = it.servers.baseUrls.joinToString("\n")
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

    if (showEdit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("服务器地址") },
            text = {
                Column {
                    Text(
                        "每行一个 HTTPS 地址；默认地址为占位，请使用你有权访问的服务器。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverText,
                        onValueChange = { serverText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val urls = serverText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                    val updated = config.copy(servers = config.servers.copy(baseUrls = urls))
                    scope.launch {
                        store.save(updated).fold(
                            onSuccess = {
                                config = it
                                message = "服务器地址已保存"
                                showEdit = false
                            },
                            onFailure = { message = "保存失败：${it.message ?: "未知错误"}" },
                        )
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("取消") } },
        )
    }

    if (showLogin) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogin = false },
            title = { Text("登录 Z-Library") },
            text = {
                Column {
                    OutlinedTextField(
                        value = loginUser,
                        onValueChange = { loginUser = it },
                        label = { Text("账号") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = loginPassword,
                        onValueChange = { loginPassword = it },
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
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        provider.login(loginUser, loginPassword).fold(
                            onSuccess = {
                                account = loginUser.trim()
                                showLogin = false
                                loginPassword = ""
                                message = "登录成功"
                            },
                            onFailure = { message = "登录失败：${it.message ?: "未知错误"}" },
                        )
                    }
                }) { Text("登录") }
            },
            dismissButton = {
                TextButton(onClick = { showLogin = false; loginPassword = "" }) { Text("取消") }
            },
        )
    }
}
