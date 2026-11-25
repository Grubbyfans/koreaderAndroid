package com.yuezhou.reader.reader.online.download

import android.app.DownloadManager
import android.content.Context
import java.io.InputStream

/** 外部下载文件的读取与归属校验端口；生产实现基于 ContentResolver/DownloadManager。 */
interface ExternalDownloadSource {
    /** 校验 URI 是否属于本应用发起的下载，或本应用自己的受控 Provider。 */
    fun verifyOwnership(uri: String, downloadId: Long?): Boolean

    /** 返回可预知的大小；未知时返回 null。 */
    fun length(uri: String): Long?

    fun open(uri: String): InputStream
}

class ContentResolverExternalDownloadSource(context: Context) : ExternalDownloadSource {
    private val appContext = context.applicationContext
    private val downloadManager = appContext.getSystemService(DownloadManager::class.java)
    private val ownAuthority = "${appContext.packageName}.zlibrary.web"

    override fun verifyOwnership(uri: String, downloadId: Long?): Boolean {
        val parsed = ContentUriPolicy.parse(uri) ?: return false
        return when (parsed.authority) {
            ContentUriPolicy.SYSTEM_DOWNLOAD_AUTHORITY -> {
                if (downloadId == null || downloadManager == null) return false
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) return false
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
                } ?: false
            }
            ownAuthority -> downloadId == null
            else -> false
        }
    }

    override fun length(uri: String): Long? {
        val parsedUri = android.net.Uri.parse(uri)
        return try {
            appContext.contentResolver.openAssetFileDescriptor(parsedUri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0 }
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun open(uri: String): InputStream {
        return appContext.contentResolver.openInputStream(android.net.Uri.parse(uri))
            ?: error("无法读取下载文件")
    }
}

/** content URI 的纯 JVM 校验，便于单元测试；不做 Android 调用。 */
object ContentUriPolicy {
    /** 系统 DownloadManager 的 content authority；SDK 未公开该常量，按官方实现固定为 downloads。 */
    const val SYSTEM_DOWNLOAD_AUTHORITY = "downloads"

    data class Parsed(val scheme: String, val authority: String, val path: String)

    fun parse(value: String): Parsed? {
        val raw = value.trim()
        if (!raw.startsWith("content://")) return null
        return try {
            val uri = java.net.URI(raw)
            val authority = uri.authority ?: return null
            if (authority.isBlank()) return null
            Parsed(scheme = uri.scheme ?: return null, authority = authority, path = uri.path.orEmpty())
        } catch (_: Exception) {
            null
        }
    }
}
