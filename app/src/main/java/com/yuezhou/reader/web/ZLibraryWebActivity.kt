package com.yuezhou.reader.web

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Z-Library 内置网页容器（阶段 C）。
 *
 * - 非导出、独立进程 `:zlibraryweb`；Application 进程守卫保证本进程不初始化 Room/同步/TTS。
 * - API 28+ 在创建任何 WebView/CookieManager 之前设置独立数据目录；更低版本直接交系统浏览器。
 * - 只允许配置服务器及子域的 HTTPS 主框架导航；外部域确认后交系统浏览器，危险 scheme 拒绝。
 * - 无 JS bridge、禁用 file/content 访问、禁止 mixed content、SSL 错误一律取消。
 * - 页面状态按导航代次管理；30 秒超时提供继续等待/重试/关闭；渲染器退出后可重建。
 *
 * 下载入口与顶部导航栏属于阶段 D：下载只接受用户确认后的 HTTPS 交付，经系统
 * DownloadManager 落到应用私有暂存，再由主进程校验、命名并导入。
 */
class ZLibraryWebActivity : ComponentActivity() {

    private val state = WebPageStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    /** 系统返回键优先返回网页历史；没有历史时交回默认行为（结束 Activity）。 */
    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var hostLabel: TextView
    private lateinit var historyButton: TextView
    private lateinit var webView: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayMessage: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayButtons: LinearLayout

    private var allowedHosts: Set<String> = emptySet()
    private var lastUrl: String? = null
    private var timeout: Runnable? = null
    private var askingExternal = false
    private var askingDownload = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (!ZLibraryWebSupport.isSupported()) {
            // API < 28：多进程无法安全隔离 WebView 数据目录，首版只用外部浏览器。
            intent.getStringExtra(EXTRA_INITIAL_URL)?.let { ZLibraryWebSupport.openExternally(this, it) }
            finish()
            return
        }

        allowedHosts = loadAllowedHosts()
        val initial = intent.getStringExtra(EXTRA_INITIAL_URL)
        if (initial == null || WebNavigationPolicy.classify(initial, allowedHosts) != WebNavigationPolicy.Decision.ALLOWED) {
            finish()
            return
        }

        ensureIsolatedDataDirectory()
        buildContentView()
        title = "Z-Library"
        openWebView(initial)
    }

    override fun onStop() {
        super.onStop()
        // 网页 Cookie 由独立数据目录管理；离页时落盘，避免进程被回收丢失登录态。
        if (::webView.isInitialized) runCatching { CookieManager.getInstance().flush() }
    }

    override fun onDestroy() {
        cancelTimeout()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView 装配

    private fun ensureIsolatedDataDirectory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (dataDirectoryReady.compareAndSet(false, true)) {
            WebView.setDataDirectorySuffix(WEB_DATA_DIRECTORY)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) WebView.setWebContentsDebuggingEnabled(false)
        val view = WebView(this)
        view.settings.apply {
            // 与标准浏览器一致：仅开启网页正常运行所需的 JS 与 DOM Storage。
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            setMediaPlaybackRequiresUserGesture(true)
            setGeolocationEnabled(false)
            // 不覆盖 UA，不注册任何 addJavascriptInterface。
        }
        view.webViewClient = Client()
        view.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            onDownloadRequested(url, userAgent, contentDisposition, mimeType, contentLength)
        }
        return view
    }

    private fun openWebView(url: String) {
        val view = createWebView()
        webView = view
        content.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lastUrl = url
        updateHistoryButton()
        view.loadUrl(url)
    }

    @SuppressLint("SetTextI18n") // 站点名是品牌名，不做本地化。
    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), 0)
        }
        // 返回 = 结束网页容器回到 Z-Library；浏览器历史由「后退」单独处理。
        bar.addView(barItem("返回") { finish() }, barParams())
        hostLabel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            text = "Z-Library"
        }
        bar.addView(hostLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        historyButton = barItem("后退") { goBackInHistory() }
        bar.addView(historyButton, barParams())
        bar.addView(barItem("刷新") { refreshPage() }, barParams())
        bar.addView(barItem("关闭") { finish() }, barParams())
        return bar
    }

    private fun barParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT)

    private fun barItem(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 14f
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun updateHistoryButton() {
        if (!::historyButton.isInitialized) return
        val canGoBack = ::webView.isInitialized && webView.canGoBack()
        historyButton.isEnabled = canGoBack
        historyButton.alpha = if (canGoBack) 1f else 0.35f
    }

    private fun goBackInHistory() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
    }

    private fun refreshPage() {
        if (::webView.isInitialized) webView.reload() else lastUrl?.let(::openWebView)
    }

    private fun buildContentView() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        root.addView(buildTopBar(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            setBackgroundColor(Color.argb(238, 255, 255, 255))
            visibility = View.GONE
        }
        overlayTitle = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        overlayMessage = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        overlayProgress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        overlayButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        overlay.addView(
            overlayTitle,
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        overlay.addView(
            overlayMessage,
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        overlay.addView(
            overlayProgress,
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        overlay.addView(
            overlayButtons,
            LinearLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT),
        )
        content.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // targetSdk 36 起系统强制 edge-to-edge，顶部栏需让出状态栏/导航栏区域。
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
    }

    private fun loadAllowedHosts(): Set<String> {
        val servers = ZLibraryConfigStore(this).load().servers
        return buildSet {
            servers.baseUrls.forEach { WebNavigationPolicy.hostOf(it)?.let(::add) }
            servers.candidates.filter { it.enabled }.forEach { WebNavigationPolicy.hostOf(it.baseUrl)?.let(::add) }
            WebNavigationPolicy.hostOf(servers.manualBaseUrl)?.let(::add)
        }
    }

    // ---------------------------------------------------------------- 导航与状态

    private fun handleMainFrameNavigation(rawUrl: String): Boolean {
        return when (WebNavigationPolicy.classify(rawUrl, allowedHosts)) {
            WebNavigationPolicy.Decision.ALLOWED,
            WebNavigationPolicy.Decision.DOWNLOAD -> false
            WebNavigationPolicy.Decision.EXTERNAL -> {
                askExternal(rawUrl)
                true
            }
            WebNavigationPolicy.Decision.BLOCKED -> {
                toast("不支持打开该链接")
                true
            }
        }
    }

    private fun askExternal(rawUrl: String) {
        if (askingExternal || isFinishing) return
        askingExternal = true
        AlertDialog.Builder(this)
            .setTitle("离开 Z-Library")
            .setMessage("该链接属于外部网站，是否使用系统浏览器打开？")
            .setPositiveButton("使用浏览器打开") { _, _ -> ZLibraryWebSupport.openExternally(this, rawUrl) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { askingExternal = false }
            .show()
    }

    // ---------------------------------------------------------------- 下载确认（阶段 D）

    private fun onDownloadRequested(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        if (isFinishing || askingDownload) return
        val safeUrl = DownloadRequestPolicy.supportedUrl(url)
        if (safeUrl == null) {
            toast("暂不支持该下载方式，请在浏览器中下载后手动导入")
            return
        }
        askingDownload = true
        val name = DownloadRequestPolicy.candidateFileName(contentDisposition, safeUrl)
        val source = DownloadRequestPolicy.sourceHost(safeUrl) ?: "未知站点"
        AlertDialog.Builder(this)
            .setTitle("下载并导入书籍")
            .setMessage(
                "来源：$source\n" +
                    "大小：${DownloadRequestPolicy.sizeText(contentLength)}\n" +
                    "候选名：${name ?: "下载后按书籍标题确定"}\n\n" +
                    "文件先下载到应用私有目录，校验后进入下载任务并导入书架；仅下载你有权使用的 EPUB。",
            )
            .setPositiveButton("下载并导入") { _, _ ->
                startDownload(safeUrl, userAgent, contentDisposition, mimeType, contentLength)
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { askingDownload = false }
            .show()
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long,
    ) {
        // DownloadManager 入队与 Provider 登记都可能在主进程冷启动时短暂阻塞，放到后台线程。
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ZLibraryWebDownloadStarter(applicationContext).start(
                    url = url,
                    userAgent = userAgent,
                    mimeType = mimeType,
                    contentDisposition = contentDisposition,
                    contentLength = contentLength,
                )
            }
            when (result) {
                is ZLibraryWebDownloadStarter.Result.Started ->
                    toast("已开始下载，完成后自动导入，可在下载管理中查看")
                is ZLibraryWebDownloadStarter.Result.Unsupported -> toast(result.message)
                is ZLibraryWebDownloadStarter.Result.Failed -> toast(result.message)
            }
        }
    }

    private fun showLoading() {
        overlay.visibility = View.VISIBLE
        overlayTitle.text = "正在加载网页"
        overlayMessage.text = ""
        overlayProgress.visibility = View.VISIBLE
        overlayButtons.removeAllViews()
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        overlayProgress.visibility = View.GONE
        overlayButtons.removeAllViews()
    }

    private fun showError(title: String, message: String) {
        showPanel(title, message) {
            button("重试") { retryCurrent() }
            button("关闭") { finish() }
        }
    }

    private fun showTimeout() {
        showPanel("页面加载超时", "网络较慢或站点正在验证，可继续等待或重新加载。") {
            button("继续等待") { continueWaiting() }
            button("重试") { retryCurrent() }
            button("关闭") { finish() }
        }
    }

    private fun showRendererGone() {
        showPanel("网页渲染器已退出", "页面渲染进程被系统回收，可重新加载。") {
            button("重新加载") { recreateAfterRendererGone() }
            button("关闭") { finish() }
        }
    }

    private fun showPanel(title: String, message: String, buttons: () -> Unit) {
        overlay.visibility = View.VISIBLE
        overlayTitle.text = title
        overlayMessage.text = message
        overlayProgress.visibility = View.GONE
        overlayButtons.removeAllViews()
        buttons()
    }

    private fun button(label: String, action: () -> Unit) {
        overlayButtons.addView(
            Button(this).apply {
                text = label
                setOnClickListener { action() }
            },
        )
    }

    private fun retryCurrent() {
        hideOverlay()
        if (::webView.isInitialized) {
            webView.reload()
        } else {
            lastUrl?.let(::openWebView) ?: finish()
        }
    }

    private fun continueWaiting() {
        // 继续等待不触发新导航，也不重新提交搜索；只恢复计时并撤去超时面板。
        state.continueWaiting()
        hideOverlay()
        armTimeout(state.generation)
    }

    private fun recreateAfterRendererGone() {
        state.resetAfterRendererGone()
        val url = lastUrl
        if (url == null) {
            finish()
            return
        }
        hideOverlay()
        openWebView(url)
    }

    private fun armTimeout(generation: Int) {
        cancelTimeout()
        val runnable = Runnable {
            if (state.markTimeout(generation)) showTimeout()
        }
        timeout = runnable
        handler.postDelayed(runnable, PAGE_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleMainFrameNavigation(request.url.toString())
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val decision = WebNavigationPolicy.classify(url, allowedHosts)
            if (decision == WebNavigationPolicy.Decision.EXTERNAL || decision == WebNavigationPolicy.Decision.BLOCKED) {
                view.stopLoading()
                if (decision == WebNavigationPolicy.Decision.EXTERNAL) askExternal(url) else toast("不支持打开该链接")
                return
            }
            // 下载 CDN 的导航交给 DownloadListener；不按页面展示处理，避免残留加载遮罩。
            if (decision == WebNavigationPolicy.Decision.DOWNLOAD) {
                hostLabel.text = WebNavigationPolicy.hostOf(url) ?: "Z-Library"
                return
            }
            lastUrl = url
            hostLabel.text = WebNavigationPolicy.hostOf(url) ?: "Z-Library"
            updateHistoryButton()
            val generation = state.beginNavigation()
            showLoading()
            armTimeout(generation)
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (state.markContentVisible(state.generation)) {
                cancelTimeout()
                hideOverlay()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            hostLabel.text = WebNavigationPolicy.hostOf(url) ?: "Z-Library"
            updateHistoryButton()
            if (state.markContentVisible(state.generation)) {
                cancelTimeout()
                hideOverlay()
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            if (state.markNetworkError(state.generation)) {
                cancelTimeout()
                showError("网页加载失败", "无法连接站点，请检查网络后重试。")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (!request.isForMainFrame) return
            val code = response.statusCode
            when {
                // 验证/限流页面按普通网页继续展示，不自行处理验证。
                code == 503 -> state.markWaiting(state.generation)
                code >= 500 -> if (state.markNetworkError(state.generation)) {
                    cancelTimeout()
                    showError("站点暂时不可用", "服务器返回 $code，请稍后重试。")
                }
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // 不向用户提供 proceed，也不覆盖系统证书信任。
            handler.cancel()
            if (state.markNetworkError(state.generation)) {
                cancelTimeout()
                showError("安全连接失败", "站点证书校验未通过，已取消连接。")
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            state.markRendererGone()
            cancelTimeout()
            (view.parent as? ViewGroup)?.removeView(view)
            runCatching { view.destroy() }
            showRendererGone()
            return true
        }
    }

    companion object {
        const val EXTRA_INITIAL_URL = "com.yuezhou.reader.extra.ZLIBRARY_WEB_URL"

        private const val PAGE_TIMEOUT_MS = 30_000L
        private const val WEB_DATA_DIRECTORY = "zlibrary_web"
        private val dataDirectoryReady = AtomicBoolean(false)
    }
}
