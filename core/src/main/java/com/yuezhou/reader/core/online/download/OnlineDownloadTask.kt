package com.yuezhou.reader.core.online.download

import com.yuezhou.reader.core.online.model.ProviderErrorCode

/**
 * 统一下载任务的 UI/状态模型；持久化字段由 reader 层 Room Entity 承载。
 *
 * fileName 是本地唯一性判断依据；remoteId 只用于定位在线资源，不参与判重。
 */
data class OnlineDownloadTask(
    val taskId: String,
    val providerId: String,
    val accountScope: String = "",
    val remoteId: String,
    val bookTitle: String,
    val fileName: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val receivedBytes: Long = 0L,
    val totalBytes: Long? = null,
    /** 下载中的独立临时文件路径；完成/取消后清空。 */
    val localPath: String? = null,
    val errorCode: ProviderErrorCode? = null,
    val errorMessage: String? = null,
    val attempts: Int = 0,
    /** 同名覆盖需用户确认；只有确认后才允许替换本地书籍。 */
    val overwriteApproved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val localBookId: String? = null,
)
