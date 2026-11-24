package com.yuezhou.reader.ui.online

import android.content.Context
import com.yuezhou.reader.DownloadForegroundService
import com.yuezhou.reader.reader.online.download.DownloadNotifier

/** 复用现有前台服务：下载期间保活并显示进度通知。 */
class AppDownloadNotifier(private val context: Context) : DownloadNotifier {
    override fun start(title: String) = DownloadForegroundService.start(context, title)

    override fun update(title: String, received: Long, total: Long?) {
        DownloadForegroundService.updateProgress(context, title, received, total ?: 0L)
    }

    override fun stop() = DownloadForegroundService.stop(context)
}
