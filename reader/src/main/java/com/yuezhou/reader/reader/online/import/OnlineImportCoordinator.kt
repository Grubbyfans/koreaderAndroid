package com.yuezhou.reader.reader.online.import

import android.content.Context
import com.yuezhou.reader.core.protocol.BookNames
import com.yuezhou.reader.reader.CoverExtractor
import com.yuezhou.reader.reader.DownloadDirectory
import com.yuezhou.reader.reader.EpubImportFiles
import com.yuezhou.reader.reader.EpubImporter
import com.yuezhou.reader.reader.LibraryStore
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.OnlineBookMappingDao
import com.yuezhou.reader.reader.db.OnlineBookMappingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
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
        val local = store.bookLocation(bookId)
        val directory = DownloadDirectory(context)
        val destination = local?.tree ?: directory.uri
        val replacing = local != null || target.isFile

        // 下载前/确定文件名后/正式导入前均需判断；此处是导入前的最后一次拦截。
        if (replacing && !overwriteApproved) return@withContext OnlineImportResult.Conflict
        if (replacing && isBookOpen(bookId)) {
            return@withContext OnlineImportResult.Failed("请关闭正在阅读的《$bookId》后再覆盖")
        }

        val validation = EpubValidator.validate(pending)
        if (!validation.valid) return@withContext OnlineImportResult.Failed(validation.reason ?: "EPUB 校验失败")

        var backup: File? = null
        var replaced = false
        var newlyWritten = false
        val staging = File.createTempFile(".online-import-", ".tmp", target.parentFile)
        try {
            Files.copy(pending.toPath(), staging.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val cover = runCatching { CoverExtractor.extract(staging) }.getOrNull()
            val title = runCatching { CoverExtractor.extractTitle(staging) }.getOrNull()

            // 新书必须先把正文落到 files/epub/，否则 registerBook 会因正文缺失而失败。
            // 同名同内容则保留原文件与代次，避免阅读位置偏移。
            val source = destination?.let { directory.publish(it, staging, safeName) }
            if (source == null) {
                val placement = placeBody(staging, target, target.isFile)
                backup = placement.backup
                replaced = placement.replaced
                newlyWritten = placement.newlyWritten
            }

            val registered = store.registerBook(
                safeName, cover, title, source, destination,
                sourceSize = pending.length(),
            )
            if (registered.isFailure) {
                if (replaced && backup != null) safeMove(backup!!, target)
                if (newlyWritten) target.delete()
                val prefix = if (replaced || newlyWritten) "导入失败，已恢复原文件：" else "导入失败："
                return@withContext OnlineImportResult.Failed(
                    prefix + (registered.exceptionOrNull()?.message ?: "未知错误"),
                )
            }
            val localBookId = registered.getOrThrow()
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
            backup?.delete()
            if (source != null) target.delete()
            OnlineImportResult.Success(localBookId, safeName)
        } catch (e: Exception) {
            if (replaced && backup != null) runCatching { safeMove(backup!!, target) }
            if (newlyWritten) runCatching { target.delete() }
            OnlineImportResult.Failed("导入失败：${e.message ?: "未知错误"}")
        } finally {
            staging.delete()
        }
    }

    companion object {
        /** 正文落盘结果：backup/replaced 用于覆盖回滚，newlyWritten 用于新书失败时清理孤儿文件。 */
        internal data class BodyPlacement(
            val backup: File?,
            val replaced: Boolean,
            val newlyWritten: Boolean,
        )

        /**
         * 把校验通过的 staging 正文放到书库目录。此处不触碰 bookId、进度与 library.json。
         * 同名不同内容先备份再替换；替换失败时内部回滚并抛异常，由调用方统一转成失败结果。
         */
        internal fun placeBody(staging: File, target: File, replacing: Boolean): BodyPlacement {
            if (!replacing) {
                safeMove(staging, target)
                return BodyPlacement(null, false, true)
            }
            if (sameContent(staging, target)) {
                staging.delete()
                return BodyPlacement(null, false, false)
            }
            val backup = File(target.parentFile, ".${target.name}.backup-${System.currentTimeMillis()}")
            safeMove(target, backup)
            try {
                safeMove(staging, target)
            } catch (e: Exception) {
                safeMove(backup, target)
                throw e
            }
            return BodyPlacement(backup, true, false)
        }

        internal fun safeMove(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        private fun sameContent(a: File, b: File): Boolean {
            if (a.length() != b.length()) return false
            DataInputStream(a.inputStream().buffered()).use { left ->
                DataInputStream(b.inputStream().buffered()).use { right ->
                    val first = ByteArray(64 * 1024)
                    val second = ByteArray(first.size)
                    var remaining = a.length()
                    while (remaining > 0) {
                        val n = minOf(first.size.toLong(), remaining).toInt()
                        left.readFully(first, 0, n)
                        right.readFully(second, 0, n)
                        for (i in 0 until n) if (first[i] != second[i]) return false
                        remaining -= n
                    }
                    return true
                }
            }
        }
    }
}
