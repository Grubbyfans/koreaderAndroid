package com.yuezhou.reader.core.cloud.webdav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DavPathTest {

    @Test
    fun `safe 拒绝非法路径`() {
        assertTrue(DavPath.safe("books/书名.epub"))
        assertTrue(DavPath.safe("library.json"))
        assertTrue(DavPath.safe("progress/foo.json"))
        assertFalse(DavPath.safe("/library.json"))          // 根目录外
        assertFalse(DavPath.safe("books//x.epub"))          // 双斜杠
        assertFalse(DavPath.safe("books/../x.epub"))        // 上级目录
        assertFalse(DavPath.safe("books/./x.epub"))         // 点段
        assertFalse(DavPath.safe("books/a%b.epub"))         // 百分号
        assertFalse(DavPath.safe("books/a\\b.epub"))        // 反斜杠
        assertFalse(DavPath.safe("books/a?b.epub"))         // 问号
        assertFalse(DavPath.safe("books/a#b.epub"))         // 井号
    }

    @Test
    fun `safeListing 允许字面百分号问号井号条目`() {
        assertTrue(DavPath.safeListing("books/a%b.epub"))
        assertTrue(DavPath.safeListing("books/a?b.epub"))
        assertTrue(DavPath.safeListing("books/a#b.epub"))
        assertFalse(DavPath.safeListing("books/a\\b.epub"))
        assertFalse(DavPath.safeListing("books/../x.epub"))
    }

    @Test
    fun `encode 保留安全字符并编码其余`() {
        assertEquals("books/%E4%B9%A6%E5%90%8D.epub", DavPath.encode("books/书名.epub"))
        assertEquals("library.json", DavPath.encode("library.json"))
    }
}

class DavXmlTest {

    private val multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/books/book1.epub</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:getcontentlength>12345</D:getcontentlength>
                <D:getetag>"abc123"</D:getetag>
                <D:getlastmodified>Sat, 01 Jan 2026 00:00:00 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/books/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    @Test
    fun `解析 multistatus 得到文件与目录条目`() {
        val result = DavXml.parse(multistatus)
        assertEquals(2, result.responses.size)

        val file = result.responses[0]
        assertEquals("/dav/books/book1.epub", file.href)
        assertEquals("HTTP/1.1 200 OK", file.status)
        assertEquals(false, file.isCollection)
        assertEquals(12345L, file.size)
        assertEquals("\"abc123\"", file.etag)
        assertEquals("Sat, 01 Jan 2026 00:00:00 GMT", file.modified)

        val dir = result.responses[1]
        assertEquals(true, dir.isCollection)
        assertNull(dir.size)
    }
}
