package com.yuezhou.reader.web

import java.net.URI

/**
 * 网页容器导航策略（阶段 C，纯 JVM 逻辑便于测试）。
 *
 * 只放行配置服务器及其子域的 HTTPS 主框架导航；其它 HTTPS 域交给系统浏览器；
 * http/file/content/javascript/intent/data 等一律拒绝，避免网页获取应用文件或本地能力。
 */
object WebNavigationPolicy {

    enum class Decision { ALLOWED, EXTERNAL, BLOCKED }

    private const val MAX_LOG_URL_CHARS = 300

    fun classify(raw: String, allowedHosts: Set<String>): Decision {
        val uri = parse(raw) ?: return Decision.BLOCKED
        if (!uri.scheme.equals("https", ignoreCase = true)) return Decision.BLOCKED
        // 带 userinfo 的地址容易被用于伪装域名，直接拒绝。
        if (!uri.userInfo.isNullOrEmpty()) return Decision.BLOCKED
        if (uri.port != -1 && uri.port != 443) return Decision.BLOCKED
        val host = normalizedHost(uri.host) ?: return Decision.BLOCKED
        val hosts = allowedHosts.mapNotNull(::normalizedHost)
        val sameSite = hosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
        return if (sameSite) Decision.ALLOWED else Decision.EXTERNAL
    }

    /** 仅用于日志/标题的脱敏地址：保留 scheme://host/path，去掉 query、fragment 与 userinfo。 */
    fun sanitizeForLog(raw: String): String {
        val uri = parse(raw) ?: return ""
        val host = normalizedHost(uri.host) ?: return ""
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
        return "https://$host$path".take(MAX_LOG_URL_CHARS)
    }

    fun hostOf(raw: String): String? = parse(raw)?.let { normalizedHost(it.host) }

    private fun parse(raw: String): URI? = runCatching { URI(raw.trim()) }
        .getOrNull()
        ?.takeIf { it.isAbsolute }

    private fun normalizedHost(raw: String?): String? = raw
        ?.lowercase()
        ?.trim()
        ?.trimEnd('.')
        ?.takeIf { it.isNotEmpty() }
}
