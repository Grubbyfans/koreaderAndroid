package com.yuezhou.reader.web

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.yuezhou.reader.ui.online.DownloadManagerHolder

/**
 * 网页进程 → 主进程的下载登记入口（阶段 D）。
 *
 * 非导出（manifest `exported=false`）且只接受同应用 UID；不读写正式书库、不返回文件内容，
 * 只把系统下载 ID 交给主进程观察。实际校验、命名与导入仍由 BookDownloadManager 完成。
 */
class ZLibraryDownloadHandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ZLibraryWebHandoff.METHOD_WATCH) return null
        val appContext = context ?: return failure("应用上下文不可用")
        // 同 UID 才允许；Provider 非导出，这里再做一次防御。
        if (Binder.getCallingUid() != Process.myUid()) return failure("调用方不受信任")
        val downloadId = arg?.toLongOrNull()
            ?: extras?.getLong(ZLibraryWebHandoff.EXTRA_DOWNLOAD_ID, -1L)
            ?: -1L
        if (downloadId <= 0L) return failure("下载标识无效")
        return runCatching {
            DownloadManagerHolder.runtime(appContext).webDownloads.onEnqueued(downloadId)
            Bundle().apply { putBoolean(ZLibraryWebHandoff.RESULT_OK, true) }
        }.getOrElse { failure(it.message ?: "登记下载任务失败") }
    }

    // 只提供 call() 通道；查询/写入一律拒绝，避免变成通用文件接口。
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun failure(message: String): Bundle =
        Bundle().apply {
            putBoolean(ZLibraryWebHandoff.RESULT_OK, false)
            putString("message", message)
        }
}
