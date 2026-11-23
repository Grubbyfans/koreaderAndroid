package com.yuezhou.reader.core.speech

import java.io.File
import java.io.IOException

/** 解压预算按每个文件累计，不能只检查一次读取块的大小。 */
class ModelArchiveBudget(
    private val maxEntries: Int = 20_000,
    private val maxFileBytes: Long = 800L * 1024 * 1024,
    private val maxTotalBytes: Long = 1_500L * 1024 * 1024,
) {
    private var entries = 0
    private var fileBytes = 0L
    private var totalBytes = 0L
    fun beginEntry() {
        if (++entries > maxEntries) throw IOException("压缩包条目过多")
        fileBytes = 0
    }
    fun addBytes(bytes: Long) {
        require(bytes >= 0)
        if (bytes > maxFileBytes - fileBytes) throw IOException("单个文件解压体积超限")
        if (bytes > maxTotalBytes - totalBytes) throw IOException("解压总量超限")
        fileBytes += bytes
        totalBytes += bytes
    }
}

object ModelImportSafety {
    fun resolve(root: File, name: String): File {
        if (name.isBlank() || name.length > 1024 || name.contains('\\') || name.contains('\u0000') ||
            name.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(name) ||
            name.split('/').any { it == ".." || it.length > 255 }) {
            throw IOException("压缩包包含非法路径")
        }
        val file = File(root, name)
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) throw IOException("路径逃逸")
        return file
    }

    /** 完整清单必须在 staging 内准备好；重命名失败时恢复原目录。 */
    fun commit(staging: File, target: File) {
        target.parentFile?.mkdirs()
        val backup = File(target.parentFile, ".backup-${target.name}-${java.util.UUID.randomUUID()}")
        if (target.exists() && !target.renameTo(backup)) throw IOException("无法备份已有模型")
        if (!staging.renameTo(target)) {
            if (backup.exists() && !backup.renameTo(target)) throw IOException("模型替换失败，原模型保存在备份目录中")
            throw IOException("无法安装模型，原模型已恢复")
        }
        if (backup.exists()) backup.deleteRecursively()
    }
}
