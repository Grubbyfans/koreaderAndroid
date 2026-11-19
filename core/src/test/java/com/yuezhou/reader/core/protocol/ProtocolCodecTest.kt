package com.yuezhou.reader.core.protocol

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 协议双向编解码测试，对齐方案 §14.3 与附录夹具。 */
class ProtocolCodecTest : FixtureTest() {

    @Test
    fun `library 夹具解码并严格校验通过`() {
        val cat = LibraryCatalog.decode(fixture("library/library.valid.json"))
        assertEquals(2, cat.books.size)
        assertEquals("示例书", cat.books[0].bookId)
        assertEquals("books/示例书.epub", cat.books[0].path)
        assertEquals("8bf8d30bb96e451cab236e154e267c5b", cat.books[0].coverVersion)
        assertEquals("无封面书", cat.books[1].bookId) // 文件名 .EPUB 大写
        assertNull(cat.books[1].coverVersion)
    }

    @Test
    fun `library 读后写出再读回字段一致`() {
        val first = LibraryCatalog.decode(fixture("library/library.valid.json"))
        val encoded = LibraryCatalog.encode(first)
        val second = LibraryCatalog.decode(encoded)
        assertEquals(first.books.map { it.bookId }, second.books.map { it.bookId })
        assertEquals(first.books.map { it.path }, second.books.map { it.path })
        assertEquals(first.books.map { it.coverVersion }, second.books.map { it.coverVersion })
        assertFalse(encoded.contains("contentSha256"))
    }

    @Test
    fun `library 未知版本必须报错`() {
        val bad = fixture("library/library.valid.json").replace("\"schemaVersion\": 2", "\"schemaVersion\": 3", ignoreCase = true)
        assertThrows<ProtocolError.Unsupported> { LibraryCatalog.decode(bad) }
    }

    @Test
    fun `library title 不等于 bookId 必须拒绝`() {
        val bad = fixture("library/library.valid.json").replace("\"title\": \"示例书\"", "\"title\": \"别的名字\"")
        assertThrows<ProtocolError.InvalidInput> { LibraryCatalog.decode(bad) }
    }

    @Test
    fun `library 含 contentSha256 必须拒绝`() {
        val bad = fixture("library/library.valid.json").replace(
            "\"size\": 10485760,", "\"size\": 10485760, \"contentSha256\": \"abc\","
        )
        assertThrows<ProtocolError.InvalidInput> { LibraryCatalog.decode(bad) }
    }

    @Test
    fun `library 有 coverPath 无 coverVersion 必须拒绝`() {
        val bad = fixture("library/library.valid.json").replace(
            "\"coverVersion\": \"8bf8d30bb96e451cab236e154e267c5b\"", "\"unused\": \"x\""
        )
        assertThrows<ProtocolError.InvalidInput> { LibraryCatalog.decode(bad) }
    }

    @Test
    fun `进度夹具解码并严格校验通过`() {
        val p = ProgressRecords.decode(fixture("progress/progress.valid.json"))
        assertEquals("示例书", p.bookId)
        assertEquals(7L, p.revision)
        assertEquals(0.315, p.percent!!, 1e-9)
        assertEquals("android-cf7b93eb", p.deviceId)
    }

    @Test
    fun `进度写出后不含 contentSha256 且能回读`() {
        val p = ProgressRecords.decode(fixture("progress/progress.valid.json"))
        val encoded = ProgressRecords.encode(p)
        assertFalse(encoded.contains("contentSha256"))
        assertEquals(p.xpointer, ProgressRecords.decode(encoded).xpointer)
    }

    @Test
    fun `进度 updatedAt 为 Z 时必须拒绝`() {
        val bad = fixture("progress/progress.valid.json").replace("+08:00", "Z")
        assertThrows<ProtocolError.InvalidInput> { ProgressRecords.decode(bad) }
    }

    @Test
    fun `进度含 contentSha256 必须拒绝`() {
        val bad = fixture("progress/progress.valid.json").replace(
            "\"format\": \"epub\",", "\"format\": \"epub\", \"contentSha256\": \"abc\","
        )
        assertThrows<ProtocolError.InvalidInput> { ProgressRecords.decode(bad) }
    }

    @Test
    fun `进度 revision 非正整数必须拒绝`() {
        val bad = fixture("progress/progress.valid.json").replace("\"revision\": 7", "\"revision\": 0")
        assertThrows<ProtocolError.InvalidInput> { ProgressRecords.decode(bad) }
    }

    @Test
    fun `书名规则 - 云端与本地的身份和校验`() {
        assertEquals("示例书" to null, BookNames.cloudId("示例书.epub"))
        assertEquals("示例书" to null, BookNames.cloudId("示例书.EPUB"))
        assertEquals("示例书" to null, BookNames.localId("示例书.epub"))
        assertTrue(BookNames.valid("示例书"))
        assertFalse(BookNames.valid(" 示例书")) // 首尾空格
        assertFalse(BookNames.valid("a/b"))     // 路径分隔符
        assertFalse(BookNames.valid("a%b"))     // 云端禁用字符
        // 本地规则不禁用 %，且允许超过 255 字节
        assertTrue(BookNames.localValid("a%b"))
    }
}
