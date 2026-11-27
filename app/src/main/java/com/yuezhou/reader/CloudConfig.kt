package com.yuezhou.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yuezhou.reader.core.cloud.baidu.BaiduAuth
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudCredentials
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 云同步后端配置区域（设置页内）：WebDAV 凭据配置 + 百度 OAuth 授权。
 *
 * 百度授权采用「App 内收集 AppKey/SecretKey/应用目录 → 打开外部浏览器走 OAuth →
 * 回填授权码 → 换 token」流程，替代 Kindle 上的扫码方案（安卓端自带浏览器与键盘，
 * 无需再绕一道扫码）。
 */
@Composable
fun CloudConfigSection(
    store: CloudCredentialStore,
    requestedProvider: String? = null,
    onRequestHandled: () -> Unit = {},
    onConfigured: (String) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var credentials by remember { mutableStateOf<CloudCredentials?>(null) }
    var showWebdav by remember { mutableStateOf(false) }
    var showBaidu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 加载已存凭据
    androidx.compose.runtime.LaunchedEffect(Unit) {
        credentials = store.load()
    }
    androidx.compose.runtime.LaunchedEffect(requestedProvider, credentials != null) {
        if (requestedProvider != null && credentials != null) {
            showWebdav = requestedProvider == "webdav"
            showBaidu = requestedProvider == "baidu"
            onRequestHandled()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        val creds = credentials ?: CloudCredentials()

        // 当前生效后端选择（仅列出已配置的后端）
        val webdavConfigured = creds.webdavRootUrl != null && creds.webdavUsername != null
        val baiduConfigured = creds.baiduAccessToken != null && creds.baiduAppDir != null
        if (webdavConfigured || baiduConfigured) {
            Text(
                "当前同步目标",
                style = MaterialTheme.typography.bodySmall,
                color = YuezhouColors.TextSecondary,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                if (webdavConfigured) {
                    BackendChoiceChip(
                        label = "WebDAV",
                        selected = creds.activeBackend == "webdav" ||
                            (creds.activeBackend == null && !baiduConfigured),
                        onClick = {
                            scope.launch {
                                val updated = creds.copy(activeBackend = "webdav")
                                if (store.save(updated)) {
                                    credentials = updated
                                    message = "同步目标已切换为 WebDAV"
                                }
                            }
                        },
                    )
                }
                if (baiduConfigured) {
                    BackendChoiceChip(
                        label = "百度网盘",
                        selected = creds.activeBackend == "baidu" ||
                            (creds.activeBackend == null && baiduConfigured),
                        onClick = {
                            scope.launch {
                                val updated = creds.copy(activeBackend = "baidu")
                                if (store.save(updated)) {
                                    credentials = updated
                                    message = "同步目标已切换为百度网盘"
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // WebDAV 行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showWebdav = true }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("WebDAV", style = MaterialTheme.typography.bodyLarge)
                Text(
                    creds.webdavRootUrl ?: "未配置",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
            }
            Text(
                if (creds.webdavRootUrl != null) "已配置" else "去配置",
                style = MaterialTheme.typography.bodyMedium,
                color = YuezhouColors.Brand,
            )
        }

        // 百度网盘行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { showBaidu = true }
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("百度网盘", style = MaterialTheme.typography.bodyLarge)
                Text(
                    creds.baiduAccountName ?: if (creds.baiduAccessToken != null) "已授权" else "未授权",
                    style = MaterialTheme.typography.bodySmall,
                    color = YuezhouColors.TextSecondary,
                )
            }
            Text(
                if (creds.baiduAccessToken != null) "已授权" else "去授权",
                style = MaterialTheme.typography.bodyMedium,
                color = YuezhouColors.Brand,
            )
        }

        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = YuezhouColors.TextSecondary)
        }
    }

    // WebDAV 配置对话框
    if (showWebdav) {
        WebDavConfigDialog(
            initial = credentials ?: CloudCredentials(),
            onDismiss = { showWebdav = false },
            onSave = { rootUrl, username, password ->
                scope.launch {
                    val updated = (credentials ?: CloudCredentials()).copy(
                        webdavRootUrl = rootUrl,
                        webdavUsername = username,
                        webdavPassword = password,
                    )
                    if (store.save(updated)) {
                        credentials = updated
                        message = "WebDAV 已保存"
                        onConfigured("webdav")
                    } else {
                        message = "WebDAV 保存失败"
                    }
                    showWebdav = false
                }
            },
        )
    }

    // 百度授权对话框
    if (showBaidu) {
        BaiduAuthDialog(
            onDismiss = { showBaidu = false },
            onAuthorized = { appKey, secretKey, appDir, accountName, accessToken, expiresAt, userId ->
                scope.launch {
                    val updated = (credentials ?: CloudCredentials()).copy(
                        baiduAppKey = appKey,
                        baiduSecretKey = secretKey,
                        baiduAppDir = appDir,
                        baiduAccessToken = accessToken,
                        baiduExpiresAt = expiresAt,
                        baiduUserId = userId,
                        baiduAccountName = accountName,
                    )
                    if (store.save(updated)) {
                        credentials = updated
                        message = "百度网盘已授权：$accountName"
                        onConfigured("baidu")
                    } else {
                        message = "百度授权信息保存失败"
                    }
                    showBaidu = false
                }
            },
            openBrowser = { url ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    message = "无法打开浏览器，请手动复制授权链接"
                }
            },
        )
    }
}

/** 后端选择 chip：显示当前同步目标选中态。 */
@Composable
private fun BackendChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) YuezhouColors.BrandDark else YuezhouColors.TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) YuezhouColors.BrandContainer else YuezhouColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** WebDAV 凭据配置对话框。 */
@Composable
private fun WebDavConfigDialog(
    initial: CloudCredentials,
    onDismiss: () -> Unit,
    onSave: (rootUrl: String, username: String, password: String) -> Unit,
) {
    var rootUrl by remember { mutableStateOf(initial.webdavRootUrl ?: "") }
    var username by remember { mutableStateOf(initial.webdavUsername ?: "") }
    var password by remember { mutableStateOf(initial.webdavPassword ?: "") }
    val valid = rootUrl.startsWith("http://") || rootUrl.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置 WebDAV") },
        text = {
            Column {
                OutlinedTextField(
                    value = rootUrl,
                    onValueChange = { rootUrl = it },
                    label = { Text("服务器地址（https://…）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(rootUrl.trimEnd('/'), username, password) },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 百度 OAuth 授权对话框：收集应用配置 → 打开浏览器 → 回填授权码 → 换 token。 */
@Composable
private fun BaiduAuthDialog(
    onDismiss: () -> Unit,
    onAuthorized: (
        appKey: String,
        secretKey: String,
        appDir: String,
        accountName: String,
        accessToken: String,
        expiresAt: Long,
        userId: String,
    ) -> Unit,
    openBrowser: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 阶段：config=收集应用配置；code=回填授权码
    var stage by remember { mutableStateOf("config") }
    var appKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var appDir by remember { mutableStateOf("") }
    var authCode by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(UUID.randomUUID().toString().replace("-", "")) }
    var error by remember { mutableStateOf<String?>(null) }
    var exchanging by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (stage == "config") "配置百度网盘" else "输入授权码") },
        text = {
            if (stage == "config") {
                Column {
                    Text(
                        "需在百度开放平台创建一个个人应用，填入 AppKey、SecretKey 与应用目录（/apps/xxx）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appKey,
                        onValueChange = { appKey = it },
                        label = { Text("AppKey") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secretKey,
                        onValueChange = { secretKey = it },
                        label = { Text("SecretKey") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appDir,
                        onValueChange = { appDir = it },
                        label = { Text("应用目录（/apps/你的目录）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column {
                    Text(
                        "已在浏览器打开授权页。授权完成后，把页面上显示的授权码粘贴到下方。",
                        style = MaterialTheme.typography.bodySmall,
                        color = YuezhouColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = authCode,
                        onValueChange = { authCode = it },
                        label = { Text("授权码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (stage == "config") {
                TextButton(
                    enabled = appKey.isNotBlank() && secretKey.isNotBlank() && appDir.startsWith("/apps/"),
                    onClick = {
                        // 打开外部浏览器走 OAuth 授权
                        val url = try {
                            BaiduAuth.authorizationUrl(appKey.trim(), state)
                        } catch (e: Exception) {
                            error = e.message
                            return@TextButton
                        }
                        openBrowser(url)
                        stage = "code"
                    },
                ) { Text("去授权") }
            } else {
                TextButton(
                    enabled = authCode.isNotBlank() && !exchanging,
                    onClick = {
                        scope.launch {
                            exchanging = true
                            error = null
                            try {
                                val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val token = BaiduAuth.exchangeCode(
                                        appKey.trim(),
                                        secretKey.trim(),
                                        authCode.trim(),
                                    )
                                    // 换 token 后再取账户信息（uk + 账户名），用于核对身份与展示
                                    val account = BaiduAuth.fetchAccountInfo(token.accessToken)
                                    token to account
                                }
                                onAuthorized(
                                    appKey.trim(),
                                    secretKey.trim(),
                                    appDir.trim(),
                                    result.second.accountName,
                                    result.first.accessToken,
                                    (System.currentTimeMillis() / 1000) + result.first.expiresIn,
                                    result.second.userId,
                                )
                            } catch (e: Exception) {
                                error = e.message ?: "授权失败，请重试"
                            } finally {
                                exchanging = false
                            }
                        }
                    },
                ) { Text(if (exchanging) "换取中…" else "完成授权") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
