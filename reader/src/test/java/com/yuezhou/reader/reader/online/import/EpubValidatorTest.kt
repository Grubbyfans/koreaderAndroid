package com.yuezhou.reader.reader.online.importtest

import com.yuezhou.reader.reader.online.import.EpubValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubValidatorTest {
    @TempDir
    lateinit var dir: File

    private fun zip(name: String, block: ZipOutputStream.() -> Unit): File {
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { it.apply(block) }
        return file
    }

    private fun ZipOutputStream.add(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray())
        closeEntry()
    }

    private fun validEpub(name: String = "valid.epub") = zip(name) {
        add("mimetype", "application/epub+zip")
        add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""")
        add("OEBPS/content.opf", """<package><metadata><title>T</title></metadata></package>""")
    }

    @Test
    fun `有效EPUB通过校验`() {
        val result = EpubValidator.validate(validEpub())
        assertTrue(result.valid, result.reason ?: "")
    }

    @Test
    fun `HTML错误页被拒绝`() {
        val file = File(dir, "error.html").apply { writeText("<html><body>403 Forbidden</body></html>") }
        val result = EpubValidator.validate(file)
        assertFalse(result.valid)
        assertTrue(result.reason!!.contains("不是 EPUB"))
    }

    @Test
    fun `缺少mimetype或OPF被拒绝`() {
        val noMimetype = zip("no-mimetype.epub") {
            add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="a.opf"/></rootfiles></container>""")
            add("a.opf", "content")
        }
        assertFalse(EpubValidator.validate(noMimetype).valid)

        val noOpf = zip("no-opf.epub") {
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="missing.opf"/></rootfiles></container>""")
        }
        assertFalse(EpubValidator.validate(noOpf).valid)
    }

    @Test
    fun `路径穿越条目被拒绝`() {
        val file = zip("traversal.epub") {
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="a.opf"/></rootfiles></container>""")
            add("a.opf", "content")
            add("../evil.txt", "bad")
        }
        val result = EpubValidator.validate(file)
        assertFalse(result.valid)
        assertTrue(result.reason!!.contains("非法路径"))
    }

    @Test
    fun `高压缩比疑似压缩炸弹被拒绝`() {
        val file = zip("bomb.epub") {
            add("mimetype", "application/epub+zip")
            add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="a.opf"/></rootfiles></container>""")
            add("a.opf", "content")
            putNextEntry(ZipEntry("bomb.txt"))
            write(ByteArray(2 * 1024 * 1024))
            closeEntry()
        }
        val result = EpubValidator.validate(file)
        assertFalse(result.valid)
        assertTrue(result.reason!!.contains("压缩比") or result.reason!!.contains("解压"))
    }
}
