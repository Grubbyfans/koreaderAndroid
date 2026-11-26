package com.yuezhou.reader.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * 从 EPUB 中提取封面，输出符合方案 §4.4 的 PNG 缩略图（≤240×320、≤32 KiB）。
 *
 * 算法对齐 BaiduPanSync 的 book_cover.lua：
 * 1. 读取 META-INF/container.xml 定位 OPF；
 * 2. 定位封面图片（manifest id=cover / 常见命名回退）；
 * 3. 按宽度 {240, 180, 120, 80} 逐级缩放，保持 4:3 比例上限；
 * 4. 转 8-bit 灰度（灰度 PNG 体积远小于彩色，这是压进 32 KiB 的关键）；
 * 5. 每级写出 PNG，≤32 KiB 且尺寸合法即返回，否则继续降级。
 *
 * 全程不抛异常：提取失败返回 null，由上层决定是否跳过封面（显示书名占位）。
 */
object CoverExtractor {

    private const val MAX_OUTPUT_BYTES = 32 * 1024
    private const val MAX_WIDTH = 240
    private const val MAX_HEIGHT = 320

    /** 逐级尝试的宽度边界（对齐基线 {240,180,120,80}）。 */
    private val WIDTH_BOUNDS = intArrayOf(240, 180, 120, 80)

    /** 从 EPUB 文件提取封面 PNG（灰度，≤32 KiB）；失败返回 null。 */
    fun extract(epubFile: File): ByteArray? {
        return try {
            ZipFile(epubFile).use { zip ->
                val opfPath = locateOpf(zip) ?: return null
                val coverHref = locateCover(zip, opfPath) ?: return null
                val entry = zip.getEntry(coverHref) ?: return null
                zip.getInputStream(entry).use { input ->
                    val bytes = input.readBytes()
                    decodeAndScale(bytes)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 从 EPUB 元数据提取书名（OPF 的 dc:title）。失败返回 null。 */
    fun extractTitle(epubFile: File): String? {
        return try {
            ZipFile(epubFile).use { zip ->
                val opfPath = locateOpf(zip) ?: return null
                val opfEntry = zip.getEntry(opfPath) ?: return null
                val opfText = zip.getInputStream(opfEntry).use { it.readBytes().toString(Charsets.UTF_8) }
                // 优先 <dc:title>...</dc:title>，其次 title 属性形式
                val title = Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>", RegexOption.IGNORE_CASE)
                    .find(opfText)?.groupValues?.get(1)
                    ?.replace(Regex("<[^>]+>"), "")
                    ?.trim()
                    ?.replace(Regex("\\s+"), " ")
                title?.takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            null
        }
    }

    internal fun validatePackage(epubFile: File) {
        ZipFile(epubFile).use { zip ->
            val container = zip.getEntry("META-INF/container.xml")
            require(container != null && container.size in 1..65536) { "EPUB 缺少有效的容器清单" }
            val opf = locateOpf(zip)?.let(zip::getEntry)
            require(opf != null && !opf.isDirectory && opf.size > 0) { "EPUB 缺少有效的书籍清单" }
        }
    }

    internal fun extractAuthors(epubFile: File): List<String>? {
        return try {
            ZipFile(epubFile).use { zip ->
                val container = zip.getEntry("META-INF/container.xml")
                if (container == null || container.size !in 1..65536) return null
                val opf = locateOpf(zip)?.let(zip::getEntry) ?: return null
                if (opf.size !in 1..(4L * 1024 * 1024)) return null

                val bytes = ByteArray(opf.size.toInt())
                DataInputStream(zip.getInputStream(opf)).use { it.readFully(bytes) }
                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    setInput(bytes.inputStream(), null)
                }
                buildList {
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "creator" &&
                            parser.namespace == "http://purl.org/dc/elements/1.1/"
                        ) {
                            parser.nextText().trim().takeIf { it.isNotEmpty() }?.let(::add)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 定位 OPF 路径：读 container.xml 的 rootfile full-path。 */
    private fun locateOpf(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        return zip.getInputStream(container).use { input ->
            val text = input.readBytes().toString(Charsets.UTF_8)
            Regex("<rootfile[^>]*full-path=[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
        }
    }

    /**
     * 在 OPF 里定位封面图片 href。返回相对 EPUB 根目录的路径。
     * 优先 manifest id=cover，其次 href 文件名含 cover 的回退。
     */
    private fun locateCover(zip: ZipFile, opfPath: String): String? {
        val opfEntry = zip.getEntry(opfPath) ?: return null
        val opfText = zip.getInputStream(opfEntry).use { it.readBytes().toString(Charsets.UTF_8) }

        val baseDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }

        // 1) manifest 里 id 为 cover 的 item
        val manifest = Regex("<manifest[\\s\\S]*?</manifest>").find(opfText)?.value ?: opfText
        // 逐个 <item> 解析，分别提取 id 与 href，不依赖属性书写顺序（calibre 常把 href 写在 id 之前）。
        val items = Regex("<item\\b[^>]*>").findAll(manifest).mapNotNull { tag ->
            val id = Regex("\\bid\\s*=\\s*[\"']([^\"']+)[\"']").find(tag.value)?.groupValues?.get(1)
            val href = Regex("\\bhref\\s*=\\s*[\"']([^\"']+)[\"']").find(tag.value)?.groupValues?.get(1)
            if (id != null && href != null) id to href else null
        }.toList()

        // 找 id 恰为 cover 的（忽略大小写）
        val coverItem = items.firstOrNull { (id, _) -> id.equals("cover", ignoreCase = true) }
        if (coverItem != null) return resolve(baseDir, coverItem.second)

        // 2) 回退：href 文件名含 cover 的图片
        val coverHrefItem = items.firstOrNull { (_, href) ->
            val name = href.substringAfterLast('/').lowercase()
            name.startsWith("cover") && name.substringAfterLast('.').let { ext ->
                ext in setOf("jpg", "jpeg", "png", "gif", "webp")
            }
        }
        if (coverHrefItem != null) return resolve(baseDir, coverHrefItem.second)

        return null
    }

    private fun resolve(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        return if (clean.startsWith("/")) clean.removePrefix("/") else baseDir + clean
    }

    /**
     * 解码原图，逐级缩放，输出 ≤32 KiB 的 PNG，或 null。
     * 优先彩色（用户期望），每级尺寸先试彩色 PNG，超 32 KiB 则回退灰度；
     * 仍超则继续降尺寸（{240,180,120,80}，保持 4:3 比例）。
     */
    private fun decodeAndScale(bytes: ByteArray): ByteArray? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val (w, h) = opts.outWidth to opts.outHeight
        if (w <= 0 || h <= 0) return null

        // 先按需采样解码，避免超大原图占用内存
        var sample = 1
        while (w / sample > 480 || h / sample > 640) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null

        try {
            for (bound in WIDTH_BOUNDS) {
                // 保持 4:3 比例：宽度 <= bound，高度 <= bound*4/3
                val factor = minOf(
                    bound.toFloat() / original.width,
                    (bound * 4f / 3f) / original.height,
                    1f,
                )
                if (factor <= 0f) continue
                val tw = maxOf(1, (original.width * factor).toInt())
                val th = maxOf(1, (original.height * factor).toInt())
                val scaled = if (tw == original.width && th == original.height) {
                    original
                } else {
                    Bitmap.createScaledBitmap(original, tw, th, true)
                }

                // 1) 先试彩色
                val colorPng = scaled.toPng()
                if (colorPng != null && colorPng.size <= MAX_OUTPUT_BYTES && tw <= MAX_WIDTH && th <= MAX_HEIGHT) {
                    if (scaled !== original) scaled.recycle()
                    return colorPng
                }
                // 2) 彩色超限则回退灰度（灰度 PNG 体积显著更小）
                val gray = toGray(scaled)
                if (scaled !== original) scaled.recycle()
                val grayPng = gray.toPng()
                gray.recycle()
                if (grayPng != null && grayPng.size <= MAX_OUTPUT_BYTES && tw <= MAX_WIDTH && th <= MAX_HEIGHT) {
                    return grayPng
                }
                // 继续降级到更小尺寸
            }
            return null
        } finally {
            if (original.isRecycled.not()) original.recycle()
        }
    }

    /** 转 8-bit 灰度 Bitmap（灰度 PNG 体积远小于彩色，作为超限兜底）。 */
    private fun toGray(src: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint()
        val cm = ColorMatrix().apply { setSaturation(0f) }
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return gray
    }

    /** 转 PNG 字节；失败返回 null。 */
    private fun Bitmap.toPng(): ByteArray? {
        return try {
            val stream = ByteArrayOutputStream()
            compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            null
        }
    }
}
