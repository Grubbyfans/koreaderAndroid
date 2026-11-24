package com.yuezhou.reader.reader.online.download

import android.content.Context
import java.io.File

/** 在线下载的应用专属目录；不迁移也不改写现有 files/epub 与 files/library。 */
object OnlineFiles {
    fun root(context: Context): File = File(context.filesDir, "downloads").apply { mkdirs() }

    fun temporary(context: Context): File = File(root(context), "temporary").apply { mkdirs() }

    fun pendingImport(context: Context): File = File(root(context), "pending_import").apply { mkdirs() }

    fun newTemp(context: Context, taskId: String): File =
        File.createTempFile("${taskId}-", ".part", temporary(context))

    /** 清理所有残留临时文件；不触碰 pending_import（可能正等待导入重试）。 */
    fun cleanTemporary(context: Context) {
        temporary(context).listFiles()?.forEach { it.delete() }
    }
}
