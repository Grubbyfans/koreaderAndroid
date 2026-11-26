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
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudCredentials
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.LibraryStore
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

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
        return BookPage(
            items = library.books.map { it.toOnlineBook() },
            nextCursor = null,
            hasMore = false,
            fromCache = library.fromCache,
            fetchedAt = library.fetchedAt,
        )
    }

    override suspend fun getBookDetail(remoteId: String): OnlineBook =
        findRemoteBook(remoteId, currentDownloadScope()).toOnlineBook()

    suspend fun currentDownloadScope(): String = cloudSync.backend(forced = backendKind)?.backendId
        ?: throw ProviderException(ProviderErrorCode.NOT_CONFIGURED, "尚未配置云后端")

    suspend fun downloadRequest(book: CloudSync.RemoteBook): BookDownloadRequest {
        val accountScope = currentDownloadScope()
        return BookDownloadRequest(
            providerId = id,
            accountScope = accountScope,
            remoteId = book.bookId,
            fileName = LibraryStore(context).bookLocation(book.bookId)?.fileName ?: book.fileName,
            expectedSize = book.size,
        )
    }

    private suspend fun findRemoteBook(remoteId: String, accountScope: String): CloudSync.RemoteBook {
        val library = cloudSync.listBooks(force = false, forced = backendKind)
        check(currentDownloadScope() == accountScope) { "云后端已变更，请重新选择书籍" }
        return library.books.firstOrNull { it.bookId == remoteId } ?: throw ProviderException(ProviderErrorCode.NOT_FOUND, "云端未找到该文件，请刷新书库")
    }

    override suspend fun download(
        request: BookDownloadRequest,
        onProgress: (received: Long, total: Long?) -> Unit,
        isBookOpen: (String) -> Boolean,
    ): DownloadResult {
        return try {
            val backend = cloudSync.backend(forced = backendKind)
                ?: throw ProviderException(ProviderErrorCode.NOT_CONFIGURED, "尚未配置云后端")
            if (request.providerId != id || request.accountScope != backend.backendId) {
                throw ProviderException(ProviderErrorCode.INVALID_CONFIG, "云后端已变更，请回书源重新下载")
            }
            if (isBookOpen(request.remoteId)) {
                throw ProviderException(ProviderErrorCode.IO, "请关闭正在阅读的书籍后再下载")
            }
            val target = request.remotePath?.let(::File)
                ?: throw ProviderException(ProviderErrorCode.IO, "下载临时路径无效")
            val book = findRemoteBook(request.remoteId, request.accountScope)
            cloudSync.downloadBookTo(book, target, backend, onProgress)
            DownloadResult.Success(request.fileName, "")
        } catch (e: CancellationException) {
            throw e
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
        cloudCover = cloudCover,
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
        is IOException -> ProviderErrorCode.IO
        else -> ProviderErrorCode.UNKNOWN
    }
}
