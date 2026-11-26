package com.yuezhou.reader.reader

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.DataInputStream

/** EPUB 导入的文件操作，不依赖 Android，供外部打开和应用内导入共用。 */
internal object EpubImportFiles {
    fun fileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        val base = cleaned.replace(Regex("(?i)\\.epub$"), "").trim()
        return "${base.ifBlank { "book" }}.epub"
    }

    fun validate(file: File) = CoverExtractor.validatePackage(file)

    data class Placement(val backup: File? = null, val newlyWritten: Boolean = false) {
        fun commit() {
            backup?.delete()
        }

        fun rollback(target: File) {
            if (backup != null) moveReplacing(backup, target)
            else if (newlyWritten) target.delete()
        }
    }

    fun install(staging: File, target: File): Placement {
        if (!target.isFile) {
            moveReplacing(staging, target)
            return Placement(newlyWritten = true)
        }
        if (sameContent(staging, target)) {
            // 重复打开同一文件时复用正文，保留 mtime 和已有朗读位置。
            staging.delete()
            return Placement()
        }

        val backup = File.createTempFile(".epub-backup-", ".tmp", target.parentFile)
        try {
            // Keep the original at its published path until the atomic replacement is ready.
            backup.delete()
            try {
                Files.createLink(backup.toPath(), target.toPath())
            } catch (_: IOException) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            } catch (_: UnsupportedOperationException) {
                Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            }
            moveReplacing(staging, target)
            return Placement(backup)
        } catch (e: Exception) {
            backup.delete()
            throw e
        }
    }

    private fun moveReplacing(staging: File, target: File) {
        try {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
