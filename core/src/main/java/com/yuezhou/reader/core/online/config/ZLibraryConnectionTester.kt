package com.yuezhou.reader.core.online.config

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 配置连接测试：只访问用户配置的可信 HTTPS 主机，不携带任何凭证。
 *
 * 重定向不自动跟随；若服务端返回 3xx，Location 主机必须在已配置主机列表内且仍是 HTTPS，
 * 防止凭证被转发到未知主机（方案 §6 / 风险 R8）。
 */
object ZLibraryConnectionTester {
    private val json = Json { ignoreUnknownKeys = true }

    fun test(config: ZLibraryConfig): Result<String> {
        val errors = ZLibraryConfigValidator.validate(config)
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString("；")))

        val allowedHosts = config.servers.baseUrls.mapNotNull { runCatching { java.net.URL(it).host }.getOrNull() }.toSet()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(config.network.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.network.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        return runCatching {
            val request = Request.Builder().url(config.servers.baseUrls.first()).head().build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                if (code in 300..399) {
                    val location = response.header("Location")
                    val redirect = location?.let { runCatching { java.net.URL(it) }.getOrNull() }
                        ?: return@use "服务器返回重定向但未提供有效 Location（HTTP $code）"
                    if (redirect.protocol != "https" || redirect.host !in allowedHosts) {
                        return@use "重定向目标不在可信主机列表内，已阻止（HTTP $code）"
                    }
                    return@use "连接成功（HTTP $code，重定向主机可信）"
                }
                if (code !in 200..299) throw IllegalStateException("服务器返回 HTTP $code")
                "连接成功（HTTP $code）"
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )
    }

    /** 供 UI 展示当前配置的 JSON（不含凭证）。 */
    fun exportJson(config: ZLibraryConfig): String = json.encodeToString(config)
}
