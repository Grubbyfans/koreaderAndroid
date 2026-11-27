package io.legado.app.help.http

import io.legado.app.constant.AppConst
import io.legado.app.nettts.NetworkTtsRuntime
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 共享 OkHttp 客户端。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/http/HttpHelper.kt`（GPLv3）。
 * 裁剪：去掉代理/SOCKS5/Cronet/图片进度/日志落库（静读侧不需要），保留 UA 注入、
 * CookieJar 头部标记、Keep-Alive 与超时覆盖能力（UrlOption 的 timeout/followRedirects 依赖）。
 */
object HttpClientProvider {

    private val clientCache = ConcurrentHashMap<ClientKey, OkHttpClient>()

    private data class ClientKey(
        val readTimeoutMs: Long,
        val callTimeoutMs: Long,
        val followRedirects: Boolean,
    )

    val baseClient: OkHttpClient by lazy {
        val specs = arrayListOf(
            ConnectionSpec.MODERN_TLS,
            ConnectionSpec.COMPATIBLE_TLS,
            ConnectionSpec.CLEARTEXT,
        )
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .connectionSpecs(specs)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                if (request.header(AppConst.UA_NAME) == null) {
                    builder.addHeader(AppConst.UA_NAME, NetworkTtsRuntime.userAgent)
                } else if (request.header(AppConst.UA_NAME) == "null") {
                    builder.removeHeader(AppConst.UA_NAME)
                }
                builder.addHeader("Keep-Alive", "300")
                builder.addHeader("Connection", "Keep-Alive")
                builder.addHeader("Cache-Control", "no-cache")
                chain.proceed(builder.build())
            }
            .addNetworkInterceptor { chain ->
                var request = chain.request()
                val enableCookieJar = request.header(CookieManager.cookieJarHeader) != null
                if (enableCookieJar) {
                    val requestBuilder = request.newBuilder()
                    requestBuilder.removeHeader(CookieManager.cookieJarHeader)
                    request = CookieManager.loadRequest(requestBuilder.build())
                }
                val networkResponse = chain.proceed(request)
                if (enableCookieJar) {
                    CookieManager.saveResponse(networkResponse)
                }
                networkResponse
            }
            .build()
    }

    /**
     * 按规则覆盖超时/重定向。默认值与共享客户端一致，未覆盖时直接复用。
     */
    fun client(
        readTimeoutMs: Long? = null,
        callTimeoutMs: Long? = null,
        followRedirects: Boolean? = null,
    ): OkHttpClient {
        if (readTimeoutMs == null && callTimeoutMs == null && followRedirects == null) {
            return baseClient
        }
        val key = ClientKey(
            readTimeoutMs = readTimeoutMs ?: 60_000L,
            callTimeoutMs = callTimeoutMs ?: 60_000L,
            followRedirects = followRedirects ?: true,
        )
        clientCache[key]?.let { return it }
        val builder = baseClient.newBuilder()
            .readTimeout(key.readTimeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(key.callTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(key.followRedirects)
            .followSslRedirects(key.followRedirects)
        return clientCache.getOrPut(key) { builder.build() }
    }
}
