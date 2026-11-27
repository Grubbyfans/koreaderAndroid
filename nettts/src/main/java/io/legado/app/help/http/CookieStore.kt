package io.legado.app.help.http

import io.legado.app.constant.AppPattern
import io.legado.app.help.http.CookieManager.mergeCookiesToMap
import io.legado.app.nettts.NetworkTtsLog
import io.legado.app.nettts.NetworkTtsRuntime
import io.legado.app.utils.NetworkUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * Cookie 存储。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/http/CookieStore.kt`（GPLv3）。
 * 裁剪：原版持久化到 Room 的 cookie 表；静读侧无对应表，改为内存 + 可选外部存储
 * （[NetworkTtsRuntime.cookieStore]，默认内存）。cookie 语义、域归并、超长裁剪与原版一致。
 */
object CookieStore {

    private val cache = ConcurrentHashMap<String, String>()

    private fun storageKey(domain: String, url: String): String {
        val key = NetworkUtils.getSubDomain(url)
        return key.ifEmpty { domain }
    }

    fun setCookie(url: String, cookie: String?) {
        if (url.isBlank()) return
        val domain = storageKey("", url)
        val value = cookie ?: ""
        cache[domain] = value
        NetworkTtsRuntime.cookieStore?.put(domain, value)
    }

    fun replaceCookie(url: String, cookie: String) {
        if (url.isBlank() || cookie.isBlank()) return
        setCookie(url, mergeCookieValues(getCookieNoSession(url), cookie))
    }

    private fun mergeCookieValues(oldCookie: String, cookie: String): String {
        if (oldCookie.isEmpty()) return cookie
        val cookieMap = cookieToMap(oldCookie)
        cookieMap.putAll(cookieToMap(cookie))
        return mapToCookie(cookieMap).orEmpty()
    }

    fun getCookie(url: String): String {
        val cookie = getCookieNoSession(url)
        val domain = storageKey("", url)
        val sessionCookie = CookieManager.getSessionCookie(domain)
        return mapToCookie(mergeCookiesToMap(cookie, sessionCookie)) ?: ""
    }

    fun getCookieNoSession(url: String): String {
        val domain = storageKey("", url)
        cache[domain]?.let { return it }
        val stored = NetworkTtsRuntime.cookieStore?.get(domain)
        if (stored != null) {
            cache[domain] = stored
            return stored
        }
        return ""
    }

    fun getKey(url: String, key: String): String = cookieToMap(getCookie(url))[key] ?: ""

    fun removeCookie(url: String) {
        val domain = storageKey("", url)
        cache.remove(domain)
        NetworkTtsRuntime.cookieStore?.remove(domain)
    }

    fun cookieToMap(cookie: String): MutableMap<String, String> {
        val cookieMap = mutableMapOf<String, String>()
        if (cookie.isBlank()) return cookieMap
        cookie.split(AppPattern.semicolonRegex).forEach { pair ->
            val pairs = pair.split(AppPattern.equalsRegex, limit = 2)
            if (pairs.size > 1) {
                val key = pairs[0].trim()
                val value = pairs[1].trim()
                if (value.isNotEmpty()) cookieMap[key] = value
            }
        }
        return cookieMap
    }

    fun mapToCookie(cookieMap: Map<String, String>?): String? {
        if (cookieMap.isNullOrEmpty()) return null
        return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun logFailure(message: String, throwable: Throwable) {
        NetworkTtsLog.put(message, throwable)
    }
}
