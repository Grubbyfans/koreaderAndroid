package io.legado.app.help

import android.annotation.SuppressLint
import android.provider.Settings
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.HttpClientProvider
import io.legado.app.help.http.RequestMethod
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.await
import io.legado.app.help.http.get
import io.legado.app.help.http.postForm
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsRuntime
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.htmlunit.corejs.javascript.WrappedException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * 规则 JS 可见的辅助方法集（`java.*`）。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/JsExtensions.kt`（GPLv3）。
 * 裁剪：只保留网络朗读规则用得到的方法；WebView/浏览器/文件/压缩包/字体替换等能力不引入。
 * 差异（阶段 A 已知边界，报告中同步说明）：
 *  - `java.get/head/post` 返回 [StrResponse]（原版返回 jsoup `Connection.Response`），
 *    常用成员 `body()`/`headers()`/`code()` 语义保持一致；
 *  - `java.toast` 走可替换出口，默认只写日志。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
interface JsExtensions {

    fun getSource(): BaseSource?

    fun getTag(): String?

    // ---------------- 网络 ----------------

    fun ajax(url: Any): String? = ajax(url, null)

    fun ajax(url: Any, callTimeout: Long?): String? {
        val urlStr = url.toString()
        return runBlocking {
            runCatching {
                val response = request(urlStr, RequestMethod.GET, null, null, callTimeout)
                response.body
            }.onFailure {
                NetworkTtsLog.put("java.ajax 请求失败：$urlStr", it)
            }.getOrNull()
        }
    }

    fun ajaxAll(urlList: Array<String>): Array<StrResponse> = ajaxAll(urlList, false)

    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        return urlList.map { url ->
            runBlocking { runCatching { request(url, RequestMethod.GET, null, null, null) }.getOrNull() }
                ?: StrResponse(url, null)
        }.toTypedArray()
    }

    fun ajaxTestAll(urlList: Array<String>, timeout: Int): Array<StrResponse> {
        return urlList.map { url ->
            runBlocking {
                runCatching { request(url, RequestMethod.GET, null, null, timeout.toLong()) }.getOrNull()
            } ?: StrResponse(url, "请求失败")
        }.toTypedArray()
    }

    fun connect(urlStr: String): StrResponse = connect(urlStr, null)

    fun connect(urlStr: String, header: String?): StrResponse = connect(urlStr, header, null)

    fun connect(urlStr: String, header: String?, callTimeout: Long?): StrResponse {
        val headers = header?.let { GSON.fromJsonObject<Map<String, String>>(it).getOrNull() }
        return runBlocking {
            runCatching { request(urlStr, RequestMethod.GET, headers, null, callTimeout) }
                .getOrElse { StrResponse(urlStr, it.message) }
        }
    }

    fun get(urlStr: String, headers: Any?): StrResponse = get(urlStr, headers, null)

    fun get(urlStr: String, headers: Any?, timeout: Int?): StrResponse {
        val headerMap = parseJsRequestHeaders(headers)
        return runBlocking {
            runCatching {
                request(urlStr, RequestMethod.GET, headerMap, null, timeout?.toLong())
            }.getOrElse { StrResponse(urlStr, it.message) }
        }
    }

    fun head(urlStr: String, headers: Any?): StrResponse = head(urlStr, headers, null)

    fun head(urlStr: String, headers: Any?, timeout: Int?): StrResponse {
        val headerMap = parseJsRequestHeaders(headers)
        return runBlocking {
            runCatching {
                request(urlStr, RequestMethod.HEAD, headerMap, null, timeout?.toLong())
            }.getOrElse { StrResponse(urlStr, it.message) }
        }
    }

    fun post(urlStr: String, body: String, headers: Any?): StrResponse = post(urlStr, body, headers, null)

    fun post(urlStr: String, body: String, headers: Any?, timeout: Int?): StrResponse {
        val headerMap = parseJsRequestHeaders(headers)
        return runBlocking {
            runCatching {
                request(urlStr, RequestMethod.POST, headerMap, body, timeout?.toLong())
            }.getOrElse { StrResponse(urlStr, it.message) }
        }
    }

    /** 供规则直接获取请求头（含登录头）副本。 */
    fun getHeaderMap(): Map<String, String> = getSource()?.getHeaderMap(true) ?: emptyMap()

    private fun request(
        urlStr: String,
        method: RequestMethod,
        headers: Map<String, String>?,
        body: String?,
        callTimeout: Long?,
    ): StrResponse {
        val client: OkHttpClient = HttpClientProvider.client(
            readTimeoutMs = callTimeout,
            callTimeoutMs = callTimeout,
        )
        val builder = Request.Builder()
        headers?.let { builder.addHeaders(it) }
        if (getSource()?.enabledCookieJar == true) {
            builder.addHeader(CookieManager.cookieJarHeader, "1")
        }
        when (method) {
            RequestMethod.POST -> {
                builder.url(urlStr)
                val contentType = headers?.get("Content-Type")
                if (!contentType.isNullOrBlank()) {
                    builder.post((body ?: "").toRequestBody(contentType.toMediaType()))
                } else {
                    builder.postForm(body ?: "")
                }
            }

            RequestMethod.HEAD -> {
                builder.get(urlStr, null)
                builder.head()
            }

            RequestMethod.GET -> builder.get(urlStr, null)
        }
        val call = client.newCall(builder.build())
        val response = runBlocking { call.await() }
        val responseBody = if (method == RequestMethod.HEAD) "" else runCatching {
            response.body.string()
        }.getOrDefault("")
        return StrResponse(response, responseBody)
    }

    fun parseJsRequestHeaders(headers: Any?): Map<String, String> {
        return when (headers) {
            null -> emptyMap()
            is Map<*, *> -> headers.entries.associate { it.key.toString() to it.value.toString() }
            is String -> GSON.fromJsonObject<Map<String, String>>(headers).getOrNull() ?: emptyMap()
            else -> emptyMap()
        }
    }

    // ---------------- 字节/编码 ----------------

    fun strToBytes(str: String): ByteArray = str.toByteArray(Charsets.UTF_8)

    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(charset(charset))

    fun bytesToStr(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, charset(charset))

    fun base64Decode(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return EncoderUtils.base64Decode(str, 0)
    }

    fun base64Decode(str: String?, charset: String): String {
        if (str.isNullOrBlank()) return ""
        return String(EncoderUtils.base64DecodeToByteArray(str, 0), charset(charset))
    }

    fun base64Decode(str: String, flags: Int): String = EncoderUtils.base64Decode(str, flags)

    fun base64DecodeToByteArray(str: String?): ByteArray? =
        if (str.isNullOrBlank()) null else EncoderUtils.base64DecodeToByteArray(str, 0)

    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? =
        if (str.isNullOrBlank()) null else EncoderUtils.base64DecodeToByteArray(str, flags)

    fun base64Encode(str: String): String? = EncoderUtils.base64Encode(str, 2)

    fun base64Encode(str: String, flags: Int): String? = EncoderUtils.base64Encode(str, flags)

    fun hexDecodeToByteArray(hex: String): ByteArray? = runCatching {
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }.getOrNull()

    fun hexDecodeToString(hex: String): String? =
        hexDecodeToByteArray(hex)?.let { String(it, Charsets.UTF_8) }

    fun hexEncodeToString(utf8: String): String? =
        utf8.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }

    fun md5Encode(str: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(str.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun timeFormatUTC(time: Long, format: String, sh: Int): String? = runCatching {
        val formatter = SimpleDateFormat(format, Locale.getDefault())
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        formatter.format(Date(time + sh * 3600_000L))
    }.getOrNull()

    fun timeFormat(time: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(time))

    fun encodeURI(str: String): String = runCatching {
        URLEncoder.encode(str, "UTF-8")
    }.getOrDefault("")

    fun encodeURI(str: String, enc: String): String = runCatching {
        URLEncoder.encode(str, enc)
    }.getOrDefault("")

    // ---------------- 杂项 ----------------

    fun getCookie(tag: String): String = io.legado.app.help.http.CookieStore.getCookie(tag)

    fun getCookie(tag: String, key: String?): String {
        val cookie = io.legado.app.help.http.CookieStore.getCookie(tag)
        if (key.isNullOrBlank()) return cookie
        return io.legado.app.help.http.CookieStore.cookieToMap(cookie)[key] ?: ""
    }

    fun log(msg: Any?): Any? {
        NetworkTtsLog.put(msg?.toString().orEmpty())
        return msg
    }

    fun logType(any: Any?) {
        NetworkTtsLog.put("类型：${any?.javaClass?.name}")
    }

    fun toast(msg: Any?) {
        NetworkTtsLog.put("提示：${msg?.toString().orEmpty()}")
    }

    fun longToast(msg: Any?) = toast(msg)

    fun randomUUID(): String = UUID.randomUUID().toString()

    @SuppressLint("HardwareIds")
    fun androidId(): String = runCatching {
        Settings.Secure.getString(
            io.legado.app.nettts.NetworkTtsRuntime.appContext?.contentResolver,
            Settings.Secure.ANDROID_ID,
        )
    }.getOrNull().orEmpty()

    fun getWebViewUA(): String = NetworkTtsRuntime.userAgent

    /** 清空网络朗读音频缓存（对应原版 java.clearTtsCache）。 */
    fun clearTtsCache() {
        NetworkTtsRuntime.ttsCacheClearer?.invoke()
    }

    /** JS 语法/运行错误统一包一层，便于上层区分"脚本错误"与其他失败。 */
    fun isScriptError(throwable: Throwable): Boolean =
        throwable is org.htmlunit.corejs.javascript.RhinoException ||
            throwable is WrappedException ||
            throwable is com.script.ScriptException
}
