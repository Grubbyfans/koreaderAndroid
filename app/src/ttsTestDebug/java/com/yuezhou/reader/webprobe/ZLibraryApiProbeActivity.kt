package com.yuezhou.reader.webprobe

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yuezhou.reader.core.online.api.ZLibraryApi
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.reader.online.import.EpubValidator
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Calls the existing API and validator without touching the application's credentials or library. */
class ZLibraryApiProbeActivity : ComponentActivity() {
    private lateinit var output: TextView
    @Volatile private var operation = "idle"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setPadding(28, 72, 28, 40)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { text = "第三阶段 API 验证 · zh.z-lib.gd" })
        fun field(label: String, type: Int) = EditText(this).apply {
            hint = label
            setSingleLine()
            inputType = type
            isSaveEnabled = false
            layout.addView(this)
        }
        val username = field("测试账号", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val password = field("密码", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val book = field("授权 EPUB 的详情网址，或 id:hash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val authorized = CheckBox(this).apply { text = "我有权访问并下载此 EPUB，允许测试使用一次额度"; layout.addView(this) }
        val run = Button(this).apply { text = "登录并验证下载"; layout.addView(this) }
        val loginOnly = Button(this).apply { text = "只验证登录和额度"; layout.addView(this) }
        output = TextView(this).also { layout.addView(it) }
        fun submit(download: Boolean) {
            val id = identifier(book.text.toString().trim())
            val invalid = when {
                username.text.isBlank() -> username to "请输入测试账号"
                password.text.isBlank() -> password to "请输入密码"
                download && id == null -> book to "此地址未提供数字ID和hash，可先验证登录和额度"
                download && !authorized.isChecked -> authorized to "请勾选授权确认"
                else -> null
            }
            if (invalid != null) {
                invalid.first.error = invalid.second
                invalid.first.requestFocus()
                Toast.makeText(this, invalid.second, Toast.LENGTH_LONG).show()
                Log.i(tag, "FORM_INVALID field=${when (invalid.first) {
                    username -> "username"
                    password -> "password"
                    book -> "book"
                    else -> "authorization"
                }}")
                return
            }
            val user = username.text.toString().trim()
            val secret = password.text.toString()
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.hide(WindowInsets.Type.ime())
            }
            password.text.clear()
            username.text.clear()
            book.text.clear()
            authorized.isChecked = false
            run.isEnabled = false
            loginOnly.isEnabled = false
            output.text = "正在连接 $host…\n"
            Toast.makeText(this, "已提交验证", Toast.LENGTH_SHORT).show()
            Log.i(tag, "SUBMITTED download=$download")
            lifecycleScope.launch {
                try {
                    verify(user, secret, id.takeIf { download })
                } finally {
                    run.isEnabled = true
                    loginOnly.isEnabled = true
                    report("COMPLETE")
                }
            }
        }
        run.setOnClickListener { submit(download = true) }
        loginOnly.setOnClickListener { submit(download = false) }
        Log.i(tag, "READY host=$host")
    }

    private fun identifier(input: String): Pair<String, String>? {
        val uri = Uri.parse(input)
        // The pasted URL only supplies the identifiers. The network host remains fixed.
        val parts = if (uri.scheme == "https" &&
            uri.pathSegments.firstOrNull() == "book"
        ) uri.pathSegments.drop(1).take(2) else input.split(':')
        if (parts.size != 2 || !parts[0].matches(Regex("[0-9]{1,20}")) ||
            !parts[1].matches(Regex("[A-Za-z0-9]{4,32}"))
        ) return null
        return parts[0] to parts[1]
    }

    private suspend fun verify(user: String, secret: String, id: Pair<String, String>?) {
        val config = ZLibraryConfig(servers = ZLibraryConfig.Servers(baseUrls = listOf("https://$host")))
        val client = ZLibraryApi.buildClient(config).newBuilder().addNetworkInterceptor { chain ->
            val request = chain.request()
            val endpoint = when {
                request.url.encodedPath == config.endpoints.login.path -> "rpc_login"
                request.url.encodedPath == config.endpoints.loginFallback.path -> "fallback_login"
                else -> operation
            }
            chain.proceed(request).also {
                report("HTTP endpoint=$endpoint method=${request.method} status=${it.code} " +
                    "cookiePresent=${request.header("Cookie") != null} type=${it.body.contentType()?.let { type -> "${type.type}/${type.subtype}" } ?: "unknown"}")
            }
        }.build()
        val api = ZLibraryApi(config, client)
        val session = step("login") { api.login(user, secret) }
            ?.takeIf { it.cookies.isNotBlank() }
        report("API_SESSION present=${session != null}")
        if (id == null) {
            if (session != null) checkQuota(api, session, "before")
            else report("SKIP quota: no API session")
            return
        }
        val detail = step("detail") { api.getBookDetail(id.first, id.second, session) }
        val matched = detail?.remoteId == "${id.first}:${id.second}"
        val epub = detail?.format.equals("epub", ignoreCase = true)
        report("DETAIL identityMatched=$matched epub=$epub bytes=${detail?.fileSize}")
        if (session == null) {
            report("SKIP quota, link, download: no API session")
            return
        }
        checkQuota(api, session, "before")
        if (!matched || !epub) {
            report("SKIP link, download: authorized EPUB detail not verified")
            return
        }
        val link = step("link") { api.getDownloadLink(id.first, id.second, session) } ?: return
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("api-probe-", ".epub", cacheDir)
            try {
                val written = step("download") { api.downloadToFile(link, file, detail.fileSize) }
                    ?: return@withContext
                val integrity = step("validate") { runCatching { verifyEpub(file) } }
                report("FILE bytes=$written localBytes=${file.length()} ${integrity ?: "invalid"}")
            } finally {
                file.delete()
            }
        }
        checkQuota(api, session, "after")
    }

    private suspend fun checkQuota(api: ZLibraryApi, session: ZLibraryApi.AuthSession, phase: String) {
        step("quota_$phase") { api.getQuota(session) }?.let {
            report("QUOTA $phase used=${it.downloadsToday} limit=${it.downloadsLimit}")
        }
    }

    private fun verifyEpub(file: File): String {
        EpubValidator.validate(file).requireValid()
        val bytes = ByteArray(64 * 1024)
        var entries = 0
        ZipFile(file).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val count = input.read(bytes)
                        if (count < 0) break
                        crc.update(bytes, 0, count)
                    }
                }
                check(crc.value == entry.crc)
                entries++
            }
        }
        val sha = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                sha.update(bytes, 0, count)
            }
        }
        return "epubValid=true crcEntries=$entries sha256=${sha.digest().joinToString("") { "%02x".format(it) }}"
    }

    private suspend fun <T> step(name: String, block: suspend () -> Result<T>): T? {
        operation = name
        val start = SystemClock.elapsedRealtime()
        val result = block()
        val error = result.exceptionOrNull()
        val outcome = (error as? ProviderException)?.code?.name ?: error?.javaClass?.simpleName ?: "OK"
        report("RESULT step=$name outcome=$outcome ms=${SystemClock.elapsedRealtime() - start}")
        return result.getOrNull()
    }

    private fun report(line: String) {
        Log.i(tag, line)
        runOnUiThread { output.append("$line\n") }
    }

    private companion object {
        const val host = "zh.z-lib.gd"
        const val tag = "ZLibraryApiProbe"
    }
}
