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
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
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
 * 下载入口与顶部导航栏属于阶段 D，本类只提供可嵌入的网页容器与安全边界。
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

    private lateinit var root: FrameLayout
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
        return view
    }

    private fun openWebView(url: String) {
        val view = createWebView()
        webView = view
        root.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        lastUrl = url
        view.loadUrl(url)
    }

    private fun buildContentView() {
        root = FrameLayout(this)
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
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
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
            WebNavigationPolicy.Decision.ALLOWED -> false
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
            if (decision != WebNavigationPolicy.Decision.ALLOWED) {
                view.stopLoading()
                if (decision == WebNavigationPolicy.Decision.EXTERNAL) askExternal(url) else toast("不支持打开该链接")
                return
            }
            lastUrl = url
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
