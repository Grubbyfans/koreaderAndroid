package com.yuezhou.reader.reader

import android.content.Context
import android.util.Log
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
import java.io.File

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

    /** 资产加载器：负责把 assets/reader 目录映射为受控 HTTPS URL。 */
    private val assetLoader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", AssetsPathHandler(context))
        // EPUB 私有目录：app 内部 files/epub/ 下的文件
        .addPathHandler("/epub/", InternalStoragePathHandler(context, File(context.filesDir, "epub")))
        .build()

    /** 桥接事件监听器。 */
    var eventListener: ((ReaderEvent) -> Unit)? = null

    /** 正文单击回调（用于呼出/收起阅读面板），按点击区域传动作。 */
    var onTap: (() -> Unit)? = null

    /** 翻页点击回调（左右 1/3 区域单击时触发，参数为翻页方向）。 */
    var onPageTap: ((Int) -> Unit)? = null // +1 下一页 / -1 上一页

    /** 当前是否已加载宿主页面。 */
    var isPageLoaded = false
        private set

    /** 是否为上下滚动模式（滚动模式下左右点击不翻页）。 */
    var verticalMode = false

    // 单击判定：记录按下时的位置与时间，抬起时若位移/时长在阈值内则视为单击
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private val tapSlop = 12 // px，超过视为滑动
    private val tapTimeout = 300L // ms，超过视为长按

    init {
        // debug 构建开启 WebView 远程调试，便于用 Chrome chrome://inspect 排查白屏
        val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        // 关键修复：部分设备（如三星 + Android 14/15）上 WebView 的独立 Surface 会浮在
        // Compose 内容之上，把「选择 EPUB」按钮等控件盖住，导致进入阅读器后界面白屏。
        // 用软件渲染层强制 WebView 与 Compose 共用同一渲染层，避免遮挡。
        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        // 单击检测：WebView 会消费触摸事件，Compose 的 clickable 收不到。
        // 这里手动判定「单击」，并按屏幕横向三分区域分发：
        //   左 1/3 → 上一页，右 1/3 → 下一页（左右翻页模式），中间 1/3 → 呼出/收起面板。
        // 上下滚动模式下左右区域不再翻页，仅中间点击呼出面板；滑动始终交给 WebView 处理。
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    downTime = event.eventTime
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val dx = kotlin.math.abs(event.x - downX)
                    val dy = kotlin.math.abs(event.y - downY)
                    val dt = event.eventTime - downTime
                    if (dx <= tapSlop && dy <= tapSlop && dt <= tapTimeout) {
                        val w = width.toFloat()
                        if (w > 0f) {
                            val rel = event.x / w
                            if (!verticalMode && rel < 0.33f) {
                                onPageTap?.invoke(-1)
                            } else if (!verticalMode && rel > 0.67f) {
                                onPageTap?.invoke(+1)
                            } else {
                                onTap?.invoke()
                            }
                        }
                    }
                }
            }
            false // 返回 false，让 WebView 继续处理滑动翻页/滚动
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
    }

    /**
     * 打开 EPUB。epubFile 须为已复制到私有目录 files/epub/ 下的文件；
     * 这里用其文件名构造受控 URL 传给 JS 侧 view.open()。
     * restoreCfi 非空时打开后跳转到该位置（恢复阅读进度）。
     */
    fun openEpub(epubFileName: String, restoreCfi: String? = null) {
        val url = "https://appassets.androidplatform.net/epub/$epubFileName"
        whenReaderReady {
            val restore = restoreCfi ?: ""
            evaluateJavascript(
                "window.__reader.openBook(${jsonQuote(url)}, ${jsonQuote(restore)}).then(r => {});",
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

    /** 朗读高亮：定位到文本节点 CFI + 节点内字符偏移处并选中。 */
    fun highlight(cfi: String, offset: Int) = whenReaderReady {
        evaluateJavascript("window.__reader.highlightRange(${jsonQuote(cfi)}, $offset).then(r => {});", null)
    }

    /** 跳转到目录项（href 指向章节）。 */
    fun goToToc(href: String) = whenReaderReady {
        evaluateJavascript("window.__reader.goToToc(${jsonQuote(href)}).then(r => {});", null)
    }

    /**
     * CFI → XPointer：把本地阅读位置（CFI）转成 KOReader/CREngine 的 XPointer。
     * 结果异步回传（回调在主线程），转换失败回调 null。
     */
    fun cfiToXPointer(cfi: String, onResult: (String?) -> Unit) = whenReaderReady {
        evaluateJavascript("window.__reader.cfiToXPointer(${jsonQuote(cfi)})") { result ->
            // 结果形如 "xpointer-string" 或 "null"（JSON 字符串）
            onResult(parseJsString(result))
        }
    }

    /**
     * XPointer → CFI：把云端（KOReader）的 XPointer 进度转成本地可定位的 CFI。
     * 结果异步回传，转换失败回调 null。
     */
    fun xPointerToCfi(xpointer: String, onResult: (String?) -> Unit) = whenReaderReady {
        evaluateJavascript("window.__reader.xPointerToCfi(${jsonQuote(xpointer)})") { result ->
            onResult(parseJsString(result))
        }
    }

    /** 解析 evaluateJavascript 返回的 JSON 字符串值（含引号包裹与 null）。 */
    private fun parseJsString(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim()
        if (s == "null" || s == "undefined" || s.isEmpty()) return null
        return try {
            org.json.JSONTokener(s).nextValue().let { if (it === org.json.JSONObject.NULL) null else it.toString() }
        } catch (e: Exception) {
            // 兜底：去掉首尾引号
            s.removePrefix("\"").removeSuffix("\"")
        }
    }

    /** 跳到全书百分比进度（0..1）。 */
    fun goToFraction(frac: Float) = whenReaderReady {
        evaluateJavascript("window.__reader.goToFraction($frac).then(r => {});", null)
    }

    /** 应用排版设置（字号/行距/边距/主题/翻页模式）。 */
    fun applySettings(
        fontSize: Int,
        lineHeight: Double,
        pageMargin: Int,
        theme: String,
        vertical: Boolean,
    ) = whenReaderReady {
        val js = buildString {
            append("window.__reader.applySettings({")
            append("fontSize:$fontSize,")
            append("lineHeight:$lineHeight,")
            append("pageMargin:$pageMargin,")
            append("theme:${jsonQuote(theme)},")
            append("vertical:$vertical")
            append("}).then(r => {});")
        }
        evaluateJavascript(js, null)
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
                ReaderEvent.JumpVerified(obj["requestId"] as? String ?: "", obj["verified"] == true)
            }
            "relocate" -> {
                val obj = parseJson(data)
                ReaderEvent.Relocated(
                    cfi = obj["cfi"] as? String,
                    index = (obj["index"] as? Number)?.toInt(),
                    fraction = (obj["fraction"] as? Number)?.toFloat(),
                )
            }
            "toc" -> {
                val obj = parseJson(data)
                ReaderEvent.TocLoaded(parseTocItems(obj["items"]))
            }
            "chapterText" -> {
                val obj = parseJson(data)
                ReaderEvent.ChapterText(parseChapterText(obj["items"]))
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
                ),
            )
        }
        return result
    }

    private fun jsonQuote(s: String): String =
        // 用 org.json 的 quote 正确处理引号、反斜杠、控制字符与 U+2028/U+2029，
        // 避免手写转义遗漏导致注入到 JS 字符串字面量时出错。
        org.json.JSONObject.quote(s)

    /** 解析章节文本节点列表 [{cfi, text}]。 */
    private fun parseChapterText(items: Any?): List<ChapterTextNode> {
        if (items !is org.json.JSONArray) return emptyList()
        val result = ArrayList<ChapterTextNode>()
        for (i in 0 until items.length()) {
            val node = items.optJSONObject(i) ?: continue
            val cfi = node.optString("cfi")
            val text = node.optString("text")
            if (cfi.isBlank() || text.isBlank()) continue
            result.add(ChapterTextNode(cfi = cfi, text = text))
        }
        return result
    }
}
