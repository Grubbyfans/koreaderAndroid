package com.yuezhou.reader.reader.online.download

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/**
 * 网页进程中发起的系统下载记录（阶段 D）。
 *
 * 网页进程与主进程是不同进程，无法共享单例；因此把「transferId ↔ downloadId ↔ 候选名」
 * 落在应用私有目录的小文件中，供主进程在广播、进程重启后按 downloadId 对账。
 * 不保存 Cookie、认证头或完整签名下载链接（[remoteRef] 已去 query/fragment）。
 */
data class WebDownloadRecord(
    val transferId: String,
    val downloadId: Long,
    val suggestedFileName: String? = null,
    val expectedBytes: Long? = null,
    val remoteRef: String? = null,
    val accountScope: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

class WebDownloadRegistry(private val directory: File) {

    @Synchronized
    fun write(record: WebDownloadRecord): Boolean {
        if (!isValidDownloadId(record.downloadId)) return false
        if (!DownloadHandoffStore.isValidTransferId(record.transferId)) return false
        directory.mkdirs()
        val target = File(directory, "${record.downloadId}.webdl")
        val temp = File(directory, ".${record.downloadId}.tmp")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("transferId", record.transferId)
            setProperty("downloadId", record.downloadId.toString())
            record.suggestedFileName?.let { setProperty("suggestedFileName", it) }
            record.expectedBytes?.let { setProperty("expectedBytes", it.toString()) }
            DownloadHandoffStore.sanitizeRemoteRef(record.remoteRef)?.let { setProperty("remoteRef", it) }
            setProperty("accountScope", record.accountScope)
            setProperty("createdAt", record.createdAt.toString())
        }
        return try {
            FileOutputStream(temp).use { output ->
                properties.store(output, null)
                output.fd.sync()
            }
            moveAtomically(temp, target)
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    @Synchronized
    fun read(downloadId: Long): WebDownloadRecord? {
        if (!isValidDownloadId(downloadId)) return null
        return readFile(File(directory, "$downloadId.webdl"))
    }

    /** 读取全部记录；损坏或非法的文件会被删除，避免恢复时反复失败。 */
    @Synchronized
    fun readAll(): List<WebDownloadRecord> {
        val files = directory.listFiles() ?: return emptyList()
        val records = ArrayList<WebDownloadRecord>()
        files.forEach { file ->
            if (file.isFile && file.name.endsWith(".webdl")) {
                val record = readFile(file)
                if (record == null) file.delete() else records += record
            }
        }
        return records.sortedBy { it.createdAt }
    }

    @Synchronized
    fun delete(downloadId: Long) {
        if (!isValidDownloadId(downloadId)) return
        File(directory, "$downloadId.webdl").delete()
    }

    private fun readFile(file: File): WebDownloadRecord? {
        if (!file.isFile) return null
        val expectedId = file.name.removeSuffix(".webdl").toLongOrNull() ?: return null
        if (!isValidDownloadId(expectedId)) return null
        return try {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            if (properties.getProperty("version") != "1") return null
            val transferId = properties.getProperty("transferId") ?: return null
            if (!DownloadHandoffStore.isValidTransferId(transferId)) return null
            val downloadId = properties.getProperty("downloadId")?.toLongOrNull() ?: return null
            if (downloadId != expectedId) return null
            WebDownloadRecord(
                transferId = transferId,
                downloadId = downloadId,
                suggestedFileName = properties.getProperty("suggestedFileName"),
                expectedBytes = properties.getProperty("expectedBytes")?.toLongOrNull()
                    ?.takeIf { it in 1..MAX_EXTERNAL_DOWNLOAD_BYTES },
                remoteRef = DownloadHandoffStore.sanitizeRemoteRef(properties.getProperty("remoteRef")),
                accountScope = properties.getProperty("accountScope").orEmpty(),
                createdAt = properties.getProperty("createdAt")?.toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: Exception) {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = true)
                source.delete()
            }
        }
    }

    companion object {
        fun forContext(context: Context): WebDownloadRegistry =
            WebDownloadRegistry(File(OnlineFiles.root(context), "web_downloads"))

        fun isValidDownloadId(value: Long): Boolean = value > 0L

        fun isValidTransferId(value: String): Boolean =
            DownloadHandoffStore.isValidTransferId(value) &&
                runCatching { UUID.fromString(value) }.isSuccess
    }
}
