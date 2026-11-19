package com.yuezhou.reader.reader

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
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

    /** 当前是否已加载宿主页面。 */
    var isPageLoaded = false
        private set

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 允许访问受控的 https 本地来源
            allowFileAccess = false
            allowContentAccess = false
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                isPageLoaded = true
                // 页面就绪后注入桥接对象
                addJavascriptInterface(JsBridge(), "ReaderBridge")
            }
        }
        // 加载宿主页面
        loadUrl("https://appassets.androidplatform.net/assets/reader/index.html")
    }

    /**
     * 打开 EPUB。epubFile 须为已复制到私有目录 files/epub/ 下的文件；
     * 这里用其文件名构造受控 URL 传给 JS 侧 view.open()。
     */
    fun openEpub(epubFileName: String) {
        val url = "https://appassets.androidplatform.net/epub/$epubFileName"
        whenReaderReady {
            evaluateJavascript(
                "window.__reader.openBook(${jsonQuote(url)}).then(r => {});",
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

    fun nextPage() = whenReaderReady { evaluateJavascript("window.__reader.next();", null) }
    fun prevPage() = whenReaderReady { evaluateJavascript("window.__reader.prev();", null) }

    /**
     * bridge.js 是 ES module，onPageFinished 时可能尚未执行完，window.__reader 未就绪。
     * 这里轮询 window.__readerReady，就绪后再执行动作；最多尝试 60 次（约 3 秒）后放弃。
     */
    private fun whenReaderReady(remaining: Int = 60, action: () -> Unit) {
        if (remaining <= 0) return
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
    private inner class JsBridge {
        @JavascriptInterface
        fun onReaderEvent(type: String, data: String) {
            post { dispatchEvent(type, data) }
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
            "relocate" -> {
                val obj = parseJson(data)
                ReaderEvent.Relocated(
                    cfi = obj["cfi"] as? String,
                    index = (obj["index"] as? Number)?.toInt(),
                    fraction = (obj["fraction"] as? Number)?.toFloat(),
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

    private fun jsonQuote(s: String): String =
        // 用 org.json 的 quote 正确处理引号、反斜杠、控制字符与 U+2028/U+2029，
        // 避免手写转义遗漏导致注入到 JS 字符串字面量时出错。
        org.json.JSONObject.quote(s)
}
