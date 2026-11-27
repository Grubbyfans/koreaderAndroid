package io.legado.app.model.analyzeRule

import android.annotation.SuppressLint
import android.util.Base64
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.ConcurrentRateLimiter
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieManager
import io.legado.app.help.http.CookieManager.mergeCookies
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.HttpClientProvider
import io.legado.app.help.http.RequestMethod
import io.legado.app.help.http.StrResponse
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.get
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.postForm
import io.legado.app.help.http.postJson
import io.legado.app.model.SharedJsScope
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.GSONStrict
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isXml
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.regex.Pattern
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * URL 规则解析。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/model/analyzeRule/AnalyzeUrl.kt`（GPLv3）。
 *
 * 保留（与原版语义一致）：`@js:`/`<js>`/`{{}}` 解析、逗号后 UrlOption（method/headers/body/
 * charset/retry/timeout/followRedirects/js/type/bodyJs）、表单与 Query 编码判定、cookie 合并、
 * 并发率限速、错误响应构造。
 *
 * 移除（按阶段 0 决策，命中时显式报错而不是静默降级）：`webView`、`dnsIp`、`serverID`、
 * Glide/Cronet/代理、多部分上传、书籍/章节变量（网络朗读无此上下文）。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
@Keep
@SuppressLint("DefaultLocale")
class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private val speakText: String? = null,
    private val speakSpeed: Int? = null,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val readTimeout: Long? = null,
    private val callTimeout: Long? = null,
    private var coroutineContext: CoroutineContext = EmptyCoroutineContext,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    private val infoMap: MutableMap<String, String>? = null,
    private val extraParams: Map<String, String>? = null,
) : JsExtensions {

    constructor(mUrl: String) : this(mUrl, null)

    var ruleUrl = ""
        private set
    var url: String = ""
        private set
    var type: String? = null
        private set
    val headerMap = LinkedHashMap<String, String>()
    private var body: String? = null
    var urlNoQuery: String = ""
        private set
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = RequestMethod.GET
    private var readTimeoutMs: Long? = readTimeout
    private var callTimeoutMs: Long? = callTimeout
    private var followRedirects: Boolean? = null
    private var retry: Int = 0
    private var bodyJs: String? = null
    private var unsupportedOption: String? = null
    private val enabledCookieJar = source?.enabledCookieJar == true
    private val domain: String
    private val concurrentRateLimiter = ConcurrentRateLimiter(source)

    init {
        coroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
        val urlMatcher = paramPattern.matcher(baseUrl)
        if (urlMatcher.find()) baseUrl = baseUrl.substring(0, urlMatcher.start())
        (headerMapF ?: source?.getHeaderMap(hasLoginHeader && isLoginHeaderSite(mUrl)))?.let {
            headerMap.putAll(it)
        }
        initUrl()
        val sourceKey = source?.getKey()
        domain = sourceKey?.takeIf { NetworkUtils.getBaseUrl(it) == null }
            ?: NetworkUtils.getSubDomain(url)
    }

    /** 登录头只发给规则同站请求（与原版一致，避免跨域泄漏凭证）。 */
    private fun isLoginHeaderSite(url: String): Boolean {
        val source = this.source ?: return true
        val sourceDomain = NetworkUtils.getSubDomainOrNull(source.getKey()) ?: return true
        val target = url.takeIf { it.startsWith("http", true) }
            ?: baseUrl.takeIf { it.isNotBlank() }
            ?: return true
        val targetDomain = NetworkUtils.getSubDomainOrNull(target) ?: return true
        return targetDomain.equals(sourceDomain, ignoreCase = true)
    }

    fun initUrl() {
        ruleUrl = mUrl
        analyzeJs()
        replaceKeyPageJs()
        analyzeUrl()
        unsupportedOption?.let { throw UnsupportedOptionException(it) }
    }

    private fun analyzeJs() {
        var start = 0
        val jsMatcher = AppPattern.JS_PATTERN.matcher(ruleUrl)
        var result = ruleUrl
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                ruleUrl.substring(start, jsMatcher.start()).trim().let {
                    if (it.isNotEmpty()) result = it.replace("@result", result)
                }
            }
            result = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1), result).toString()
            start = jsMatcher.end()
        }
        if (ruleUrl.length > start) {
            ruleUrl.substring(start).trim().let {
                if (it.isNotEmpty()) result = it.replace("@result", result)
            }
        }
        ruleUrl = result
    }

    private fun replaceKeyPageJs() {
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl)
            val url = analyze.innerRule("{{", "}}") {
                val jsEval = evalJS(it) ?: ""
                when (jsEval) {
                    is String -> jsEval
                    is Double if jsEval % 1.0 == 0.0 -> String.format("%.0f", jsEval)
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        page?.let {
            val matcher = AppPattern.pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val pages = matcher.group(1)!!.split(",")
                ruleUrl = if (page < pages.size) {
                    ruleUrl.replace(matcher.group(), pages[page - 1].trim())
                } else {
                    ruleUrl.replace(matcher.group(), pages.last().trim())
                }
            }
        }
    }

    private fun analyzeUrl() {
        val urlMatcher = paramPattern.matcher(ruleUrl)
        val urlNoOption =
            if (urlMatcher.find()) ruleUrl.substring(0, urlMatcher.start()) else ruleUrl
        url = NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let { baseUrl = it }
        // 处理一些规则 URL 里存在的重复 query key（如两个 voiceId），保留最后一个值，
        // 避免服务端因重复参数返回 400。无重复参数的 URL 不会被改写。
        url = NetworkUtils.dedupQueryParams(url)
        if (urlNoOption.length != ruleUrl.length) {
            val urlOptionStr = ruleUrl.substring(urlMatcher.end())
            var urlOption = GSONStrict.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
            if (urlOption == null) {
                urlOption = GSON.fromJsonObject<UrlOption>(urlOptionStr).getOrNull()
                if (urlOption != null) {
                    log("链接参数 JSON 格式不规范，请改为规范格式")
                }
            }
            urlOption?.let { option ->
                option.getMethod()?.let {
                    method = when (it.uppercase()) {
                        "POST" -> RequestMethod.POST
                        "HEAD" -> RequestMethod.HEAD
                        else -> RequestMethod.GET
                    }
                }
                option.getHeaderMap()?.forEach { entry ->
                    headerMap[entry.key.toString()] = entry.value.toString()
                }
                option.getBody()?.let { body = it }
                type = option.getType()
                charset = option.getCharset()
                retry = option.getRetry()
                option.getTimeout()?.let { readTimeoutMs = it }
                callTimeoutMs = callTimeoutMs ?: readTimeoutMs
                followRedirects = option.getFollowRedirects()
                bodyJs = option.getBodyJs()
                if (option.useWebView()) unsupportedOption = "该规则使用 webView 参数，静读暂不支持"
                option.getDnsIp()?.let { unsupportedOption = "该规则使用 dnsIp 参数，静读暂不支持" }
                option.getServerID()?.let { unsupportedOption = "该规则使用 serverID 参数，静读暂不支持" }
                option.getJs()?.let { jsStr ->
                    evalJS(jsStr, url)?.toString()?.let { url = it }
                }
            }
        }
        urlNoQuery = url
        when (method) {
            RequestMethod.POST -> body?.let {
                if (!it.isJson() && !it.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                    encodedForm = encodeParams(it, charset, false)
                }
            }

            else -> {
                val pos = url.indexOf('?')
                if (pos != -1) {
                    encodedQuery = encodeParams(url.substring(pos + 1), charset, true)
                    urlNoQuery = url.substring(0, pos)
                }
            }
        }
    }

    private fun encodeParams(params: String, charset: String?, isQuery: Boolean): String {
        val checkEncoded = charset.isNullOrEmpty()
        val charsetObj = when {
            charset.isNullOrEmpty() -> Charsets.UTF_8
            charset == "escape" -> null
            else -> Charset.forName(charset)
        }
        if (isQuery && charsetObj != null) {
            if (NetworkUtils.encodedQuery(params)) return params
            return encodeQueryChars(params, charsetObj)
        }
        val len = params.length
        val sb = StringBuilder()
        var pos = 0
        while (pos <= len) {
            if (sb.isNotEmpty()) sb.append("&")
            var ampOffset = params.indexOf("&", pos)
            if (ampOffset == -1) ampOffset = len
            val eqOffset = params.indexOf("=", pos)
            val keyParam: String
            val value: String?
            if (eqOffset == -1 || eqOffset > ampOffset) {
                keyParam = params.substring(pos, ampOffset)
                value = null
            } else {
                keyParam = params.substring(pos, eqOffset)
                value = params.substring(eqOffset + 1, ampOffset)
            }
            sb.appendEncoded(keyParam, checkEncoded, charsetObj)
            if (value != null) {
                sb.append("=")
                sb.appendEncoded(value, checkEncoded, charsetObj)
            }
            pos = ampOffset + 1
        }
        return sb.toString()
    }

    private fun encodeQueryChars(params: String, charset: Charset): String {
        val builder = StringBuilder()
        for (char in params) {
            if (char.code < 128 && QUERY_SAFE_CHARS.indexOf(char) >= 0) {
                builder.append(char)
            } else {
                for (byte in char.toString().toByteArray(charset)) {
                    builder.append('%').append("%02X".format(byte))
                }
            }
        }
        return builder.toString()
    }

    private fun StringBuilder.appendEncoded(value: String, checkEncoded: Boolean, charset: Charset?) {
        if (checkEncoded && NetworkUtils.encodedForm(value)) {
            append(value)
        } else if (charset == null) {
            append(EncoderUtils.escape(value))
        } else {
            append(URLEncoder.encode(value, charset))
        }
    }

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val bindings = buildScriptBindings { bindings ->
            bindings["java"] = this
            bindings["baseUrl"] = baseUrl
            bindings["cookie"] = CookieStore
            bindings["cache"] = CacheManager
            bindings["page"] = page
            bindings["key"] = key
            bindings["speakText"] = speakText
            bindings["speakSpeed"] = speakSpeed
            bindings["book"] = null
            bindings["source"] = source
            bindings["result"] = result
            extraParams?.forEach { (name, value) ->
                bindings[name] = if (name == "page") value.toIntOrNull() ?: value else value
            }
            bindings["infoMap"] = infoMap
        }
        val sharedScope = source?.let { SharedJsScope.scopeFor(it) }
        val scope = if (sharedScope == null) {
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            bindings.apply {
                chainTo(sharedScope, SharedJsScope.globalStateKey(source.getKey()))
            }
        }
        return RhinoScriptEngine.eval(jsStr, scope, coroutineContext.takeIf { it !== EmptyCoroutineContext })
    }

    fun put(key: String, value: String): String {
        source?.put(key, value)
        return value
    }

    fun get(key: String): String {
        extraParams?.get(key)?.let { return it }
        return source?.get(key) ?: ""
    }

    // ---------------- 请求 ----------------

    suspend fun getStrResponseAwait(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
        isTest: Boolean = false,
        skipRateLimit: Boolean = false,
    ): StrResponse {
        if (type != null) {
            return StrResponse(url, hexEncodeToString(String(getByteArrayAwait())) ?: "")
        }
        if (skipRateLimit) return executeStrRequest(isTest)
        concurrentRateLimiter.withLimit { return executeStrRequest(isTest) }
    }

    private suspend fun executeStrRequest(isTest: Boolean): StrResponse {
        val startTime = System.currentTimeMillis()
        return try {
            val strResponse = getClient().newCallStrResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm ?: "")
                        } else if (!contentType.isNullOrBlank()) {
                            post(body.toRequestBody(contentType.toMediaType()))
                        } else {
                            postJson(body)
                        }
                    }

                    RequestMethod.HEAD -> {
                        get(urlNoQuery, encodedQuery)
                        head()
                    }

                    else -> get(urlNoQuery, encodedQuery)
                }
            }.let {
                if (bodyJs != null) {
                    StrResponse(it.raw, evalJS(bodyJs!!, it.body).toString())
                } else {
                    it
                }
            }
            strResponse.putCallTime((System.currentTimeMillis() - startTime).toInt())
            strResponse
        } catch (e: Exception) {
            if (!isTest) throw e
            StrResponse(url, e.message).apply { putCallTime(-7) }
        }
    }

    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        useWebView: Boolean = true,
    ): StrResponse = runBlocking(coroutineContext) {
        getStrResponseAwait(jsStr, sourceRegex, useWebView)
    }

    suspend fun getResponseAwait(): Response {
        return concurrentRateLimiter.withLimit {
            setCookie()
            getClient().newCallResponse(retry) {
                addHeaders(headerMap)
                when (method) {
                    RequestMethod.POST -> {
                        url(urlNoQuery)
                        val contentType = headerMap["Content-Type"]
                        val body = body
                        if (!encodedForm.isNullOrEmpty() || body.isNullOrBlank()) {
                            postForm(encodedForm ?: "")
                        } else if (!contentType.isNullOrBlank()) {
                            post(body.toRequestBody(contentType.toMediaType()))
                        } else {
                            postJson(body)
                        }
                    }

                    else -> get(urlNoQuery, encodedQuery)
                }
            }
        }
    }

    fun getErrResponse(e: Throwable): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(okhttp3.Protocol.HTTP_1_1)
        .code(500)
        .message(e.message ?: "Error Response")
        .body((e.stackTraceToString()).toResponseBody(null))
        .build()

    fun getErrStrResponse(e: Throwable): StrResponse = StrResponse(getErrResponse(e), e.stackTraceToString())

    private fun getClient(): OkHttpClient {
        return HttpClientProvider.client(
            readTimeoutMs = readTimeoutMs,
            callTimeoutMs = callTimeoutMs,
            followRedirects = followRedirects,
        )
    }

    fun getResponse(): Response = runBlocking(coroutineContext) { getResponseAwait() }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!urlNoQuery.startsWith("data:")) return null
        val match = AppPattern.dataUriRegex.find(urlNoQuery) ?: return null
        return Base64.decode(match.groupValues[1], Base64.DEFAULT)
    }

    suspend fun getByteArrayAwait(): ByteArray {
        getByteArrayIfDataUri()?.let { return it }
        return getResponseAwait().body.bytes()
    }

    fun getByteArray(): ByteArray = runBlocking(coroutineContext) { getByteArrayAwait() }

    suspend fun getInputStreamAwait(): InputStream {
        getByteArrayIfDataUri()?.let { return ByteArrayInputStream(it) }
        return getResponseAwait().body.byteStream()
    }

    fun getInputStream(): InputStream = runBlocking(coroutineContext) { getInputStreamAwait() }

    /** cookie 优先级：UrlOption 临时 cookie > 存储 cookie（与原版一致）。 */
    private fun setCookie() {
        val cookie = CookieStore.getCookie(domain)
        if (cookie.isNotEmpty()) {
            mergeCookies(cookie, headerMap["Cookie"])?.let { headerMap["Cookie"] = it }
        }
        if (enabledCookieJar) {
            headerMap[CookieManager.cookieJarHeader] = "1"
        } else {
            headerMap.remove(CookieManager.cookieJarHeader)
        }
    }

    override fun getSource(): BaseSource? = source

    override fun getTag(): String? = source?.getTag()

    fun isPost(): Boolean = method == RequestMethod.POST

    fun getUserAgent(): String =
        headerMap.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            ?: io.legado.app.nettts.NetworkTtsRuntime.userAgent

    /** 规则使用了静读暂不支持的 UrlOption 参数。 */
    class UnsupportedOptionException(message: String) : Exception(message)

    companion object {
        val paramPattern: Pattern = Pattern.compile("\\s*,\\s*(?=\\{)")

        /** RFC3986 允许集合 + 原版 PercentCodec 额外放行的字符。 */
        private const val QUERY_SAFE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$%&()*+,/:;=?@[]^`{|}"

        private fun log(message: String) = NetworkTtsLog.put(message)
    }

    @Keep
    data class UrlOption(
        private var method: String? = null,
        private var charset: String? = null,
        private var headers: Any? = null,
        private var body: Any? = null,
        private var origin: String? = null,
        private var retry: Int? = null,
        private var type: String? = null,
        private var webView: Any? = null,
        private var webJs: String? = null,
        private var timeout: Any? = null,
        private var followRedirects: Any? = null,
        @SerializedName(value = "dnsIp", alternate = ["resolveIp"])
        private var dnsIp: String? = null,
        private var js: String? = null,
        private var bodyJs: String? = null,
        private var serverID: Long? = null,
        private var webViewDelayTime: Long? = null,
    ) {
        fun getMethod(): String? = method

        fun getCharset(): String? = charset

        fun getRetry(): Int = retry ?: 0

        fun getType(): String? = type

        fun useWebView(): Boolean = when (webView) {
            null, "", false, "false" -> false
            else -> true
        }

        fun getHeaderMap(): Map<*, *>? = when (val value = headers) {
            is Map<*, *> -> value
            is String -> GSON.fromJsonObject<Map<String, Any>>(value).getOrNull()
            else -> null
        }

        fun getBody(): String? = body?.let { it as? String ?: GSON.toJson(it) }

        fun getTimeout(): Long? {
            val value = timeout ?: return null
            val millis = when (value) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> null
            } ?: return null
            return millis.takeIf { it > 0 }
        }

        fun getFollowRedirects(): Boolean? = when (val value = followRedirects) {
            null, "" -> null
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }

            is Number -> value.toInt() != 0
            else -> null
        }

        fun getDnsIp(): String? = dnsIp?.takeIf { it.isNotBlank() }

        fun getJs(): String? = js?.takeIf { it.isNotBlank() }

        fun getBodyJs(): String? = bodyJs?.takeIf { it.isNotBlank() }

        fun getServerID(): Long? = serverID
    }

    data class ConcurrentRecord(
        var time: Long,
        var accessLimit: Int,
        var interval: Int,
        var frequency: Int,
    )
}
