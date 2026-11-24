package com.yuezhou.reader.core.online.provider

import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.BookPage
import com.yuezhou.reader.core.online.model.ConnectionState
import com.yuezhou.reader.core.online.model.DownloadResult
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.SearchQuery
import com.yuezhou.reader.core.online.model.UploadResult
import java.io.File

/**
 * 书源统一接口。
 *
 * 设计修正（阶段 A 核对实际源码后）：下载由书源直接落盘到应用管理路径并返回最终文件名，
 * 而非由调用方传入 target，以便委托现有 `CloudSync.downloadBook()` 的临时文件与导入流程。
 * 下载任务队列、进度节流与重启恢复由后续阶段的 BookDownloadManager 负责。
 */
interface BookProvider {
    val id: String
    val displayName: String
    val capabilities: Set<ProviderCapability>

    suspend fun getConnectionState(): ConnectionState

    suspend fun getBookDetail(remoteId: String): OnlineBook

    suspend fun download(
        request: BookDownloadRequest,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
        isBookOpen: (String) -> Boolean = { false },
    ): DownloadResult
}

/** 云存储型书源：目录浏览 + 上传，委托现有 CloudBackend / CloudService。 */
interface CloudBookProvider : BookProvider {
    suspend fun listBooks(
        directory: String = "",
        cursor: String? = null,
        force: Boolean = false,
    ): BookPage

    suspend fun upload(localBook: File): UploadResult
}

/** 检索型书源：关键词搜索；不实现 CloudBackend，不上传、不进度同步。 */
interface CatalogBookProvider : BookProvider {
    suspend fun search(query: SearchQuery): BookPage
}
