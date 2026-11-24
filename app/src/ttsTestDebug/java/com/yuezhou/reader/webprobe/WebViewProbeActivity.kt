package com.yuezhou.reader.webprobe

import android.app.Activity
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 独立、临时的 Android WebView 可行性探针（仅存在于 ttsTestDebug 变体）。
 *
 * 目的：验证「标准 Android WebView 浏览器环境」能否正常访问 Z-Library 的 SSR 网页搜索，
 * 覆盖首次访问 / 关键词搜索 / 分页 / 刷新，并记录加载耗时、HTTP 错误、重定向与加载结果。
 *
 * 严格边界：
 * - 只使用 framework WebView，默认 UA，默认请求头；不注入 Cookie/Token/账号，不重放凭证。
 * - 不关闭 TLS 校验：不覆盖 WebView 的证书信任，遇到 SSL 错误只记录并 cancel。
 * - 不绕过网站访问验证：页面自身的 JavaScript 由 WebView 正常执行（等同普通浏览器），
 *   不干预 challenge 流程、不跳过校验、不伪造 challenge 结果。
 * - 不改动 main 源码：现有 App 的 API 搜索、服务器配置、下载、书籍导入逻辑均不受影响；
 *   本 Activity 通过 ADB 显式启动，未接入任何应用内导航。
 *
 * ADB 启动（测试版包名 com.yuezhou.reader.tts.test）：
 *   adb shell am start -n com.yuezhou.reader.tts.test/com.yuezhou.reader.webprobe.WebViewProbeActivity \
 *     --ez autorun true --es base_url https://zh.z-lib.gd --es query python
 *
 * 可覆盖参数：base_url / query / search_path / page_param / sample1 / sample2 / step_gap（ms）。
 * 完整日志同时写入 Logcat（tag=WebViewProbe）与 files/webview_probe.log（可用 run-as 取出）。
 */
class WebViewProbeActivity : Activity() {

    private val tag = "WebViewProbe"
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var webView: WebView
    private lateinit var addressBox: EditText
    private lateinit var status: TextView
    private lateinit var logFile: File

    private lateinit var baseUrl: String
    private var baseUrlSource = ""
    private var query = "python"
    private var searchPath = "/s/"
    private var pageParam = "page"
    private var sample1Ms = 10_000L
    private var sample2Ms = 20_000L
    private var stepGapMs = 23_000L

    private var currentLoadUrl = ""
    private var currentStep = "IDLE"
    private var stepStartAt = 0L
    private var stepIndex = 0
    private var navChain = mutableListOf<String>()
    private var httpErrors = mutableListOf<String>()
    private var redirectCount = 0
    private var pageFinishCount = 0
    private var commitVisibleAt = 0L
    private var subResourceHttpErrors = 0
    private var consoleCount = 0
    private var lastErrorKey = ""
    private var steps: List<Step> = emptyList()

    private sealed class Action {
        data class Load(val url: String) : Action()
        data object Reload : Action()
    }

    private data class Step(val name: String, val action: Action, val url: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        logFile = File(filesDir, "webview_probe.log")
        logFile.writeText("=== WebViewProbe run ${ts(System.currentTimeMillis())} ===\n")

        readParams()
        buildUi()
        logEnvironment()
        resetCookiesForAnonymous()
        buildSteps()

        if (intent.getBooleanExtra("autorun", false)) {
            handler.postDelayed({ startAutoRun() }, 900L)
        } else {
            log("IDLE 手动模式：可点按钮逐项测试，或用 --ez autorun true 自动跑完整序列。")
        }
    }

    private fun readParams() {
        val (resolved, source) = resolveBaseUrl()
        baseUrl = resolved
        baseUrlSource = source
        query = intent.getStringExtra("query")?.takeIf { it.isNotBlank() } ?: "python"
        searchPath = intent.getStringExtra("search_path")?.takeIf { it.isNotBlank() } ?: "/s/"
        pageParam = intent.getStringExtra("page_param")?.takeIf { it.isNotBlank() } ?: "page"
        sample1Ms = intent.getIntExtra("sample1", 10_000).toLong().coerceIn(2_000L, 60_000L)
        sample2Ms = intent.getIntExtra("sample2", 20_000).toLong().coerceIn(sample1Ms, 90_000L)
        stepGapMs = intent.getIntExtra("step_gap", 23_000).toLong().coerceIn(sample2Ms, 120_000L)
    }

    /** 优先 ADB 显式指定；否则只读当前 App 已配置的服务器地址；不再硬编码任何域名。 */
    private fun resolveBaseUrl(): Pair<String, String> {
        val override = intent.getStringExtra("base_url")?.trim()?.trimEnd('/')
        if (!override.isNullOrBlank() && override.startsWith("https://")) {
            return override to "intent:base_url"
        }
        val fromConfig = runCatching {
            ZLibraryConfigStore(this).load().servers.baseUrls
                .firstOrNull { it.startsWith("https://") }?.trim()?.trimEnd('/')
        }.getOrNull()
        if (!fromConfig.isNullOrBlank()) return fromConfig to "ZLibraryConfigStore(只读)"
        return "" to "NONE"
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        addressBox = EditText(this).apply {
            setSingleLine(true)
            setText("$baseUrl/")
            textSize = 12f
        }
        val urlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        urlRow.addView(addressBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        urlRow.addView(button("访问") { load(addressBox.text.toString().trim()) })
        root.addView(urlRow)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("首页") { val u = "$baseUrl/"; addressBox.setText(u); load(u) })
        bar.addView(button("搜索") { val u = searchUrl(); addressBox.setText(u); load(u) })
        bar.addView(button("下一页") { val u = withPage(currentLoadUrl.ifBlank { searchUrl() }); addressBox.setText(u); load(u) })
        bar.addView(button("刷新") { resetStepCounters("MANUAL_RELOAD"); webView.reload() })
        bar.addView(button("自动重跑") { startAutoRun() })
        bar.addView(button("清屏") { status.text = "" })
        root.addView(ScrollView(this).apply {
            addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })

        webView = WebView(this).apply {
            configureStandardBrowserSettings()
            webViewClient = ProbeWebViewClient()
            webChromeClient = ProbeWebChromeClient()
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.parseColor("#111111"))
            setBackgroundColor(Color.parseColor("#F2F2F2"))
            setTextIsSelectable(true)
            isVerticalScrollBarEnabled = true
        }
        root.addView(ScrollView(this).apply { addView(status) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))

        setContentView(root)
    }

    /** 标准 WebView 浏览器环境：只开启网页正常运行所需的 JS 与 DOM Storage，其余保持默认。 */
    private fun WebView.configureStandardBrowserSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 显示相关，让 SSR 页面按正常视口渲染；不影响网络与校验行为。
            loadWithOverviewMode = true
            useWideViewPort = true
            // 以下均保持系统默认，显式列出便于核对「未削弱安全性」：
            // mixedContentMode = MIXED_CONTENT_NEVER_ALLOW、cacheMode = LOAD_DEFAULT、
            // allowFileAccess = false、UA = WebView 默认值。
        }
    }

    private fun logEnvironment() {
        val s = webView.settings
        val pkg = WebView.getCurrentWebViewPackage()
        log("ENV device=${android.os.Build.MODEL} android=${android.os.Build.VERSION.RELEASE} sdk=${android.os.Build.VERSION.SDK_INT}")
        log("ENV webviewPkg=${pkg?.packageName} webviewVersion=${pkg?.versionName} chromiumMaj=${chromiumMajor(s.userAgentString)}")
        log("ENV ua=${s.userAgentString}")
        log("ENV js=${s.javaScriptEnabled} domStorage=${s.domStorageEnabled} cacheMode=${s.cacheMode} mixedContent=${s.mixedContentMode} allowFileAccess=${s.allowFileAccess}")
        log("ENV TLS_VERIFY=system_default; sslErrorHandler=cancel_only; no_custom_TrustManager; no_UA_override; no_cookie_injection")
        log("ENV baseUrl=$baseUrl source=$baseUrlSource query=$query searchPath=$searchPath pageParam=$pageParam")
        log("ENV timing sample1=${sample1Ms}ms sample2=${sample2Ms}ms stepGap=${stepGapMs}ms")
    }

    /** 保证「未登录状态」：只清空 WebView 自身的 CookieJar（与 App 的账号凭证存储完全隔离）。 */
    private fun resetCookiesForAnonymous() {
        if (baseUrl.isBlank()) return
        val cm = CookieManager.getInstance()
        val before = runCatching { cm.getCookie(baseUrl) }.getOrNull()
        log("COOKIE_BEFORE len=${before?.length ?: 0} present=${!before.isNullOrBlank()}")
        cm.removeAllCookies { ok -> log("COOKIE_RESET_ANONYMOUS removed=$ok (仅 WebView CookieJar，不影响 App 凭证)") }
        cm.flush()
    }

    // ---------------------------------------------------------------- 自动测试序列

    private fun buildSteps() {
        if (baseUrl.isBlank()) {
            steps = emptyList()
            log("NO_BASE_URL 未解析到可用的 https 地址；请在地址栏输入，或用 --es base_url https://... 启动。")
            return
        }
        val search = searchUrl()
        val page2 = withPage(search)
        steps = listOf(
            Step("HOME", Action.Load("$baseUrl/"), "$baseUrl/"),
            Step("SEARCH", Action.Load(search), search),
            Step("PAGE2", Action.Load(page2), page2),
            Step("RELOAD", Action.Reload, page2),
        )
        log("PLAN HOME=$baseUrl/ SEARCH=$search PAGE2=$page2 RELOAD=reload(page2)")
    }

    private fun startAutoRun() {
        handler.removeCallbacksAndMessages(null)
        if (steps.isEmpty()) buildSteps()
        if (steps.isEmpty()) return
        stepIndex = 0
        log("RUN_BEGIN steps=${steps.size} at=${ts(System.currentTimeMillis())}")
        runStep(0)
    }

    private fun runStep(index: Int) {
        if (index >= steps.size) {
            log("RUN_COMPLETE steps=${steps.size} at=${ts(System.currentTimeMillis())}；不做任何业务集成，仅输出验证结论。")
            return
        }
        val step = steps[index]
        currentStep = step.name
        stepStartAt = SystemClock.elapsedRealtime()
        resetStepCounters(step.name)
        log("STEP_BEGIN idx=$index name=${step.name} url=${step.url} t=${nowMs()}")
        when (val action = step.action) {
            is Action.Load -> { currentLoadUrl = action.url; addressBox.setText(action.url); webView.loadUrl(action.url) }
            Action.Reload -> webView.reload()
        }
        handler.postDelayed({ sample(step.name, 1) }, sample1Ms)
        handler.postDelayed({ sample(step.name, 2) }, sample2Ms)
        handler.postDelayed({ finishStep(step, index) }, stepGapMs)
    }

    private fun finishStep(step: Step, index: Int) {
        log("STEP_END name=${step.name} totalMs=${SystemClock.elapsedRealtime() - stepStartAt} " +
            "pageFinishes=$pageFinishCount commitVisibleMs=${if (commitVisibleAt > 0L) commitVisibleAt - stepStartAt else -1} " +
            "redirects=$redirectCount subResHttpErrors=$subResourceHttpErrors")
        log("STEP_CHAIN name=${step.name} nav=${navChain.joinToString(" -> ").take(900)}")
        log("STEP_HTTP_ERRORS name=${step.name} count=${httpErrors.size} list=${httpErrors.joinToString(" | ").take(700)}")
        runStep(index + 1)
    }

    private fun resetStepCounters(name: String) {
        currentStep = name
        navChain = mutableListOf()
        httpErrors = mutableListOf()
        redirectCount = 0
        pageFinishCount = 0
        commitVisibleAt = 0L
        subResourceHttpErrors = 0
        lastErrorKey = ""
    }

    // ---------------------------------------------------------------- 页面 JS 采样

    private val probeJs = """
(function () {
  var body = document.body ? document.body.innerText.replace(/\s+/g, ' ').slice(0, 320) : '';
  var forms = [];
  for (var i = 0; i < document.forms.length && forms.length < 6; i++) {
    var f = document.forms[i];
    var names = [];
    for (var j = 0; j < f.elements.length && names.length < 12; j++) {
      if (f.elements[j].name) names.push(f.elements[j].name);
    }
    forms.push({ action: f.action, method: f.method, names: names });
  }
  var cards = document.querySelectorAll('z-bookcard').length;
  var cardLike = document.querySelectorAll('[class*="bookcard"],[class*="book-card"],.bookItem,.book-item').length;
  var challenge = /anubis|just a moment|checking your browser|verify you are|challenge|请稍候|访问验证|人机验证/i.test(body);
  return {
    href: location.href,
    title: document.title,
    ready: document.readyState,
    zBookcard: cards,
    cardLike: cardLike,
    bodyLen: document.body ? document.body.innerText.length : 0,
    challenge: challenge,
    cookieLen: document.cookie.length,
    excerpt: body,
    forms: forms
  };
})()
""".trimIndent()

    private fun sample(step: String, seq: Int) {
        if (currentStep != step) return
        val atMs = SystemClock.elapsedRealtime() - stepStartAt
        webView.evaluateJavascript(probeJs) { value ->
            if (currentStep != step) return@evaluateJavascript
            log("SAMPLE_RAW name=$step seq=$seq atMs=$atMs json=${value?.take(1600)}")
            val parsed = runCatching { JSONObject(value ?: "{}") }.getOrNull()
            if (parsed == null) {
                log("SAMPLE_PARSE_FAIL name=$step seq=$seq")
                return@evaluateJavascript
            }
            log("SAMPLE name=$step seq=$seq atMs=$atMs href=${parsed.optString("href")} " +
                "title=${parsed.optString("title").take(120)} ready=${parsed.optString("ready")} " +
                "zBookcard=${parsed.optInt("zBookcard")} cardLike=${parsed.optInt("cardLike")} " +
                "bodyLen=${parsed.optInt("bodyLen")} challenge=${parsed.optBoolean("challenge")} " +
                "cookieLen=${parsed.optInt("cookieLen")} excerpt=${parsed.optString("excerpt").take(180)}")
            if (parsed.has("forms")) {
                log("FORMS name=$step seq=$seq ${parsed.optJSONArray("forms")?.toString()?.take(600)}")
            }
        }
    }

    // ---------------------------------------------------------------- WebView 回调记录

    private inner class ProbeWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val isRedirect = request.isRedirect
            if (isRedirect) redirectCount++
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                log("BLOCKED_NON_HTTP name=$currentStep url=$url")
                return true
            }
            log("NAV name=$currentStep isRedirect=$isRedirect isMainFrame=${request.isForMainFrame} " +
                "method=${request.method} url=$url atMs=${relMs()}")
            return false // 交由 WebView 按标准浏览器行为处理
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            navChain.add(url)
            log("PAGE_START name=$currentStep idx=${navChain.size} url=$url atMs=${relMs()}")
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (commitVisibleAt == 0L) commitVisibleAt = SystemClock.elapsedRealtime()
            log("COMMIT_VISIBLE name=$currentStep atMs=${relMs()} url=$url")
        }

        override fun onPageFinished(view: WebView, url: String) {
            pageFinishCount++
            log("PAGE_FINISH name=$currentStep count=$pageFinishCount atMs=${relMs()} url=$url")
        }

        override fun onReceivedHttpError(
            view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse,
        ) {
            val status = errorResponse.statusCode
            val url = request.url.toString()
            if (request.isForMainFrame) {
                val line = "$status ${errorResponse.reasonPhrase} $url"
                httpErrors.add(line)
                log("HTTP_ERROR_MAIN name=$currentStep status=$status reason=${errorResponse.reasonPhrase} " +
                    "mime=${errorResponse.mimeType} url=$url atMs=${relMs()}")
            } else {
                subResourceHttpErrors++
                if (subResourceHttpErrors <= 8) {
                    log("HTTP_ERROR_SUB name=$currentStep status=$status url=${url.take(160)}")
                }
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            val url = request.url.toString()
            val key = "${error.errorCode}:$url"
            if (key == lastErrorKey) return
            lastErrorKey = key
            if (request.isForMainFrame) {
                log("NET_ERROR_MAIN name=$currentStep code=${error.errorCode} desc=${error.description} " +
                    "url=$url atMs=${relMs()}")
            } else if (subResourceHttpErrors <= 12) {
                log("NET_ERROR_SUB name=$currentStep code=${error.errorCode} url=${url.take(160)}")
            }
        }

        /** 不关闭 TLS 校验：只记录，并明确 cancel（等价于系统默认行为）。 */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            log("SSL_ERROR name=$currentStep primary=${error.primaryError} url=${error.url} " +
                "cert=${error.certificate?.toString()?.take(120)} -> handler.cancel()（不 proceed、不绕过校验）")
            handler.cancel()
        }
    }

    private inner class ProbeWebChromeClient : WebChromeClient() {
        override fun onReceivedTitle(view: WebView?, title: String?) {
            log("TITLE name=$currentStep title=${title.orEmpty().take(160)} atMs=${relMs()}")
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress == 100) log("PROGRESS_100 name=$currentStep atMs=${relMs()}")
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            consoleCount++
            if (consoleCount <= 40) {
                log("JS_CONSOLE name=$currentStep level=${consoleMessage.messageLevel()} " +
                    "msg=${consoleMessage.message().take(220)} src=${consoleMessage.sourceId().take(120)}")
            }
            return false
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun load(url: String) {
        if (url.isBlank()) return
        resetStepCounters("MANUAL")
        stepStartAt = SystemClock.elapsedRealtime()
        currentLoadUrl = url
        log("MANUAL_LOAD url=$url")
        webView.loadUrl(url)
        handler.postDelayed({ sample("MANUAL", 1) }, sample1Ms)
    }

    private fun searchUrl(): String {
        val encoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return "$baseUrl$searchPath$encoded"
    }

    private fun withPage(url: String): String {
        val cleaned = url.replace(Regex("([?&])${Regex.escape(pageParam)}=\\d+"), "")
            .trimEnd('?', '&')
        return cleaned + (if (cleaned.contains("?")) "&" else "?") + "$pageParam=2"
    }

    private fun chromiumMajor(ua: String): String =
        Regex("Chrome/(\\d+)").find(ua)?.groupValues?.get(1) ?: "unknown"

    private fun button(label: String, action: () -> Unit): Button =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun relMs(): Long =
        if (stepStartAt == 0L) 0L else SystemClock.elapsedRealtime() - stepStartAt

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    private fun ts(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))

    private fun log(line: String) {
        Log.i(tag, line)
        runCatching { logFile.appendText("${ts(System.currentTimeMillis())} $line\n") }
        runOnUiThread {
            if (status.lineCount > 500) status.text = ""
            status.append("$line\n")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
