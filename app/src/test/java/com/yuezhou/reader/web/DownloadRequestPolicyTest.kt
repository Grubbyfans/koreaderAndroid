package com.yuezhou.reader.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRequestPolicyTest {

    @Test
    fun `只接受无凭据的 443 HTTPS 地址`() {
        assertEquals(
            "https://cdn.example.com/book.epub",
            DownloadRequestPolicy.supportedUrl("https://cdn.example.com/book.epub"),
        )
        assertNull(DownloadRequestPolicy.supportedUrl("http://cdn.example.com/book.epub"))
        assertNull(DownloadRequestPolicy.supportedUrl("blob:https://example.com/abc"))
        assertNull(DownloadRequestPolicy.supportedUrl("data:application/epub+zip;base64,AAAA"))
        assertNull(DownloadRequestPolicy.supportedUrl("https://user:pass@example.com/book.epub"))
        assertNull(DownloadRequestPolicy.supportedUrl("https://example.com:8443/book.epub"))
    }

    @Test
    fun `优先使用响应头中的可辨认文件名`() {
        assertEquals(
            "三体.epub",
            DownloadRequestPolicy.candidateFileName(
                "attachment; filename=\"三体.epub\"",
                "https://cdn.example.com/download/1",
            ),
        )
        assertEquals(
            "三体.epub",
            DownloadRequestPolicy.candidateFileName(
                "attachment; filename*=UTF-8''%E4%B8%89%E4%BD%93.epub",
                "https://cdn.example.com/download/1",
            ),
        )
    }

    @Test
    fun `缺少响应头时回退到 URL 末段`() {
        assertEquals(
            "santi.epub",
            DownloadRequestPolicy.candidateFileName(null, "https://cdn.example.com/files/santi.epub"),
        )
    }

    @Test
    fun `占位名交给 EPUB 标题或用户确认`() {
        assertNull(DownloadRequestPolicy.candidateFileName("attachment; filename=\"redirection.epub\"", "https://cdn.example.com/dl"))
        assertNull(DownloadRequestPolicy.candidateFileName("attachment; filename=\"download.epub\"", "https://cdn.example.com/dl/1"))
    }

    @Test
    fun `来源站点只返回主机名`() {
        assertEquals("cdn.example.com", DownloadRequestPolicy.sourceHost("https://cdn.example.com/book.epub?token=x"))
        assertNull(DownloadRequestPolicy.sourceHost("not a url"))
    }
}
