package com.yuezhou.reader.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.util.UUID

/** Persisted SAF destination for the user-visible copy of an EPUB. */
class DownloadDirectory(private val context: Context) {
    private val prefs = context.getSharedPreferences("download_directory", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver

    val uri: Uri?
        get() = prefs.getString("tree", null)?.let(Uri::parse)

    fun label(): String = prefs.getString("label", null) ?: "应用私有目录"

    fun select(tree: Uri) {
        resolver.takePersistableUriPermission(
            tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        val directory = writableDirectory(tree)
        prefs.edit()
            .putString("tree", tree.toString())
            .putString("label", directory.name ?: DocumentsContract.getTreeDocumentId(tree))
            .apply()
    }

    fun reset() {
        prefs.edit().remove("tree").remove("label").apply()
    }

    private fun writableDirectory(tree: Uri): DocumentFile =
        DocumentFile.fromTreeUri(context, tree)?.takeIf { it.isDirectory && it.canWrite() }
            ?: throw IOException("下载目录无法写入，请在设置中重新选择")

    internal fun <T> publish(tree: Uri, source: File, fileName: String, commit: (Uri) -> T): T {
        val directory = writableDirectory(tree)
        val existing = directory.findFile(fileName)
        if (existing != null && !existing.isFile) {
            throw IOException("下载目录中有同名文件夹")
        }

        val id = UUID.randomUUID()
        val staged = directory.createFile("application/octet-stream", "yuezhou-$id.part")
            ?: throw IOException("无法在下载目录创建临时文件")
        var backedUp = false
        var installed = false
        try {
            val bytesWritten = resolver.openOutputStream(staged.uri, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("无法写入下载目录")
            if (bytesWritten != source.length()) throw IOException("写入所选目录的 EPUB 大小不符")
            if (existing != null) {
                backedUp = existing.renameTo("yuezhou-$id.backup")
                if (!backedUp) throw IOException("下载目录不支持安全替换同名文件")
            }
            if (!staged.renameTo(fileName)) {
                throw IOException("下载目录不支持发布文件，请选择其他目录")
            }
            if (resolver.openInputStream(staged.uri)?.use { it.read() }?.let { it >= 0 } != true) {
                throw IOException("无法读取所选目录中的 EPUB")
            }
            val result = commit(staged.uri)
            installed = true
            return result
        } finally {
            if (!installed) {
                staged.delete()
                if (backedUp && existing?.renameTo(fileName) != true) {
                    throw IOException("发布失败，原书保留在 yuezhou-$id.backup")
                }
            } else if (backedUp) {
                existing?.delete()
            }
        }
    }
}
