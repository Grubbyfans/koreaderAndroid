package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val importMutex = Mutex()

    /** 私有 EPUB 根目录：files/epub/。 */
    fun epubDir(context: Context): File = File(context.filesDir, "epub").apply { mkdirs() }

    /**
     * 复制 SAF URI 指向的 EPUB 到私有目录，提取封面并登记书库，返回落盘文件名。
     * 文件名取源 URI 的 display name（去扩展名 + 规范化），
     * 冲突时保留 `.epub` 原名（同名覆盖语义见方案 §5.5）。
     *
     * 导入结果会同时：
     * - 把 EPUB 复制到 files/epub/（供 WebViewAssetLoader 提供正文）；
     * - 通过 [LibraryStore] 登记 library.json、提取并保存封面。
     */
    suspend fun import(context: Context, uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val dir = epubDir(context)
            val staging = File.createTempFile(".epub-import-", ".tmp", dir)
            try {
                val name = displayName(context, uri) ?: "book-${System.currentTimeMillis()}.epub"
                val safeName = EpubImportFiles.fileName(name)
                val target = File(dir, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    staging.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: throw IllegalStateException("无法读取 EPUB，请从文件管理器重新选择")
                EpubImportFiles.validate(staging)
                val cover = CoverExtractor.extract(staging)
                val title = CoverExtractor.extractTitle(staging)
                currentCoroutineContext().ensureActive()
                EpubImportFiles.install(staging, target)
                LibraryStore(context).registerBook(safeName, cover, title).getOrThrow()
                Result.success(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: OutOfMemoryError) {
                Result.failure(IllegalStateException("内存不足，请关闭其他应用后重试导入"))
            } catch (e: Exception) {
                Result.failure(e)
            } finally { staging.delete() }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path?.let { File(it).name }
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment
    }
}
