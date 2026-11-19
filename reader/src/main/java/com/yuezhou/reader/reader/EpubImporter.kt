package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EPUB 导入：把 SAF 返回的 EPUB 复制到私有目录 files/epub/，
 * 供 WebViewAssetLoader 的 InternalStoragePathHandler 按受控 URL 提供。
 *
 * 命名规则：用 bookId（文件名去扩展名，见 core 的 BookNames）作为目录键，
 * 保证同一本书重复导入时覆盖旧文件（配合文件代次 fileGeneration 语义）。
 */
object EpubImporter {

    /** 私有 EPUB 根目录：files/epub/。 */
    fun epubDir(context: Context): File = File(context.filesDir, "epub").apply { mkdirs() }

    /**
     * 复制 SAF URI 指向的 EPUB 到私有目录，返回落盘文件名。
     * 文件名取源 URI 的 display name（去扩展名 + 规范化），
     * 冲突时保留 `.epub` 原名（同名覆盖语义见方案 §5.5）。
     */
    suspend fun import(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        try {
            val dir = epubDir(context)
            val name = displayName(context, uri) ?: "book-${System.currentTimeMillis()}.epub"
            val safeName = sanitizeFileName(name)
            val target = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(IllegalStateException("无法打开 EPUB: $uri"))
            Result.success(target)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 取 URI 的显示名（文件名）。 */
    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    /** 清理文件名中的路径分隔符与非法字符，避免目录穿越。 */
    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        return if (cleaned.isBlank()) "book.epub" else cleaned
    }
}
