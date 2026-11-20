package com.yuezhou.reader.core.cloud

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CloudServiceTest {

    /** 造一个临时 EPUB 文件（内容任意，测试只关心 size）。 */
    private fun epub(content: String = "dummy"): File {
        val f = File.createTempFile("book", ".epub")
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `index 不存在时返回空目录`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        val idx = svc.index()
        assertTrue(idx.books.isEmpty())
    }

    @Test
    fun `首次上传书会创建 books 和 covers 目录`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        val f = epub("hello")
        svc.uploadBook(f, "测试书.epub")
        assertTrue(backend.mkdirCalls.contains("books"))
        assertTrue(backend.mkdirCalls.contains("covers"))
    }

    @Test
    fun `同名覆盖保留旧封面`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)

        // 第一次上传，带封面
        val f1 = epub("v1")
        val r1 = svc.uploadBook(f1, "测试书.epub") { "cover-data" }
        val cover1 = r1.record.coverPath
        assertTrue(cover1 != null)

        // 第二次同名上传，不带封面（makeCover=null），应保留旧封面
        val f2 = epub("v2")
        val r2 = svc.uploadBook(f2, "测试书.epub", makeCover = null)
        assertEquals(cover1, r2.record.coverPath)
        assertEquals(r1.record.coverVersion, r2.record.coverVersion)

        // 列表里只有一条
        val idx = svc.index()
        assertEquals(1, idx.books.size)
        assertEquals("测试书", idx.books[0].bookId)
    }

    @Test
    fun `上传后列表可读且 bookId 正确`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        val f = epub("content")
        svc.uploadBook(f, "红楼梦.epub")
        val idx = svc.index()
        assertEquals(1, idx.books.size)
        assertEquals("红楼梦", idx.books[0].bookId)
        assertEquals("books/红楼梦.epub", idx.books[0].path)
    }

    @Test
    fun `uploadProgress 首次写入 revision 为 1`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        svc.uploadProgress("测试书", ProgressPayload(xpointer = "/body/text().0", deviceId = "dev1"))
        val raw = backend.text("progress/测试书.json")!!
        val decoded = com.yuezhou.reader.core.protocol.ProgressRecords.decode(raw)
        assertEquals(1L, decoded.revision)
        assertEquals("测试书", decoded.bookId)
    }

    @Test
    fun `uploadProgress 再次上传递增 revision`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        svc.uploadProgress("测试书", ProgressPayload(xpointer = "/body/text().0", deviceId = "dev1"))
        svc.uploadProgress("测试书", ProgressPayload(xpointer = "/body/text().5", deviceId = "dev1"))
        val raw = backend.text("progress/测试书.json")!!
        val decoded = com.yuezhou.reader.core.protocol.ProgressRecords.decode(raw)
        assertEquals(2L, decoded.revision)
        assertEquals("/body/text().5", decoded.xpointer)
    }

    @Test
    fun `downloadProgress 不存在返回 null`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        assertNull(svc.downloadProgress("不存在"))
    }

    @Test
    fun `deleteBook 删除正文进度封面并更新列表`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)

        val f = epub("x")
        svc.uploadBook(f, "测试书.epub") { "cover" }
        svc.uploadProgress("测试书", ProgressPayload(xpointer = "/body/text().0", deviceId = "dev1"))

        val result = svc.deleteBook("测试书")
        assertTrue(result.deleted.contains("books/测试书.epub"))
        assertTrue(result.deleted.contains("progress/测试书.json"))
        assertTrue(result.deleted.contains("covers/测试书.json"))

        // 列表清空
        val idx = svc.index()
        assertTrue(idx.books.isEmpty())
    }

    @Test
    fun `deleteBook 对已不存在的进度不阻断`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        val f = epub("x")
        // 上传书但不上传进度
        svc.uploadBook(f, "测试书.epub", makeCover = null)
        // 删除时 progress 不存在，应 NOT_FOUND 但继续，不抛
        val result = svc.deleteBook("测试书")
        assertTrue(result.deleted.contains("books/测试书.epub"))
    }

    @Test
    fun `uploadBook 用非法文件名抛 INVALID_INPUT`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        val f = epub("x")
        try {
            svc.uploadBook(f, "非法名.txt")
            org.junit.jupiter.api.Assertions.fail("应抛异常")
        } catch (e: CloudException) {
            assertEquals(ErrorCode.INVALID_INPUT, e.code)
        }
    }

    @Test
    fun `uploadProgress 云端进度书名不匹配抛 CONFLICT`() = runTest {
        val backend = FakeBackend()
        val svc = CloudService(backend)
        // 预先放一个书名不匹配的进度（用另一本书的合法进度）
        val other = com.yuezhou.reader.core.protocol.CloudProgress(
            bookId = "另一本", title = "另一本", size = 0,
            xpointer = "/body/text().0", percent = null, revision = 1,
            writeId = "w1", deviceId = "dev1",
            updatedAt = "2026-11-20T10:00:00+08:00",
        )
        backend.putText("progress/测试书.json", com.yuezhou.reader.core.protocol.ProgressRecords.encode(other))
        try {
            svc.uploadProgress("测试书", ProgressPayload(xpointer = "/body/text().0", deviceId = "dev1"))
            org.junit.jupiter.api.Assertions.fail("应抛 CONFLICT")
        } catch (e: CloudException) {
            assertEquals(ErrorCode.CONFLICT, e.code)
        }
    }
}
