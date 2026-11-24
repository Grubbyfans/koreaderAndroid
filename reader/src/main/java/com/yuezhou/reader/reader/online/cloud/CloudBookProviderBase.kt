package com.yuezhou.reader.reader.online.cloud

import android.content.Context
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.BookPage
import com.yuezhou.reader.core.online.model.ConnectionState
import com.yuezhou.reader.core.online.model.DownloadResult
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.core.online.model.UploadResult
import com.yuezhou.reader.core.online.provider.CloudBookProvider
import com.yuezhou.reader.core.online.provider.ProviderCapability
import com.yuezhou.reader.core.protocol.BookNames
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudCredentials
import com.yuezhou.reader.reader.CloudSync
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * 云存储型书源适配器基类。
 *
 * 只做「CloudSync ↔ BookProvider」的数据转换与后端定向，不修改云端协议、索引或导入逻辑。
 * 现有 `CloudSync.RemoteBook` 保持原样；在线书源统一使用 [OnlineBook]。
 */
abstract class CloudBookProviderBase(
    protected val context: Context,
    protected val cloudSync: CloudSync,
    override val id: String,
    override val displayName: String,
    protected val backendKind: BackendKind,
) : CloudBookProvider {

    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.BROWSE,
        ProviderCapability.BOOK_DETAIL,
        ProviderCapability.DOWNLOAD,
        ProviderCapability.UPLOAD,
        ProviderCapability.DELETE,
        ProviderCapability.PROGRESS_SYNC,
    )

    /** 保留原始云端条目，供下载时还原 CloudSync.downloadBook 所需的完整参数。 */
    private val legacyIndex = LinkedHashMap<String, CloudSync.RemoteBook>()

    protected abstract fun isConfigured(credentials: CloudCredentials): Boolean

    override suspend fun getConnectionState(): ConnectionState {
        if (!isConfigured(CloudCredentialStore(context).load())) return ConnectionState.NotConfigured
        return try {
            if (cloudSync.backend(forced = backendKind) == null) ConnectionState.NotConfigured
            else ConnectionState.Connected
        } catch (e: Exception) {
            ConnectionState.Failed(e.message ?: "云端连接失败")
        }
    }

    override suspend fun listBooks(
        directory: String,
        cursor: String?,
        force: Boolean,
    ): BookPage {
        val library = cloudSync.listBooks(force = force, forced = backendKind)
        legacyIndex.clear()
        library.books.forEach { legacyIndex[it.bookId] = it }
        return BookPage(
            items = library.books.map { it.toOnlineBook() },
            nextCursor = null,
            hasMore = false,
        )
    }

    override suspend fun getBookDetail(remoteId: String): OnlineBook {
        legacyIndex[remoteId]?.let { return it.toOnlineBook() }
        val library = cloudSync.listBooks(force = false, forced = backendKind)
        val found = library.books.firstOrNull { it.bookId == remoteId }
            ?: throw ProviderException(ProviderErrorCode.NOT_FOUND, "云端未找到该书")
        legacyIndex[remoteId] = found
        return found.toOnlineBook()
    }

    override suspend fun download(
        request: BookDownloadRequest,
        onProgress: (received: Long, total: Long?) -> Unit,
        isBookOpen: (String) -> Boolean,
    ): DownloadResult {
        val legacy = legacyIndex[request.remoteId] ?: run {
            try {
                getBookDetail(request.remoteId)
            } catch (e: Exception) {
                return e.toDownloadFailure()
            }
            legacyIndex[request.remoteId]
                ?: return DownloadResult.Failed(ProviderErrorCode.NOT_FOUND, "云端未找到该文件，请刷新书库")
        }

        return try {
            val result = cloudSync.downloadBook(
                book = legacy,
                isOpen = isBookOpen,
                onProgress = { received, total, _ -> onProgress(received, total) },
                forced = backendKind,
            )
            result.fold(
                onSuccess = { fileName ->
                    DownloadResult.Success(
                        fileName = fileName,
                        localBookId = BookNames.localId(fileName).first ?: fileName,
                    )
                },
                onFailure = { it.toDownloadFailure() },
            )
        } catch (e: CancellationException) {
            DownloadResult.Cancelled
        } catch (e: Exception) {
            e.toDownloadFailure()
        }
    }

    override suspend fun upload(localBook: File): UploadResult {
        return try {
            val selection = cloudSync.uploadSelection(forced = backendKind)
            val entry = selection.books.firstOrNull { it.fileName == localBook.name }
                ?: return UploadResult.Failed(ProviderErrorCode.NOT_FOUND, "本地书库未找到 ${localBook.name}")
            val outcome = cloudSync.pushLocal(
                backendId = selection.backendId,
                books = listOf(entry),
                isStopped = { false },
                onProgress = { _, _ -> },
                forced = backendKind,
            )
            if (outcome.success) UploadResult.Success(entry.bookId)
            else UploadResult.Failed(ProviderErrorCode.UNKNOWN, outcome.message)
        } catch (e: CancellationException) {
            UploadResult.Failed(ProviderErrorCode.CANCELLED, "上传已取消")
        } catch (e: Exception) {
            UploadResult.Failed(mapCode(e), e.message ?: "上传失败")
        }
    }

    private fun CloudSync.RemoteBook.toOnlineBook() = OnlineBook(
        providerId = id,
        remoteId = bookId,
        title = bookId,
        fileName = fileName,
        fileSize = size,
        coverPng = coverPng,
        remotePath = "books/$fileName",
        local = local,
    )

    private fun Throwable.toDownloadFailure(): DownloadResult = when (this) {
        is CancellationException -> DownloadResult.Cancelled
        else -> DownloadResult.Failed(mapCode(this), message ?: "下载失败")
    }

    private fun mapCode(error: Throwable): ProviderErrorCode = when (error) {
        is ProviderException -> error.code
        is CloudException -> when (error.code) {
            ErrorCode.AUTH, ErrorCode.FORBIDDEN -> ProviderErrorCode.AUTH
            ErrorCode.NETWORK, ErrorCode.TLS -> ProviderErrorCode.NETWORK
            ErrorCode.CANCELLED -> ProviderErrorCode.CANCELLED
            ErrorCode.NOT_FOUND -> ProviderErrorCode.NOT_FOUND
            ErrorCode.UNSUPPORTED -> ProviderErrorCode.UNSUPPORTED
            ErrorCode.INVALID_INPUT -> ProviderErrorCode.INVALID_CONFIG
            ErrorCode.IO, ErrorCode.UNCERTAIN -> ProviderErrorCode.IO
            else -> ProviderErrorCode.UNKNOWN
        }
        else -> ProviderErrorCode.UNKNOWN
    }
}
