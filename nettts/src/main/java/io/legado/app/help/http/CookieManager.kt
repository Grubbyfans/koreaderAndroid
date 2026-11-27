package io.legado.app.help.http

import io.legado.app.help.CacheManager
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.utils.NetworkUtils
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response

/**
 * Cookie 请求/响应处理。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/http/CookieManager.kt`（GPLv3）。
 * 裁剪：去掉 WebView / jsoup Connection.Response 分支（静读网络朗读不使用 WebView）。
 */
object CookieManager {

    const val cookieJarHeader = "CookieJar"

    fun saveResponse(response: Response) {
        saveCookiesFromHeaders(response.request.url, response.headers)
    }

    private fun saveCookiesFromHeaders(url: HttpUrl, headers: Headers) {
        val domain = NetworkUtils.getSubDomain(url.toString())
        val cookies = Cookie.parseAll(url, headers)
        val sessionCookie = cookies.filter { !it.persistent }.getString()
        updateSessionCookie(domain, sessionCookie)
        val cookieString = cookies.filter { it.persistent }.getString()
        CookieStore.replaceCookie(domain, cookieString)
    }

    fun loadRequest(request: Request): Request {
        val url = request.url.toString()
        val domain = NetworkUtils.getSubDomain(url)
        val cookie = CookieStore.getCookie(domain)
        val requestCookie = request.header("Cookie")
        val newCookie = mergeCookies(requestCookie, cookie) ?: return request
        return runCatching {
            request.newBuilder().header("Cookie", newCookie).build()
        }.onFailure {
            CookieStore.removeCookie(url)
            NetworkTtsLog.put("设置 cookie 出错，已清除 $domain 的 cookie", it)
        }.getOrDefault(request)
    }

    fun getSessionCookie(domain: String): String? {
        return CacheManager.getFromMemory("${domain}_session_cookie") as? String
    }

    private fun updateSessionCookie(domain: String, cookies: String) {
        val sessionCookie = getSessionCookie(domain)
        if (sessionCookie.isNullOrEmpty()) {
            CacheManager.putMemory("${domain}_session_cookie", cookies)
            return
        }
        mergeCookies(sessionCookie, cookies)?.let {
            CacheManager.putMemory("${domain}_session_cookie", it)
        }
    }

    fun mergeCookies(vararg cookies: String?): String? {
        return CookieStore.mapToCookie(mergeCookiesToMap(*cookies))
    }

    fun mergeCookiesToMap(vararg cookies: String?): MutableMap<String, String> {
        val maps = cookies.filterNotNull().map { CookieStore.cookieToMap(it) }
        if (maps.isEmpty()) return mutableMapOf()
        return maps.reduce { acc, cookieMap -> acc.apply { putAll(cookieMap) } }
    }

    fun List<Cookie>.getString() = buildString {
        this@getString.forEachIndexed { index, cookie ->
            if (index > 0) append("; ")
            append(cookie.name).append('=').append(cookie.value)
        }
    }
}
