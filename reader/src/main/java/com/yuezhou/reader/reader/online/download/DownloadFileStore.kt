package com.yuezhou.reader.reader.online.download

import android.content.Context
import java.io.File

/** 下载任务使用的应用私有目录；单元测试可注入临时目录实现。 */
interface DownloadFileStore {
    fun pendingDir(): File
    fun temporaryDir(): File
    fun handoffDir(): File
}

class ContextDownloadFileStore(context: Context) : DownloadFileStore {
    private val appContext = context.applicationContext

    override fun pendingDir(): File = OnlineFiles.pendingImport(appContext)

    override fun temporaryDir(): File = OnlineFiles.temporary(appContext)

    override fun handoffDir(): File = File(OnlineFiles.root(appContext), "handoff").apply { mkdirs() }
}
