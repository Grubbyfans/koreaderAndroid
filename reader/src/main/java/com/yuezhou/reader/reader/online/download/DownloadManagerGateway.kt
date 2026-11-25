package com.yuezhou.reader.reader.online.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/** 系统下载的查询结果；主进程只用它判定完成/失败，不信任单独广播。 */
sealed interface SystemDownloadState {
    /** 系统任务不存在：可能被用户取消、被清理或从未登记成功。 */
    data object Unknown : SystemDownloadState
    data object Pending : SystemDownloadState
    data class Ready(val contentUri: String, val totalBytes: Long?) : SystemDownloadState
    data class Failed(val reason: String) : SystemDownloadState
}

/** 系统 DownloadManager 端口；网页进程入队，主进程查询与清理。 */
interface DownloadManagerGateway {
    /**
     * 入队到应用私有外部目录并返回系统下载 ID。
     * [destinationName] 只是落盘占位名，最终书名由 EPUB 校验后的命名策略决定。
     */
    fun enqueue(
        url: String,
        destinationName: String,
        userAgent: String? = null,
        mimeType: String? = null,
    ): Long?

    fun query(downloadId: Long): SystemDownloadState

    /** 清理系统下载及其私有落盘文件；验收完成后调用，避免重复占用空间。 */
    fun remove(downloadId: Long): Boolean
}

class AndroidDownloadManagerGateway(context: Context) : DownloadManagerGateway {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(DownloadManager::class.java)

    override fun enqueue(
        url: String,
        destinationName: String,
        userAgent: String?,
        mimeType: String?,
    ): Long? {
        val downloadManager = manager ?: return null
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(destinationName)
            // 新版系统已拒绝 VISIBILITY_HIDDEN（SecurityException），改为普通可见通知；
            // 文件仍落在应用私有外部目录，完成通知不代表导入成功。
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, destinationName)
            mimeType?.takeIf { it.isNotBlank() }?.let(::setMimeType)
            // 不转发 Cookie/Authorization 等认证头；UA 只用于 CDN 正常识别浏览器来源。
            userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
        }
        return try {
            downloadManager.enqueue(request)
        } catch (error: Exception) {
            android.util.Log.w("ZLibraryWebDL", "enqueue failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    override fun query(downloadId: Long): SystemDownloadState {
        val downloadManager = manager ?: return SystemDownloadState.Unknown
        if (downloadId <= 0L) return SystemDownloadState.Unknown
        val cursor = runCatching {
            downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        }.getOrNull() ?: return SystemDownloadState.Unknown
        cursor.use {
            if (!it.moveToFirst()) return SystemDownloadState.Unknown
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        .takeIf { size -> size >= 0L }
                    val uri = runCatching { downloadManager.getUriForDownloadedFile(downloadId) }
                        .getOrNull()?.toString()
                    if (uri.isNullOrBlank()) SystemDownloadState.Failed("下载文件不可读") else SystemDownloadState.Ready(uri, total)
                }
                DownloadManager.STATUS_FAILED -> {
                    val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    SystemDownloadState.Failed(reasonText(reason))
                }
                else -> SystemDownloadState.Pending
            }
        }
    }

    override fun remove(downloadId: Long): Boolean {
        val downloadManager = manager ?: return false
        if (downloadId <= 0L) return false
        return runCatching { downloadManager.remove(downloadId) > 0 }.getOrDefault(false)
    }

    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足"
        DownloadManager.ERROR_FILE_ERROR -> "写入下载文件失败"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "下载数据传输失败"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "服务器返回异常状态"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
        DownloadManager.ERROR_CANNOT_RESUME -> "无法继续下载，请重试"
        DownloadManager.ERROR_UNKNOWN -> "系统下载失败"
        else -> "系统下载失败（$reason）"
    }
}
