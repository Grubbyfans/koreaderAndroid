package com.yuezhou.reader.reader.online.import

import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * 在线下载 EPUB 的安全校验：ZIP 魔数、mimetype 首条目、container.xml / OPF、
 * 解压体积与路径穿越防护。校验只判断文件有效性，不参与书籍重复判断。
 */
object EpubValidator {
    data class Result(val valid: Boolean, val reason: String? = null) {
        fun requireValid() {
            if (!valid) throw IllegalArgumentException(reason ?: "EPUB 校验失败")
        }
    }

    private const val MAX_ENTRIES = 5_000
    private const val MAX_UNCOMPRESSED_TOTAL = 300L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_ENTRY = 100L * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 200

    fun validate(file: File): Result {
        if (!file.isFile) return Result(false, "下载文件不存在")
        if (file.length() !in 1..(1024L * 1024 * 1024)) return Result(false, "文件大小无效")
        val magic = ByteArray(4)
        val read = file.inputStream().use { it.read(magic) }
        if (read < 4 || magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte()) {
            return Result(false, "下载内容不是 EPUB（可能是网页错误页）")
        }
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var totalUncompressed = 0L
                var entryCount = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (++entryCount > MAX_ENTRIES) return Result(false, "EPUB 条目过多")
                    val name = entry.name
                    if (name.startsWith("/") || name.contains("\\") ||
                        name.split('/').any { it == ".." }
                    ) return Result(false, "EPUB 含非法路径：${name.take(80)}")
                    if (entry.size > MAX_UNCOMPRESSED_ENTRY) return Result(false, "EPUB 单文件解压体积过大")
                    if (entry.compressedSize > 0 && entry.size > 1024 * 1024 &&
                        entry.size / entry.compressedSize > MAX_COMPRESSION_RATIO
                    ) return Result(false, "EPUB 压缩比异常，疑似压缩炸弹")
                    totalUncompressed += entry.size
                    if (totalUncompressed > MAX_UNCOMPRESSED_TOTAL) return Result(false, "EPUB 解压总量过大")
                }

                val mimetype = zip.getEntry("mimetype")
                    ?: return Result(false, "EPUB 缺少 mimetype")
                if (mimetype.isDirectory || mimetype.size > 128) return Result(false, "mimetype 无效")
                val mimetypeText = zip.getInputStream(mimetype).use { it.readBytes().toString(Charsets.US_ASCII) }.trim()
                if (mimetypeText != "application/epub+zip") return Result(false, "mimetype 内容无效")

                val container = zip.getEntry("META-INF/container.xml")
                    ?: return Result(false, "EPUB 缺少容器清单")
                if (container.isDirectory || container.size !in 1..65_536) return Result(false, "容器清单无效")
                val containerText = zip.getInputStream(container).use { it.readBytes().toString(Charsets.UTF_8) }
                val opfPath = Regex("<rootfile[^>]*full-path=[\"']([^\"']+)[\"']")
                    .find(containerText)?.groupValues?.get(1)
                    ?: return Result(false, "容器清单缺少 OPF 路径")
                if (opfPath.startsWith("/") || opfPath.split('/').any { it == ".." }) {
                    return Result(false, "OPF 路径非法")
                }
                val opf = zip.getEntry(opfPath) ?: return Result(false, "EPUB 缺少 OPF 文件")
                if (opf.isDirectory || opf.size <= 0L) return Result(false, "OPF 文件无效")
                Result(true)
            }
        } catch (_: ZipException) {
            Result(false, "EPUB 压缩结构损坏")
        } catch (e: Exception) {
            Result(false, "EPUB 校验失败：${e.message ?: "未知错误"}")
        }
    }
}
