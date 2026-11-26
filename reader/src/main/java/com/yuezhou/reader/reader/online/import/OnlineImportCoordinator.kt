package com.yuezhou.reader.reader.online.import

import android.content.Context
import android.util.Log
import com.yuezhou.reader.core.protocol.BookNames
import com.yuezhou.reader.reader.CoverExtractor
import com.yuezhou.reader.reader.DownloadDirectory
import com.yuezhou.reader.reader.EpubImportFiles
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.OnlineBookMappingDao
import com.yuezhou.reader.reader.db.OnlineBookMappingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 在线下载导入结果；Conflict 表示本地已有同名文件且用户尚未确认覆盖。 */
sealed interface OnlineImportResult {
    data class Success(val bookId: String, val fileName: String) : OnlineImportResult
    data object Conflict : OnlineImportResult
    data class Failed(val message: String) : OnlineImportResult
}

/**
 * 在线书籍导入协调器：文件名唯一判断 → 同名覆盖确认前置 → 安全替换 → 复用现有导入与书库登记。
 *
 * 不修改 bookId 规则、不重置阅读进度/书签、不自动改动云端文件；映射表只用于展示下载状态。
 */
class OnlineImportCoordinator(
    private val context: Context,
    private val mappingDao: OnlineBookMappingDao = AppDatabase.get(context).onlineBookMappingDao(),
) {
    suspend fun import(
        pending: File,
        fileName: String,
        providerId: String,
        accountScope: String,
        remoteId: String,
        overwriteApproved: Boolean,
        isBookOpen: (String) -> Boolean,
    ): OnlineImportResult = withContext(Dispatchers.IO) {
        val safeName = EpubImportFiles.fileName(fileName)
        val bookId = BookNames.localId(safeName).first
            ?: return@withContext OnlineImportResult.Failed("文件名无效，无法导入")
        val target = File(EpubImporter.epubDir(context), safeName)
        val store = LibraryStore(context)
        val directory = DownloadDirectory(context)
        val staging = File.createTempFile(".online-import-", ".tmp", target.parentFile)
        try {
            Files.copy(pending.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val cover = runCatching { CoverExtractor.extract(staging) }.getOrNull()
            val title = CoverExtractor.extractTitle(staging)
            val authors = CoverExtractor.extractAuthors(staging)
            store.withCatalogMutation {
                val local = store.bookLocation(bookId)
                val destination = local?.tree ?: directory.uri
                val replacing = local != null || target.isFile
                if (replacing && !overwriteApproved) return@withCatalogMutation OnlineImportResult.Conflict
                if (replacing && isBookOpen(bookId)) {
                    return@withCatalogMutation OnlineImportResult.Failed("请关闭正在阅读的《$bookId》后再覆盖")
                }

                val localBookId = if (destination != null) {
                    directory.publish(destination, staging, safeName) { source ->
                        store.registerBookLocked(safeName, cover, title, source, destination, pending.length(), authors)
                    }.also { target.delete() }
                } else {
                    val placement = EpubImportFiles.install(staging, target)
                    val registered = try {
                        store.registerBookLocked(safeName, cover, title, authors = authors)
                    } catch (e: Exception) {
                        placement.rollback(target)
                        throw e
                    }
                    placement.commit()
                    registered
                }
                try {
                    mappingDao.upsert(
                        OnlineBookMappingEntity(
                            providerId = providerId,
                            accountScope = accountScope,
                            remoteId = remoteId,
                            fileName = safeName,
                            localBookId = localBookId,
                            downloadedAt = System.currentTimeMillis(),
                            active = true,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("OnlineImport", "书籍已导入，无法更新书源映射", e)
                }
                OnlineImportResult.Success(localBookId, safeName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OnlineImportResult.Failed("导入失败：${e.message ?: "未知错误"}")
        } finally {
            staging.delete()
        }
    }
}
