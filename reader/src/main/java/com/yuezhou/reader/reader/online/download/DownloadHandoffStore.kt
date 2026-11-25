package com.yuezhou.reader.reader.online.download

import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/**
 * 主进程与网页进程之间的私有交接文件。
 *
 * 仅保存任务标识、本地 content URI、大小和候选文件名；写入使用临时文件 + 原子替换。
 * 文件名固定为 `<transferId>.receipt`，transferId 必须是 UUID，拒绝任意路径。
 */
class DownloadHandoffStore(private val directory: File) {

    fun write(receipt: ExternalDownloadReceipt): Boolean {
        if (!isValidTransferId(receipt.transferId)) return false
        directory.mkdirs()
        val target = File(directory, "${receipt.transferId}.receipt")
        val temp = File(directory, ".${receipt.transferId}.tmp")
        val properties = Properties().apply {
            setProperty("version", "1")
            setProperty("transferId", receipt.transferId)
            setProperty("contentUri", receipt.contentUri)
            receipt.expectedBytes?.let { setProperty("expectedBytes", it.toString()) }
            receipt.suggestedFileName?.let { setProperty("suggestedFileName", it) }
            sanitizeRemoteRef(receipt.remoteRef)?.let { setProperty("remoteRef", it) }
            setProperty("accountScope", receipt.accountScope)
            receipt.downloadId?.let { setProperty("downloadId", it.toString()) }
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

    fun read(transferId: String): ExternalDownloadReceipt? {
        if (!isValidTransferId(transferId)) return null
        return readFile(File(directory, "$transferId.receipt"))
    }

    /** 读取全部回执；损坏或非 UUID 命名的文件会被删除，避免恢复循环。 */
    fun readAll(): List<ExternalDownloadReceipt> {
        val files = directory.listFiles() ?: return emptyList()
        val receipts = ArrayList<ExternalDownloadReceipt>()
        files.forEach { file ->
            if (file.isFile && file.name.endsWith(".receipt")) {
                val receipt = readFile(file)
                if (receipt == null) {
                    file.delete()
                } else {
                    receipts += receipt
                }
            }
        }
        return receipts
    }

    fun delete(transferId: String) {
        if (!isValidTransferId(transferId)) return
        File(directory, "$transferId.receipt").delete()
    }

    fun deleteAllForTest() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun readFile(file: File): ExternalDownloadReceipt? {
        if (!file.isFile) return null
        if (!file.name.removeSuffix(".receipt").let(::isValidTransferId)) return null
        return try {
            val properties = Properties().apply { file.inputStream().use { load(it) } }
            if (properties.getProperty("version") != "1") return null
            val transferId = properties.getProperty("transferId") ?: return null
            if (!isValidTransferId(transferId)) return null
            val contentUri = properties.getProperty("contentUri") ?: return null
            if (!contentUri.startsWith("content://")) return null
            ExternalDownloadReceipt(
                transferId = transferId,
                contentUri = contentUri,
                expectedBytes = properties.getProperty("expectedBytes")?.toLongOrNull(),
                suggestedFileName = properties.getProperty("suggestedFileName"),
                remoteRef = properties.getProperty("remoteRef"),
                accountScope = properties.getProperty("accountScope").orEmpty(),
                downloadId = properties.getProperty("downloadId")?.toLongOrNull(),
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
        private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        fun isValidTransferId(value: String): Boolean =
            UUID_PATTERN.matches(value) && runCatching { UUID.fromString(value) }.isSuccess

        /** 只保留 origin + path；query/fragment 可能携带临时令牌，禁止落盘。 */
        fun sanitizeRemoteRef(value: String?): String? {
            val raw = value?.trim().orEmpty()
            if (raw.isEmpty()) return null
            return try {
                val uri = URI(raw)
                if (uri.scheme != "https" && uri.scheme != "http") return null
                val authority = uri.authority ?: return null
                val path = uri.path.orEmpty()
                ("${uri.scheme}://$authority$path").take(500)
            } catch (_: Exception) {
                null
            }
        }
    }
}
