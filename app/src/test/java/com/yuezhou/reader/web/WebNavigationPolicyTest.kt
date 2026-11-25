package com.yuezhou.reader.web

import org.junit.Assert.assertEquals
import org.junit.Test

class WebNavigationPolicyTest {

    private val hosts = setOf("z-lib.io", "z-library.example.org")

    @Test
    fun `配置服务器及其子域的 HTTPS 允许`() {
        assertEquals(
            WebNavigationPolicy.Decision.ALLOWED,
            WebNavigationPolicy.classify("https://z-lib.io/book/1", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.ALLOWED,
            WebNavigationPolicy.classify("https://cdn.z-library.example.org/file.epub", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.ALLOWED,
            WebNavigationPolicy.classify("HTTPS://Z-LIB.IO./book/1", hosts),
        )
    }

    @Test
    fun `仿冒域名与外部域名交给系统浏览器`() {
        assertEquals(
            WebNavigationPolicy.Decision.EXTERNAL,
            WebNavigationPolicy.classify("https://z-lib.io.evil.com/book/1", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.EXTERNAL,
            WebNavigationPolicy.classify("https://example.com/book/1", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.EXTERNAL,
            WebNavigationPolicy.classify("https://example.com/book/1", emptySet()),
        )
    }

    @Test
    fun `已验证的下载 CDN 交给 DownloadListener`() {
        assertEquals(
            WebNavigationPolicy.Decision.DOWNLOAD,
            WebNavigationPolicy.classify("https://dln1.ncdn.ec/dl/abc/file.epub", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.DOWNLOAD,
            WebNavigationPolicy.classify("https://ncdn.ec/file.epub", hosts),
        )
        // 形似域名仍按外部站点处理，避免把伪装域当成下载 CDN。
        assertEquals(
            WebNavigationPolicy.Decision.EXTERNAL,
            WebNavigationPolicy.classify("https://ncdn.ec.evil.com/file.epub", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.EXTERNAL,
            WebNavigationPolicy.classify("https://fake-ncdn.ec/file.epub", hosts),
        )
    }

    @Test
    fun `非 HTTPS 与危险 scheme 一律拒绝`() {
        listOf(
            "http://z-lib.io/book/1",
            "file:///sdcard/book.epub",
            "content://media/external/file/1",
            "javascript:alert(1)",
            "intent://scan/#Intent;scheme=zxing;end",
            "data:text/html,hello",
            "/book/1",
            "",
        ).forEach { url ->
            assertEquals(url, WebNavigationPolicy.Decision.BLOCKED, WebNavigationPolicy.classify(url, hosts))
        }
    }

    @Test
    fun `伪装 userinfo 与非标准端口拒绝`() {
        assertEquals(
            WebNavigationPolicy.Decision.BLOCKED,
            WebNavigationPolicy.classify("https://z-lib.io@evil.com/book/1", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.BLOCKED,
            WebNavigationPolicy.classify("https://z-lib.io:8443/book/1", hosts),
        )
        assertEquals(
            WebNavigationPolicy.Decision.ALLOWED,
            WebNavigationPolicy.classify("https://z-lib.io:443/book/1", hosts),
        )
    }

    @Test
    fun `日志脱敏去掉查询片段与 userinfo`() {
        assertEquals(
            "https://z-lib.io/book/1",
            WebNavigationPolicy.sanitizeForLog("https://user:pass@z-lib.io/book/1?token=secret#part"),
        )
        assertEquals("", WebNavigationPolicy.sanitizeForLog("not a url"))
    }

    @Test
    fun `hostOf 只返回规范化主机`() {
        assertEquals("z-lib.io", WebNavigationPolicy.hostOf("https://Z-LIB.io./book"))
        assertEquals(null, WebNavigationPolicy.hostOf("file:///tmp/a"))
    }
}
