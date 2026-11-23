package com.yuezhou.reader.reader

import com.yuezhou.reader.core.speech.ChapterTextSource
import com.yuezhou.reader.core.speech.SpeechTextPlanning
import com.yuezhou.reader.core.speech.SpeechEngineType
import com.yuezhou.reader.core.speech.SpeechPlan
import com.yuezhou.reader.core.speech.SpeechTextUnit
import com.yuezhou.reader.core.speech.SourceAnchor
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/**
 * 原生 EPUB 章节文本源：从本地 EPUB 解包并按 spine 顺序提取纯文本，
 * 产出 [SpeechTextUnit] 序列供分句器使用。
 *
 * 这是 §7.3「后台不能依赖 WebView」的正式方案——朗读前台服务可在
 * WebView 被回收/锁屏冻结后，仍独立读取 EPUB 生成朗读计划。
 *
 * 关键设计：
 *   - 从 META-INF/container.xml 定位 OPF，读 `<spine>` 的 itemref 顺序，
 *     再经 `<manifest>` 映射到实际 XHTML 文件路径；
 *   - 每个 XHTML 用 [HtmlTextExtractor] 剥离成纯文本，按段落（换行）切分，
 *     每段作为一个 [SpeechTextUnit]；
 *   - nodeId 用「章节文件序号 + 段内序号」构成稳定标识（如 `3:12`），
 *     使原生解析结果自洽，供高亮回写与进度锚点使用（不与 foliate-js CFI 混用）。
 *
 * 本类无 Android UI 依赖，可在后台线程/服务中调用。
 */
class EpubChapterTextSource(
    private val epubFile: File,
    /** 目标章节序号（0-based，对应 spine 顺序）。 */
    private val chapterIndex: Int,
) : ChapterTextSource {

    override fun extract(): List<SpeechTextUnit> = try {
        ZipFile(epubFile).use { zip ->
            val href = locateSpine(zip)?.getOrNull(chapterIndex) ?: return@use emptyList()
            extractChapter(zip, href, chapterIndex)
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        fun nextPlan(epubFile: File, after: SpeechPlan, checkActive: () -> Unit = {}): SpeechPlan? {
            ZipFile(epubFile).use { zip ->
                val spine = checkNotNull(locateSpine(zip)) { "无法读取 EPUB 章节顺序" }
                for (index in (after.chapterIndex + 1) until spine.size) {
                    checkActive()
                    val units = extractChapter(zip, spine[index], index)
                    val result = SpeechTextPlanning.plan(units,
                        if (after.novelParsing) SpeechEngineType.KOKORO else SpeechEngineType.SYSTEM_TTS, after.novelParsing)
                    if (result.segments.isNotEmpty()) {
                        return after.copy(chapterIndex = index, segments = result.segments,
                            plannerVersion = result.version, sourceUnits = units)
                    }
                }
                return null
            }
        }

        private fun extractChapter(zip: ZipFile, href: String, index: Int): List<SpeechTextUnit> {
            val entry = zip.getEntry(href) ?: return emptyList()
            if (entry.isDirectory) return emptyList()
            val html = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return splitIntoUnits(HtmlTextExtractor.extract(html), index)
        }

        /**
         * 把一章纯文本按段落（换行）切分为 [SpeechTextUnit]，
         * nodeId = `"$chapterIndex:$paraIndex"`，offset 为段内 0。
         */
        private fun splitIntoUnits(text: String, chapter: Int): List<SpeechTextUnit> {
            val units = mutableListOf<SpeechTextUnit>()
            var para = 0
            for (paragraph in text.split('\n')) {
                val cleaned = paragraph.trim()
                if (cleaned.isEmpty()) continue
                units.add(
                    SpeechTextUnit(
                        text = cleaned,
                        anchor = SourceAnchor(nodeId = "$chapter:$para", offset = 0),
                    )
                )
                para++
            }
            return units
        }

        /** 定位并解析 OPF，返回按 spine 顺序排列的 XHTML 相对路径列表。 */
        fun locateSpine(zip: ZipFile): List<String>? {
            val container = zip.getEntry("META-INF/container.xml") ?: return null
            val containerText = zip.getInputStream(container).use { it.readBytes().toString(Charsets.UTF_8) }
            // 用 jsoup XML parser 解析 container.xml，取第一个 rootfile 的 full-path。
            val opfPath = runCatching {
                val doc = Jsoup.parse(containerText, "", Parser.xmlParser())
                doc.selectFirst("rootfile")?.attr("full-path")
            }.getOrNull() ?: return null
            val opfEntry = zip.getEntry(opfPath) ?: return null
            val opfText = zip.getInputStream(opfEntry).use { it.readBytes().toString(Charsets.UTF_8) }

            val baseDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

            // 用 jsoup XML parser 解析 OPF：manifest 的 id->href 映射，spine 的 itemref 顺序。
            val doc = runCatching {
                Jsoup.parse(opfText, "", Parser.xmlParser())
            }.getOrNull() ?: return null

            val idToHref = mutableMapOf<String, String>()
            doc.select("manifest > item").forEach { item ->
                val id = item.attr("id")
                val href = item.attr("href")
                if (id.isNotEmpty() && href.isNotEmpty()) idToHref[id] = href
            }

            val hrefs = doc.select("spine > itemref").map { itemref ->
                val id = itemref.attr("idref")
                val linear = itemref.attr("linear")
                val href = idToHref[id]
                // Preserve EPUB spine indices even for entries excluded from sequential reading.
                if (href == null || linear.equals("no", ignoreCase = true)) ""
                else resolve(baseDir, href)
            }
            return if (hrefs.isEmpty()) null else hrefs
        }

        private fun resolve(baseDir: String, href: String): String {
            val clean = href.substringBefore('#').substringBefore('?')
            val decoded = runCatching { URI(clean).path }.getOrNull() ?: clean
            val path = if (decoded.startsWith("/")) decoded else "/$baseDir$decoded"
            return URI(null, null, path, null).normalize().path.removePrefix("/")
        }
    }
}
