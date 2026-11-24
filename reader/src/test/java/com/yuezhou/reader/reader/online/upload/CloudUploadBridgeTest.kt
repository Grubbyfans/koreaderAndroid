package com.yuezhou.reader.reader.online.upload

import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudUploadBridgeTest {

    private val entry = LibraryStore.Entry(
        bookId = "book",
        fileName = "book.epub",
        size = 1024L,
        coverPng = null,
        title = "测试书",
        percent = null,
        location = null,
        lastReadAt = null,
        sourceUri = null,
        localPath = "/tmp/book.epub",
    )

    @Test
    fun `两个云端分别记录结果且一侧失败不影响另一侧`() = runBlocking {
        val kinds = mutableListOf<BackendKind>()
        val bridge = CloudUploadBridge(
            findEntry = { entry },
            send = { kind, _ ->
                kinds += kind
                if (kind == BackendKind.Baidu) {
                    CloudUploadBridge.SendResult.Done(CloudSync.SyncOutcome(true, "已上传 1 本"))
                } else {
                    CloudUploadBridge.SendResult.Done(CloudSync.SyncOutcome(false, "上传失败：网络超时"))
                }
            },
        )

        val report = bridge.upload()

        assertEquals(listOf(BackendKind.Baidu, BackendKind.WebDav), kinds)
        assertEquals(CloudUploadBridge.Status.UPLOADED, report.results[0].status)
        assertEquals(CloudUploadBridge.Status.FAILED, report.results[1].status)
        assertTrue(report.success)
        assertTrue(report.summary.contains("百度网盘"))
        assertTrue(report.summary.contains("WebDAV"))
    }

    @Test
    fun `未配置的云端跳过不影响已配置目标`() = runBlocking {
        val bridge = CloudUploadBridge(
            findEntry = { entry },
            send = { kind, _ ->
                if (kind == BackendKind.WebDav) {
                    CloudUploadBridge.SendResult.NotConfigured
                } else {
                    CloudUploadBridge.SendResult.Done(CloudSync.SyncOutcome(true, "已上传 1 本"))
                }
            },
        )

        val report = bridge.upload()

        assertEquals(CloudUploadBridge.Status.UPLOADED, report.results[0].status)
        assertEquals(CloudUploadBridge.Status.SKIPPED, report.results[1].status)
        assertTrue(report.success)
        assertEquals("未配置该云端", report.results[1].message)
    }

    @Test
    fun `上传异常被隔离为单目标失败`() = runBlocking {
        val bridge = CloudUploadBridge(
            findEntry = { entry },
            send = { kind, _ ->
                if (kind == BackendKind.Baidu) throw IllegalStateException("认证过期") else
                    CloudUploadBridge.SendResult.Done(CloudSync.SyncOutcome(true, "已上传 1 本"))
            },
        )

        val report = bridge.upload()

        assertEquals(CloudUploadBridge.Status.FAILED, report.results[0].status)
        assertEquals("认证过期", report.results[0].message)
        assertEquals(CloudUploadBridge.Status.UPLOADED, report.results[1].status)
        assertTrue(report.success)
    }

    @Test
    fun `本地书籍不存在时不发起任何上传`() = runBlocking {
        var invoked = false
        val bridge = CloudUploadBridge(
            findEntry = { null },
            send = { _, _ -> invoked = true; CloudUploadBridge.SendResult.Done(CloudSync.SyncOutcome(true, "已上传 1 本")) },
        )

        val report = bridge.upload()

        assertFalse(invoked)
        assertEquals(2, report.results.size)
        assertTrue(report.results.all { it.status == CloudUploadBridge.Status.FAILED })
        assertFalse(report.success)
    }
}
