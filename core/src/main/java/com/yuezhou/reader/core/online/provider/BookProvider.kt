package com.yuezhou.reader.core.online.provider

import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.BookPage
import com.yuezhou.reader.core.online.model.ConnectionState
import com.yuezhou.reader.core.online.model.DownloadResult
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.SearchQuery
import com.yuezhou.reader.core.online.model.UploadResult
import java.io.File

/** 书源将正文传输到请求的暂存路径，下载队列负责校验、发布和书库登记。 */
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

    /** 封面是无凭证的静态资源；不支持的书源返回失败，UI 回退为文字占位封面。 */
    suspend fun fetchCover(url: String): Result<ByteArray> =
        Result.failure(UnsupportedOperationException("该书源不支持封面加载"))
}
