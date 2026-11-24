package com.yuezhou.reader.core.online.api

import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ProbeStatus
import com.yuezhou.reader.core.online.config.ServerUrlPolicy
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 无认证服务器探测（增量方案 §5/§6）。
 *
 * - 独立 OkHttpClient，**不注入 Cookie/Authorization**，与认证客户端完全隔离。
 * - 不自动跟随重定向；3xx 目标须 HTTPS 且在候选主机白名单内，否则判为 API 不兼容。
 * - 探测只打健康端点，必须返回可解析的 JSON 对象（HTTP 200 的非预期内容不算可用）。
 * - 拒绝私有/环回/本地目标，防止被用于探测内网服务。
 */
object ServerProbeClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(
        baseUrl: String,
        config: ZLibraryConfig,
        candidateId: String,
        allowedHosts: Set<String> = config.servers.candidates.mapNotNull {
            runCatching { URL(it.baseUrl).host }.getOrNull()
        }.toSet() + config.servers.baseUrls.mapNotNull { runCatching { URL(it).host }.getOrNull() },
    ): ProbeResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val error = ServerUrlPolicy.validate(baseUrl)
        if (error != null) {
            return@withContext ProbeResult(candidateId, ProbeStatus.NETWORK_ERROR.name, null, started)
        }
        val host = runCatching { URL(baseUrl).host }.getOrNull()
        if (host == null || host.lowercase() !in allowedHosts) {
            return@withContext ProbeResult(candidateId, ProbeStatus.NETWORK_ERROR.name, null, started)
        }
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(config.discovery.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.discovery.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(config.discovery.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + config.endpoints.health.path)
            .get()
            .build()

        val status = runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    // 重定向一律不信任为可用：跨域可能泄漏凭证，且未跟随无法确认最终内容。
                    ProbeStatus.API_INCOMPATIBLE
                } else if (response.code !in 200..299) {
                    when (response.code) {
                        401, 403 -> ProbeStatus.API_INCOMPATIBLE
                        else -> ProbeStatus.NETWORK_ERROR
                    }
                } else {
                    val body = response.body?.string().orEmpty()
                    val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
                    if (isExpectedStructure(parsed)) ProbeStatus.AVAILABLE
                    else ProbeStatus.API_INCOMPATIBLE
                }
            }
        }.getOrElse { throwable ->
            when {
                throwable is java.net.SocketTimeoutException ||
                    throwable is java.io.InterruptedIOException -> ProbeStatus.TIMEOUT
                throwable is javax.net.ssl.SSLException -> ProbeStatus.TLS_ERROR
                else -> ProbeStatus.NETWORK_ERROR
            }
        }
        ProbeResult(candidateId, status.name, System.currentTimeMillis() - started, System.currentTimeMillis())
    }

    /**
     * 预期响应结构：JSON 对象且至少包含一个可用的数组或对象字段。
     * 不假定具体字段名（服务端版本差异），但足以拒绝 HTML 错误页与 bot challenge。
     */
    internal fun isExpectedStructure(element: JsonElement?): Boolean {
        if (element !is JsonObject) return false
        if (element.isEmpty()) return false
        return element.values.any { it is JsonObject || it is kotlinx.serialization.json.JsonArray }
    }
}
