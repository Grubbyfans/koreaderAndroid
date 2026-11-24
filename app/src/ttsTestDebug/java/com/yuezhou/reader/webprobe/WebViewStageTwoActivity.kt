package com.yuezhou.reader.webprobe

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import org.json.JSONObject

class WebViewStageTwoActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private val handler = Handler(Looper.getMainLooper())
    private var operation = "home"
    private var started = 0L
    private var committed = false
    private val timeout = Runnable {
        if (!committed) {
            web.stopLoading()
            record("TIMEOUT operation=$operation ms=${elapsed()}")
            Toast.makeText(this, "首屏超时，可重新访问", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val created = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 28) {
            finish()
            return
        }
        if (!profileConfigured) {
            WebView.setDataDirectorySuffix("stage_two_probe")
            profileConfigured = true
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView, url: String) {
                committed = true
                handler.removeCallbacks(timeout)
                record("VISIBLE operation=$operation host=${Uri.parse(url).host} ms=${elapsed()}")
            }

            override fun onPageFinished(view: WebView, url: String) {
                record("FINISH operation=$operation host=${Uri.parse(url).host} ms=${elapsed()}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                if (request.isForMainFrame) record("HTTP operation=$operation status=${error.statusCode} ms=${elapsed()}")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) record("NETWORK operation=$operation code=${error.errorCode} ms=${elapsed()}")
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"
        }
        web.setDownloadListener { url, _, disposition, mime, length ->
            val uri = Uri.parse(url)
            val name = URLUtil.guessFileName(url, disposition, mime)
                .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").takeLast(120)
            record("DOWNLOAD_CALLBACK host=${uri.host} epubName=${name.endsWith(".epub", true)} mime=$mime length=$length")
            if (uri.scheme == "https" && (name.endsWith(".epub", true) || mime == "application/epub+zip")) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("下载已获授权的测试 EPUB？")
                    .setMessage(name)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("下载") { _, _ ->
                        runCatching {
                            val path = "webview-stage-two/${SystemClock.elapsedRealtime()}/$name"
                            getSystemService(DownloadManager::class.java).enqueue(
                                DownloadManager.Request(uri).setTitle(name)
                                    .setDestinationInExternalFilesDir(this, null, path),
                            )
                        }.onSuccess { record("DOWNLOAD_ENQUEUED id=$it") }
                            .onFailure { record("DOWNLOAD_ENQUEUE_ERROR type=${it.javaClass.simpleName}") }
                    }.show()
            }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        address = EditText(this).apply { setSingleLine(); setText(primary) }
        root.addView(address)
        root.addView(LinearLayout(this).apply {
            addView(button("访问") { navigate("manual", address.text.toString().trim()) })
            addView(button("网页返回") { back() })
            addView(button("采样") { snapshot() })
            addView(button("退出探针") { finish() })
        })
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        record("CREATE pid=${Process.myPid()} uiMs=${SystemClock.elapsedRealtime() - created} webview=${WebView.getCurrentWebViewPackage()?.versionName}")
        cookies("create")
        command(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        command(intent)
    }

    private fun command(intent: Intent) {
        when (intent.getStringExtra("action")) {
            "sample" -> snapshot()
            "back" -> back()
            "finish" -> finish()
            "cookies" -> cookies("command")
            else -> navigate(intent.getStringExtra("label") ?: "home", intent.getStringExtra("url") ?: primary)
        }
    }

    private fun navigate(label: String, url: String) {
        if (Uri.parse(url).scheme != "https") return
        address.setText(url)
        begin(label)
        web.loadUrl(url)
    }

    private fun begin(label: String) {
        operation = label.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(40)
        started = SystemClock.elapsedRealtime()
        committed = false
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 12_000L)
        record("START operation=$operation")
    }

    private fun back() {
        record("BACK available=${web.canGoBack()}")
        if (web.canGoBack()) {
            begin("back")
            web.goBack()
        }
    }

    private fun snapshot() {
        web.evaluateJavascript(
            """({
                cards: document.querySelectorAll('z-bookcard').length,
                search: location.pathname.startsWith('/s/'),
                detail: location.pathname.startsWith('/book/'),
                page: Number(new URLSearchParams(location.search).get('page')) || 1,
                scroll: Math.round(scrollY),
                empty: /no (books|results|matches)|没有找到|未找到|没有结果/i.test(document.body?.innerText || ''),
                challenge: /checking your browser|just a moment/i.test(document.title),
                ready: document.readyState
            })""".trimIndent(),
        ) { sample ->
            record("SAMPLE operation=$operation ms=${elapsed()} ${sample ?: "null"}")
            cookies("sample")
        }
    }

    private fun cookies(event: String) {
        val manager = CookieManager.getInstance()
        record("COOKIES event=$event primaryLength=${manager.getCookie(primary)?.length ?: 0} secondaryLength=${manager.getCookie(secondary)?.length ?: 0}")
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) record("RESUME pid=${Process.myPid()}")
    }

    override fun onStop() {
        if (::web.isInitialized) {
            CookieManager.getInstance().flush()
            record("STOP cookieStoreFlushed=true")
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            (web.parent as? LinearLayout)?.removeView(web)
            web.destroy()
            record("DESTROY pid=${Process.myPid()}")
        }
        super.onDestroy()
    }

    private fun button(label: String, click: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { click() } }

    private fun elapsed() = SystemClock.elapsedRealtime() - started

    private fun record(message: String) {
        Log.i("WebStageTwo", message)
    }

    private companion object {
        var profileConfigured = false
        const val primary = "https://zh.z-lib.gd/"
        const val secondary = "https://zh.z-library.sk/"
    }
}
