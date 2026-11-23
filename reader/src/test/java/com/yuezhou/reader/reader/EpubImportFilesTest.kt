package com.yuezhou.reader.reader

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EpubImportFilesTest {
    @Test
    fun `清理路径字符空书名并支持混合大小写扩展名`() {
        assertEquals("天倾之后.epub", EpubImportFiles.fileName(" 天倾之后 .ePuB "))
        assertEquals("book.epub", EpubImportFiles.fileName(" .EPUB "))
        assertEquals("a_b.epub", EpubImportFiles.fileName("a/b.epub"))
    }
    @Test
    fun `相同文件重复打开保留旧正文代次`() {
        val root = Files.createTempDirectory("epub-repeat-").toFile()
        try {
            val target = File(root, "book.epub").apply { writeText("same book"); setLastModified(1_000_000L) }
            val generation = target.lastModified()
            val staging = File(root, "stage").apply { writeText("same book") }
            EpubImportFiles.install(staging, target)
            assertEquals(generation, target.lastModified())
            assertFalse(staging.exists())
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `同名不同内容原子替换且失败不破坏旧书`() {
        val root = Files.createTempDirectory("epub-replace-").toFile()
        try {
            val target = File(root, "book.epub").apply { writeText("old book") }
            assertThrows(Exception::class.java) { EpubImportFiles.install(File(root, "missing"), target) }
            assertEquals("old book", target.readText())
            val staging = File(root, "stage").apply { writeText("new book") }
            EpubImportFiles.install(staging, target)
            assertEquals("new book", target.readText())
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `拒绝伪装EPUB及缺少书籍清单的压缩包`() {
        val root = Files.createTempDirectory("epub-invalid-").toFile()
        try {
            val fake = File(root, "fake.epub").apply { writeText("not an epub") }
            assertThrows(Exception::class.java) { EpubImportFiles.validate(fake) }
            val missing = File(root, "missing.epub")
            ZipOutputStream(missing.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("<container/>".toByteArray()); zip.closeEntry()
            }
            assertThrows(Exception::class.java) { EpubImportFiles.validate(missing) }
        } finally { root.deleteRecursively() }
    }
    @Test
    fun `有效EPUB通过容器与OPF校验`() {
        val file = Files.createTempFile("epub-valid-", ".epub").toFile()
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("META-INF/container.xml"))
                zip.write("<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
                zip.write("<package/>".toByteArray()); zip.closeEntry()
            }
            EpubImportFiles.validate(file)
        } finally { file.delete() }
    }
}
