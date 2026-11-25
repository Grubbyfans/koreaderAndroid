package com.yuezhou.reader.reader.online.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WebDownloadRegistryTest {

    @TempDir
    lateinit var root: File

    private val transferId = "00000000-0000-4000-8000-00000000002a"

    @Test
    fun `写入后可读取且签名链接参数不落盘`() {
        val registry = WebDownloadRegistry(root)
        val record = WebDownloadRecord(
            transferId = transferId,
            downloadId = 42L,
            suggestedFileName = "三体.epub",
            expectedBytes = 1024L,
            remoteRef = "https://cdn.example.com/book.epub?token=secret",
        )

        assertTrue(registry.write(record))
        val loaded = registry.read(42L)
        assertEquals(transferId, loaded?.transferId)
        assertEquals(42L, loaded?.downloadId)
        assertEquals("三体.epub", loaded?.suggestedFileName)
        assertEquals(1024L, loaded?.expectedBytes)
        assertEquals("https://cdn.example.com/book.epub", loaded?.remoteRef)
    }

    @Test
    fun `非法 transferId 或下载 ID 不写入`() {
        val registry = WebDownloadRegistry(root)
        assertFalse(registry.write(WebDownloadRecord(transferId = "not-a-uuid", downloadId = 1L)))
        assertFalse(registry.write(WebDownloadRecord(transferId = transferId, downloadId = 0L)))
        assertTrue(registry.readAll().isEmpty())
    }

    @Test
    fun `损坏记录在读取时被清理`() {
        val registry = WebDownloadRegistry(root)
        assertTrue(registry.write(WebDownloadRecord(transferId = transferId, downloadId = 7L)))
        File(root, "8.webdl").writeText("not-a-properties-file")
        File(root, "9.webdl").writeText("version=1\ntransferId=bad\n")

        val records = registry.readAll()

        assertEquals(1, records.size)
        assertEquals(7L, records.single().downloadId)
        assertFalse(File(root, "8.webdl").exists())
        assertFalse(File(root, "9.webdl").exists())
    }

    @Test
    fun `删除后不再返回记录`() {
        val registry = WebDownloadRegistry(root)
        registry.write(WebDownloadRecord(transferId = transferId, downloadId = 11L))

        registry.delete(11L)

        assertNull(registry.read(11L))
        assertTrue(registry.readAll().isEmpty())
    }
}
