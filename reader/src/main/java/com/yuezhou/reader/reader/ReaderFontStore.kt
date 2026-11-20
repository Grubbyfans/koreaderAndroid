package com.yuezhou.reader.reader

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ReaderFontStore(private val context: Context) {
    data class Font(val id: String, val label: String)

    private val root = File(context.filesDir, DIRECTORY)
    private val names = context.getSharedPreferences("reader_fonts", Context.MODE_PRIVATE)

    fun installed(): List<Font> = root.listFiles().orEmpty()
        .filter { it.isFile && isImportedFont(it.name) }
        .map { Font(it.name, names.getString(it.name, null) ?: it.name) }
        .sortedWith(compareBy({ it.label.lowercase(Locale.ROOT) }, { it.id }))

    suspend fun importFont(uri: Uri): Font = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: throw IOException("无法获取字体文件名")
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension == "ttf" || extension == "otf") { "请选择 TTF 或 OTF 字体文件" }
        check(root.isDirectory || root.mkdirs()) { "无法创建字体目录" }
        val target = File(root, "${UUID.randomUUID()}.$extension")
        val tmp = File.createTempFile("font-", ".part", root)
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("无法打开字体文件")
            input.use {
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= MAX_BYTES) { "字体文件不能超过 64 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val signature = tmp.inputStream().use { input -> List(4) { input.read() } }
            require(signature in FONT_SIGNATURES) { "文件不是有效的 TTF 或 OTF 字体" }
            try {
                Typeface.createFromFile(tmp)
            } catch (_: RuntimeException) {
                throw IOException("Android 无法加载这个字体文件")
            }
            check(tmp.renameTo(target)) { "无法保存字体文件" }
            val label = displayName.substringBeforeLast('.').trim().take(100).ifBlank { displayName.take(100) }
            names.edit().putString(target.name, label).apply()
            Font(target.name, label)
        } finally {
            tmp.delete()
        }
    }

    companion object {
        internal const val DIRECTORY = "reader_fonts"
        private const val MAX_BYTES = 64L * 1024 * 1024
        private val namePattern = Regex("^[0-9a-f-]{36}\\.(?:ttf|otf)$")
        private val FONT_SIGNATURES = setOf(
            listOf(0, 1, 0, 0),
            "OTTO".map(Char::code),
            "true".map(Char::code),
        )

        internal fun isImportedFont(name: String): Boolean = namePattern.matches(name)
    }
}
