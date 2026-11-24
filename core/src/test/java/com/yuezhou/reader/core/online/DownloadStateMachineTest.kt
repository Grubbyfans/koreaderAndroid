package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.download.DownloadRetryPolicy
import com.yuezhou.reader.core.online.download.DownloadStatus
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DownloadStateMachineTest {
    @Test
    fun `正常链路状态可依次跳转`() {
        val chain = listOf(
            DownloadStatus.QUEUED, DownloadStatus.PREPARING, DownloadStatus.DOWNLOADING,
            DownloadStatus.VERIFYING, DownloadStatus.IMPORTING, DownloadStatus.COMPLETED,
        )
        chain.zipWithNext().forEach { (from, to) -> assertTrue(DownloadStatus.canTransit(from, to)) }
    }

    @Test
    fun `完成与取消是终态且不允许跳回下载中`() {
        assertFalse(DownloadStatus.canTransit(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING))
        assertFalse(DownloadStatus.canTransit(DownloadStatus.CANCELLED, DownloadStatus.QUEUED))
        assertThrows<IllegalArgumentException> {
            DownloadStatus.requireTransit(DownloadStatus.COMPLETED, DownloadStatus.DOWNLOADING)
        }
    }

    @Test
    fun `导入失败可重试但最终态不可复活`() {
        assertTrue(DownloadStatus.canTransit(DownloadStatus.IMPORT_FAILED, DownloadStatus.QUEUED))
        assertTrue(DownloadStatus.canTransit(DownloadStatus.FAILED, DownloadStatus.QUEUED))
        assertFalse(DownloadStatus.canTransit(DownloadStatus.COMPLETED, DownloadStatus.QUEUED))
    }

    @Test
    fun `只有网络错误最多自动重试两次`() {
        assertTrue(DownloadRetryPolicy.shouldRetry(ProviderErrorCode.NETWORK, 1))
        assertTrue(DownloadRetryPolicy.shouldRetry(ProviderErrorCode.NETWORK, 2))
        assertFalse(DownloadRetryPolicy.shouldRetry(ProviderErrorCode.NETWORK, 3))
        assertFalse(DownloadRetryPolicy.shouldRetry(ProviderErrorCode.QUOTA, 1))
        assertFalse(DownloadRetryPolicy.shouldRetry(ProviderErrorCode.AUTH, 1))
        assertFalse(DownloadRetryPolicy.shouldRetry(ProviderErrorCode.RATE_LIMITED, 1))
    }
}
