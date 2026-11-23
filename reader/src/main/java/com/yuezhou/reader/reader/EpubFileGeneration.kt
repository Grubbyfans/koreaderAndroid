package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * EPUB 文件代次：随文件内容/元数据变化而变化，用于判定同名覆盖后旧朗读位置是否失效。
 *
 * 规则为 `"<size>-<lastModified>"`；本地私有文件用文件系统 stat，SAF URI 用
 * 文档提供者列（缺失时退回文件描述符长度）。无法取得可靠信息时返回空串，
 * 此时不做代次失效判断，避免误删可恢复位置。
 */
object EpubFileGeneration {

    fun of(context: Context, fileName: String, uri: Uri?): String = runCatching {
        if (uri == null) {
            val file = File(EpubImporter.epubDir(context), fileName)
            "${file.length()}-${file.lastModified()}"
        } else {
            val resolver = context.contentResolver
            var size = -1L
            var modified = -1L
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                        val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                        if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                            modified = cursor.getLong(modifiedIndex)
                        }
                    }
                }
            }
            if (size < 0) {
                runCatching {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
                }
            }
            if (size < 0 && modified < 0) "" else "$size-$modified"
        }
    }.getOrDefault("")
}
