package com.yuezhou.reader.reader

import com.yuezhou.reader.core.speech.SentencePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubChapterTextSourceTest {

    @TempDir
    lateinit var tempDir: File

    /** 构造一个最小 EPUB：两章，spine 顺序 chapter1 -> chapter2。 */
    private fun buildEpub(): File {
        val epub = File(tempDir, "sample.epub")
        ZipOutputStream(epub.outputStream()).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""",
            )
            put(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
  <manifest>
    <item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="chapter2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
    <itemref idref="c2"/>
  </spine>
</package>""",
            )
            put(
                "OEBPS/chapter1.xhtml",
                "<html><body><h1>第一章</h1><p>这是第一段。</p><p>这是第二段！</p><script>var x=1;</script></body></html>",
            )
            put(
                "OEBPS/chapter2.xhtml",
                "<html><body><p>第二章内容。</p></body></html>",
            )
        }
        return epub
    }

    @Test
    fun `按 spine 顺序提取指定章节文本`() {
        val epub = buildEpub()
        val source = EpubChapterTextSource(epub, chapterIndex = 0)
        val units = source.extract()

        assertTrue(units.isNotEmpty())
        // 第一章：标题 + 两段（script 被剔除）
        assertEquals(listOf("第一章", "这是第一段。", "这是第二段！"), units.map { it.text })
        // nodeId 用 chapterIndex:paraIndex
        assertEquals("0:0", units[0].anchor.nodeId)
        assertEquals("0:2", units[2].anchor.nodeId)
    }

    @Test
    fun `提取第二章`() {
        val epub = buildEpub()
        val source = EpubChapterTextSource(epub, chapterIndex = 1)
        val units = source.extract()
        assertEquals(listOf("第二章内容。"), units.map { it.text })
        assertEquals("1:0", units[0].anchor.nodeId)
    }

    @Test
    fun `越界章节返回空`() {
        val epub = buildEpub()
        val source = EpubChapterTextSource(epub, chapterIndex = 5)
        assertTrue(source.extract().isEmpty())
    }

    @Test
    fun `不存在的文件返回空`() {
        val source = EpubChapterTextSource(File(tempDir, "none.epub"), chapterIndex = 0)
        assertTrue(source.extract().isEmpty())
    }

    @Test
    fun `提取结果可接入 SentencePlanner 分句`() {
        val epub = buildEpub()
        val units = EpubChapterTextSource(epub, chapterIndex = 0).extract()
        val segments = SentencePlanner().plan(units)

        assertTrue(segments.isNotEmpty())
        // 每个片段都应携带锚点映射（charToSource 长度 = text.length + 1）
        for (seg in segments) {
            assertNotNull(seg.charToSource)
            assertEquals(seg.text.length + 1, seg.charToSource.size)
        }
    }
}
