package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.config.ServerTrustScope
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerTrustScopeTest {
    @Test
    fun `同主机复用会话`() {
        assertTrue(ServerTrustScope.isTrusted("a.example.com", "https://a.example.com"))
        assertTrue(ServerTrustScope.isTrusted("A.EXAMPLE.COM", "https://a.example.com"))
    }

    @Test
    fun `切换主机后旧会话失效`() {
        assertFalse(ServerTrustScope.isTrusted("a.example.com", "https://b.example.com"))
    }

    @Test
    fun `旧版未绑定凭证按兼容处理`() {
        assertTrue(ServerTrustScope.isTrusted("", "https://a.example.com"))
    }

    @Test
    fun `空当前地址不可信`() {
        assertFalse(ServerTrustScope.isTrusted("a.example.com", ""))
    }

    @Test
    fun `主机提取规范化`() {
        org.junit.jupiter.api.Assertions.assertEquals("a.example.com", ServerTrustScope.hostOf("https://A.EXAMPLE.COM/eapi"))
        org.junit.jupiter.api.Assertions.assertEquals("", ServerTrustScope.hostOf("not a url"))
    }
}
