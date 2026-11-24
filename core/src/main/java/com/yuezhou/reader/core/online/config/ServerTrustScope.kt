package com.yuezhou.reader.core.online.config

import java.net.URL

/**
 * 凭证信任范围（增量方案 §6）：会话只在其登录时绑定的主机上可用。
 * 切换服务器后旧 Cookie 不得发送到新主机，需重新登录确认信任关系。
 */
object ServerTrustScope {
    /** 从服务器地址提取小写主机；无效地址返回空串。 */
    fun hostOf(baseUrl: String): String = runCatching {
        URL(baseUrl.trim().trimEnd('/')).host.lowercase()
    }.getOrDefault("")

    /**
     * 会话是否可用于当前主机。
     * 绑定主机为空（旧版凭证）时保守视为可用；否则必须完全一致。
     */
    fun isTrusted(boundHost: String, currentBaseUrl: String): Boolean {
        if (boundHost.isBlank()) return true
        val current = hostOf(currentBaseUrl)
        return current.isNotBlank() && current == boundHost.lowercase()
    }
}
