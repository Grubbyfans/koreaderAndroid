package com.yuezhou.reader.core.online.config

import java.net.URL

/**
 * 服务器地址规范化与校验策略（增量方案 §4/§6）。
 *
 * - 仅 HTTPS；拒绝 userinfo、查询、片段、非法端口与非基址路径。
 * - 拒绝本地/环回/链路本地/私有网络目标（仅针对该检索书源，不改变 WebDAV 局域网能力）。
 * - 规范化主机大小写、默认端口、尾部斜杠后判重，不改写路径大小写。
 */
object ServerUrlPolicy {
    /** 规范化后的地址：小写主机、去掉默认端口、去掉尾部斜杠。 */
    fun normalize(raw: String): String {
        val url = URL(raw.trim())
        val scheme = url.protocol.lowercase()
        val host = url.host.lowercase()
        val explicitPort = url.port
        val port = when {
            explicitPort == -1 -> ""
            explicitPort == 443 -> ""
            else -> ":$explicitPort"
        }
        val path = url.path.orEmpty().trimEnd('/')
        return "$scheme://$host$port$path"
    }

    /** 返回错误原因；null 表示通过。 */
    fun validate(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return "地址不能为空"
        val url = runCatching { URL(value) }.getOrNull() ?: return "地址格式无效"
        if (url.protocol != "https") return "必须使用 HTTPS"
        if (url.userInfo != null) return "不允许在地址中携带用户名或密码"
        if (url.query != null || url.ref != null) return "地址不能包含查询参数或片段"
        val path = url.path.orEmpty()
        if (path != "" && path != "/") return "服务器地址只能包含协议与主机"
        if (url.host.isEmpty()) return "主机不能为空"
        if (url.port != -1 && url.port !in 1..65535) return "端口无效"
        val host = url.host.lowercase()
        if (isBlockedHost(host)) return "不允许本地、环回、链路本地或私有网络地址"
        return null
    }

    /** 纯字符串判定，不做 DNS 解析，避免校验阻塞主线程。 */
    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") ||
            host.endsWith(".home.arpa")
        ) return true

        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(host)
        if (ipv4 != null) {
            val p = ipv4.groupValues.drop(1).map { it.toIntOrNull() ?: return true }
            if (p.any { it !in 0..255 }) return true
            val (a, b) = p[0] to p[1]
            return a == 0 || a == 10 || a == 127 ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) ||
                a >= 224
        }

        if (host.contains(":")) {
            val lower = host.lowercase()
            if (lower == "::" || lower == "::1") return true
            if (lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd")) return true
            val v4Tail = Regex("::ffff:(\\d+\\.\\d+\\.\\d+\\.\\d+)$").find(lower)
            if (v4Tail != null) return isBlockedHost(v4Tail.groupValues[1])
        }
        return false
    }
}
