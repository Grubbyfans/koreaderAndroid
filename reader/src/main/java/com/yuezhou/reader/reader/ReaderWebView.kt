package com.yuezhou.reader.reader

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewAssetLoader.InternalStoragePathHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

/**
 * 阅读器 WebView 封装：
 * - 通过 WebViewAssetLoader 加载 assets/reader 下的 foliate-js 宿主页面与脚本；
 * - 通过内部存储 handler 提供 EPUB 文件（复制到私有目录后按 URL 访问）；
 * - 注入 [ReaderBridge] 桥接对象，接收 JS 事件。
 *
 * 采用受控 HTTPS 本地来源（https://appassets.androidplatform.net/...），
 * 满足方案 §6.2 的安全要求：不开放任意文件访问、不关闭 TLS 校验。
 */
class ReaderWebView(context: Context) : WebView(context) {

    @Volatile private var externalSource: Uri? = null

    /** 资产加载器：负责把 assets/reader 目录映射为受控 HTTPS URL。 */
    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", AssetsPathHandler(context))
        // EPUB 私有目录：app 内部 files/epub/ 下的文件
        .addPathHandler("/epub/", InternalStoragePathHandler(context, File(context.filesDir, "epub")))
        .addPathHandler("/fonts/") { path ->
            val file = File(context.filesDir, "${ReaderFontStore.DIRECTORY}/$path")
            if (!ReaderFontStore.isImportedFont(path) || !file.isFile) {
                WebResourceResponse("text/plain", "UTF-8", 404, "Font unavailable", emptyMap(), null)
            } else {
                val mimeType = if (path.endsWith(".otf")) "font/otf" else "font/ttf"
                try {
                    WebResourceResponse(mimeType, null, 200, "OK", emptyMap(), file.inputStream())
                } catch (_: java.io.IOException) {
                    WebResourceResponse("text/plain", "UTF-8", 404, "Font unavailable", emptyMap(), null)
                }
            }
        }
        .addPathHandler("/selected/") { path ->
            val uri = externalSource
            val stream = if (path == "book.epub" && uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)
                } catch (_: java.io.IOException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            } else null
            if (stream == null) {
                WebResourceResponse("text/plain", "UTF-8", 404, "EPUB unavailable", emptyMap(), null)
            } else {
                WebResourceResponse("application/epub+zip", null, 200, "OK", mapOf("Cache-Control" to "no-store"), stream)
            }
        }
        .build()

    /** 桥接事件监听器。 */
    var eventListener: ((ReaderEvent) -> Unit)? = null

    /** 正文单击回调（用于呼出/收起阅读面板），按点击区域传动作。 */
    var onTap: (() -> Unit)? = null

    /** 翻页点击回调（左右 1/3 区域单击时触发，参数为翻页方向）。 */
    var onPageTap: ((Int) -> Unit)? = null // +1 下一页 / -1 上一页

    /** 左侧边缘滑动回调（参数为本次垂直位移增量 px，正=下滑/增亮，负=上滑/减暗）。 */
    var onBrightnessScroll: ((Float) -> Unit)? = null

    /** 上次滑动回调时的触摸点 Y 坐标，用于计算相邻两次回调的位移增量。 */
    private var lastScrollTouchY = Float.NaN

    /** 当前手势是否已识别为「左侧亮度滑动」。激活期间吞掉所有触摸事件，阻止左右/纵向位移干扰。 */
    private var brightnessGestureActive = false

    /**
     * 滑动方向锁定：
     * - null 表示尚未确定主导方向；
     * - true 表示已锁定为「亮度滑动」（垂直为主）；
     * - false 表示已锁定为「横向滑动」（交还 WebView，如左右翻页）。
     * 一旦锁定，同一手势内不再切换，避免斜向滑动在两种模式间来回抖动。
     */
    private var scrollAxisLocked: Boolean? = null

    /** 累计的横向/纵向位移，用于判定主导方向（超过阈值才锁定）。 */
    private var accumX = 0f
    private var accumY = 0f

    /** 当前是否已加载宿主页面。 */
    var isPageLoaded = false
        private set

    /** 是否为上下滚动模式（滚动模式下左右点击不翻页）。 */
    var verticalMode = false

    init {
        // debug 构建开启 WebView 远程调试，便于用 Chrome chrome://inspect 排查白屏
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // Use system tap thresholds and leave drag, pinch and selection events with WebView.
        val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                lastScrollTouchY = Float.NaN
                brightnessGestureActive = false
                scrollAxisLocked = null
                accumX = 0f
                accumY = 0f
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (width <= 0) return false
                val position = event.x / width
                post {
                    when {
                        !verticalMode && position < 1f / 3f -> onPageTap?.invoke(-1)
                        !verticalMode && position > 2f / 3f -> onPageTap?.invoke(1)
                        else -> onTap?.invoke()
                    }
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (e1 == null || width <= 0) {
                    lastScrollTouchY = Float.NaN
                    return false
                }
                // 仅左侧边缘区域（约左 1/4）参与亮度滑动判定；右侧区域一律交还 WebView
                val inLeftZone = e1.x < width * 0.25f

                // 累积位移，用于方向锁定判定（distanceX/distanceY 是相对手势起点的累计值）
                accumX += distanceX
                accumY += distanceY

                // 未锁定时：累计位移超过阈值再判定主导方向，避免轻触/抖动误判
                if (scrollAxisLocked == null) {
                    val absX = kotlin.math.abs(accumX)
                    val absY = kotlin.math.abs(accumY)
                    if (absX < TOUCH_SLOP && absY < TOUCH_SLOP) {
                        return false
                    }
                    // 主导方向：垂直明显大于水平（约 1.5 倍）且在左区 → 亮度滑动；否则视为横向/普通滚动
                    scrollAxisLocked = inLeftZone && absY > absX * 1.5f
                }

                if (scrollAxisLocked == true) {
                    // 锁定为亮度滑动：用相邻两次回调的绝对 Y 坐标差值作为增量（正=上滑增亮）
                    val y = e2.y
                    if (!lastScrollTouchY.isNaN()) {
                        val delta = lastScrollTouchY - y
                        if (delta != 0f) onBrightnessScroll?.invoke(delta)
                    }
                    lastScrollTouchY = y
                    brightnessGestureActive = true
                    return true
                }
                // 横向主导：交还 WebView 处理（左右翻页等）
                return false
            }
        })
        setOnTouchListener { _, event ->
            // 手势结束时清空方向锁定与亮度激活状态，避免状态残留到下一次手势
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    brightnessGestureActive = false
                    scrollAxisLocked = null
                    accumX = 0f
                    accumY = 0f
                    lastScrollTouchY = Float.NaN
                }
            }
            taps.onTouchEvent(event)
            // 亮度滑动激活期间吞掉事件，阻止 WebView 接收横向/纵向位移（左右滑动不干扰调亮度）
            brightnessGestureActive
        }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 允许访问受控的 https 本地来源
            allowFileAccess = false
            allowContentAccess = false
            // 允许混合内容（本地 https 来源内部 fetch）
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        // 吞掉长按事件，避免亮度/翻页手势过程中误触 WebView 文本选择
        setOnLongClickListener { true }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                val resp = assetLoader.shouldInterceptRequest(request.url)
                if (resp == null) {
                    Log.w(TAG, "拦截失败(返回null): ${request.url} method=${request.method}")
                }
                return resp
            }
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    Log.e(TAG, "主框架加载错误: ${request.url} code=${error.errorCode} desc=${error.description}")
                }
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                // 桥接对象已在 init 中注入（loadUrl 之前），此处不再重复注入
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = "[JS:${consoleMessage.messageLevel()}] ${consoleMessage.message()} (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                // 用 W 级别确保 logcat 默认可见，方便排查白屏
                Log.w(TAG, msg)
                return true
            }
        }
        // 关键：必须在 loadUrl 之前注入桥接对象，否则 ES module 顶层代码执行时
        // window.ReaderBridge 尚不可用，导致所有事件回传静默丢失（bookOpened/relocate/toc 等）。
        addJavascriptInterface(
            JsBridge { type, data -> post { dispatchEvent(type, data) } },
            "ReaderBridge",
        )
        // 加载宿主页面
        loadUrl("https://appassets.androidplatform.net/assets/reader/index.html")
    }

    companion object {
        private const val TAG = "ReaderWebView"

        /** 方向锁定判定阈值（px）：累计位移超过此值才判定主导方向，避免轻触/抖动误判。 */
        private const val TOUCH_SLOP = 24f
    }

    /**
     * 通过受控 URL 加载私有 EPUB 或用户授权的当前书籍。
     * restoreCfi 非空时打开后跳转到该位置（恢复阅读进度）。
     */
    fun openEpub(epubFileName: String, restoreCfi: String? = null, source: Uri? = null) {
        externalSource = source
        val path = if (source == null) "epub/${Uri.encode(epubFileName)}" else "selected/book.epub"
        val url = "https://appassets.androidplatform.net/$path"
        val settings = readerSettings
        whenReaderReady {
            val restore = restoreCfi ?: ""
            evaluateJavascript(
                "window.__reader.openBook(${jsonQuote(url)}, ${jsonQuote(restore)}, $settings).then(r => {});",
                null,
            )
        }
    }

    /** 跳转到指定 CFI。 */
    fun goToCfi(cfi: String) {
        whenReaderReady {
            evaluateJavascript(
                "window.__reader.goToCfi(${jsonQuote(cfi)}).then(r => {});",
                null,
            )
        }
    }

    fun verifyJump(requestId: String, cfi: String, restoreCfi: String) = whenReaderReady {
        evaluateJavascript(
            "window.__reader.verifyJump(${jsonQuote(requestId)}, ${jsonQuote(cfi)}, ${jsonQuote(restoreCfi)})",
            null,
        )
    }

    fun nextPage() = whenReaderReady { evaluateJavascript("window.__reader.next();", null) }
    fun prevPage() = whenReaderReady { evaluateJavascript("window.__reader.prev();", null) }

    /** 请求目录树（结果通过 TocLoaded 事件回传）。 */
    fun requestToc() = whenReaderReady {
        evaluateJavascript("window.__reader.getTableOfContents();", null)
    }

    /** 请求当前章节正文文本（结果通过 ChapterText 事件回传，供朗读分句）。
     *  startCfi 非空时，只返回该位置及其之后的文本（从当前阅读位置开始朗读）。 */
    fun requestChapterText(startCfi: String? = null) = whenReaderReady {
        val cfiArg = if (startCfi.isNullOrBlank()) "null" else jsonQuote(startCfi)
        evaluateJavascript("window.__reader.getCurrentChapterText($cfiArg);", null)
    }

    /** 朗读高亮：定位到「起止文本节点 CFI + 节点内字符偏移」形成的范围并高亮整句。 */
    fun highlight(startCfi: String, startOffset: Int, endCfi: String?, endOffset: Int) = whenReaderReady {
        evaluateJavascript(
            "window.__reader.highlightRange(" +
                "${jsonQuote(startCfi)}, $startOffset, ${jsonQuote(endCfi ?: "")}, $endOffset)" +
                ".then(r => {});",
            null,
        )
    }

    fun clearSpeechHighlight() = whenReaderReady {
        evaluateJavascript("window.__reader.clearSpeechHighlight();", null)
    }

    /** 跳转到目录项（href 指向章节）。 */
    fun goToToc(href: String) = whenReaderReady {
        evaluateJavascript("window.__reader.goToToc(${jsonQuote(href)}).then(r => {});", null)
    }

    private val pendingConversions = mutableMapOf<String, CompletableDeferred<String?>>()

    suspend fun cfiToXPointer(cfi: String): String? = convertPosition("cfiToXPointer", cfi)

    suspend fun xPointerToCfi(xpointer: String): String? = convertPosition("xPointerToCfi", xpointer)

    private suspend fun convertPosition(method: String, position: String): String? {
        val requestId = UUID.randomUUID().toString()
        val result = CompletableDeferred<String?>()
        pendingConversions[requestId] = result
        return try {
            whenReaderReady {
                if (requestId in pendingConversions) {
                    evaluateJavascript(
                        "window.__reader.$method(${jsonQuote(requestId)}, ${jsonQuote(position)})",
                        null,
                    )
                }
            }
            withTimeoutOrNull(5_000) { result.await() }
        } finally {
            pendingConversions.remove(requestId)
        }
    }

    /** 跳到全书百分比进度（0..1）。 */
    fun goToFraction(frac: Float) = whenReaderReady {
        evaluateJavascript("window.__reader.goToFraction($frac).then(r => {});", null)
    }

    /** 应用排版设置（字号/行距/边距/主题/翻页模式）。 */
    private var readerSettings = "{}"

    fun applySettings(
        fontSize: Int,
        lineHeight: Double,
        pageMargin: Int,
        theme: String,
        vertical: Boolean,
        fontId: String,
        fontWeight: Int,
        pageAnimation: String,
    ) {
        val settings = buildString {
            append("{fontSize:$fontSize,")
            append("lineHeight:$lineHeight,")
            append("pageMargin:$pageMargin,")
            append("theme:${jsonQuote(theme)},")
            append("vertical:$vertical,")
            append("fontId:${jsonQuote(fontId)},")
            append("fontWeight:$fontWeight,")
            append("pageAnimation:${jsonQuote(pageAnimation)}}")
        }
        if (readerSettings == settings) return
        readerSettings = settings
        whenReaderReady {
            if (readerSettings == settings) {
                evaluateJavascript("window.__reader.applySettings($settings)", null)
            }
        }
    }

    /**
     * bridge.js 是 ES module，onPageFinished 时可能尚未执行完，window.__reader 未就绪。
     * 这里轮询 window.__readerReady，就绪后再执行动作；最多尝试 200 次（约 10 秒）后放弃，
     * 覆盖冷启动下 WebView + ES module 的加载耗时。
     */
    private fun whenReaderReady(remaining: Int = 200, action: () -> Unit) {
        if (remaining <= 0) {
            Log.e(TAG, "等待 reader 就绪超时，放弃执行动作")
            eventListener?.invoke(ReaderEvent.Error("阅读器初始化超时"))
            return
        }
        evaluateJavascript(
            "window.__readerReady === true",
        ) { ready ->
            if (ready == "true") {
                action()
            } else {
                postDelayed({ whenReaderReady(remaining - 1, action) }, 50)
            }
        }
    }

    /** JS 侧桥接对象：由 JS `window.ReaderBridge.onReaderEvent(type, data)` 调用。 */
    class JsBridge(private val onEvent: (String, String) -> Unit) {
        @JavascriptInterface
        fun onReaderEvent(type: String, data: String) {
            onEvent(type, data)
        }
    }

    private fun dispatchEvent(type: String, data: String) {
        if (type == "positionConverted") {
            val obj = parseJson(data)
            val requestId = obj["requestId"] as? String ?: return
            pendingConversions[requestId]?.complete(obj["value"] as? String)
            return
        }
        val event: ReaderEvent = when (type) {
            "bookOpened" -> {
                val obj = parseJson(data)
                ReaderEvent.BookOpened(
                    sections = (obj["sections"] as? Number)?.toInt() ?: 0,
                    title = obj["title"] as? String ?: "",
                )
            }
            "jumpVerified" -> {
                val obj = parseJson(data)
                ReaderEvent.JumpVerified(
                    requestId = obj["requestId"] as? String ?: "",
                    verified = obj["verified"] == true,
                    fraction = (obj["fraction"] as? Number)?.toDouble(),
                )
            }
            "relocate" -> {
                val obj = parseJson(data)
                ReaderEvent.Relocated(
                    cfi = obj["cfi"] as? String,
                    index = (obj["index"] as? Number)?.toInt(),
                    fraction = (obj["fraction"] as? Number)?.toFloat(),
                    tocId = (obj["tocId"] as? Number)?.toInt(),
                    tocHref = obj["tocHref"] as? String,
                )
            }
            "toc" -> {
                val obj = parseJson(data)
                ReaderEvent.TocLoaded(parseTocItems(obj["items"]))
            }
            "chapterText" -> {
                val obj = parseJson(data)
                ReaderEvent.ChapterText(
                    parseChapterText(obj["items"]),
                    (obj["index"] as? Number)?.toInt(),
                    parseChapterStart(obj["startPosition"]),
                )
            }
            "error" -> {
                val obj = parseJson(data)
                ReaderEvent.Error(obj["message"] as? String ?: "未知错误")
            }
            else -> ReaderEvent.Error("未知事件: $type")
        }
        eventListener?.invoke(event)
    }

    private fun parseJson(data: String): Map<String, Any?> {
        return try {
            @Suppress("UNCHECKED_CAST")
            org.json.JSONObject(data).let { jo ->
                val map = HashMap<String, Any?>()
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = jo.get(k)
                }
                map
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** 解析嵌套目录项 JSON（label/href/subitems）。 */
    private fun parseTocItems(items: Any?): List<TocItem> {
        if (items !is org.json.JSONArray) return emptyList()
        val result = ArrayList<TocItem>()
        for (i in 0 until items.length()) {
            val node = items.optJSONObject(i) ?: continue
            result.add(
                TocItem(
                    label = node.optString("label"),
                    href = node.optString("href"),
                    subitems = parseTocItems(node.opt("subitems")),
                    id = (node.opt("id") as? Number)?.toInt(),
                ),
            )
        }
        return result
    }

    private fun jsonQuote(s: String): String =
        // 用 org.json 的 quote 正确处理引号、反斜杠、控制字符与 U+2028/U+2029，
        // 避免手写转义遗漏导致注入到 JS 字符串字面量时出错。
        org.json.JSONObject.quote(s)

    /** 解析朗读起点 {startCfi, nodeIndex, charOffsetInNode}；缺失或非法返回 null。 */
    private fun parseChapterStart(value: Any?): ChapterTextStart? {
        val obj = value as? org.json.JSONObject ?: return null
        val nodeIndex = (obj.opt("nodeIndex") as? Number)?.toInt()?.takeIf { it >= 0 }
        if (nodeIndex == null) return null
        return ChapterTextStart(
            startCfi = obj.optString("startCfi").takeIf { it.isNotBlank() },
            nodeIndex = nodeIndex,
            charOffsetInNode = (obj.opt("charOffsetInNode") as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
        )
    }

    /** 解析章节文本节点列表 [{cfi, text}]。 */
    private fun parseChapterText(items: Any?): List<ChapterTextNode> {
        if (items !is org.json.JSONArray) return emptyList()
        val result = ArrayList<ChapterTextNode>()
        for (i in 0 until items.length()) {
            val node = items.optJSONObject(i) ?: continue
            val cfi = node.optString("cfi")
            val text = node.optString("text")
            if (cfi.isBlank() || text.isBlank()) continue
            result.add(ChapterTextNode(
                cfi = cfi,
                text = text,
                paragraphId = if (node.has("paragraphId")) node.optInt("paragraphId") else null,
            ))
        }
        return result
    }
}
