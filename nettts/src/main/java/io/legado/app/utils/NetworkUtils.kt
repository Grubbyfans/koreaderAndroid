package io.legado.app.utils

import okhttp3.internal.publicsuffix.PublicSuffixDatabase
import java.net.URL
import java.util.BitSet

/**
 * 网络/URL 工具子集。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/utils/NetworkUtils.kt`（GPLv3）。
 * 裁剪：去掉联网状态检测、本机 IP 枚举（依赖 Android 网络服务与 hutool 校验器），
 * 保留 URL 拼接、域名归并、已编码判定等规则解析必需逻辑，语义与原版一致。
 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object NetworkUtils {

    private val notNeedEncodingQuery: BitSet by lazy {
        BitSet(256).apply {
            for (i in 'a'.code..'z'.code) set(i)
            for (i in 'A'.code..'Z'.code) set(i)
            for (i in '0'.code..'9'.code) set(i)
            for (char in "!$&()*+,-./:;=?@[\\]^_`{|}~") set(char.code)
        }
    }

    private val notNeedEncodingForm: BitSet by lazy {
        BitSet(256).apply {
            for (i in 'a'.code..'z'.code) set(i)
            for (i in 'A'.code..'Z'.code) set(i)
            for (i in '0'.code..'9'.code) set(i)
            for (char in "*-._") set(char.code)
        }
    }

    /**
     * 支持 JAVA URLEncoder 产出的字符串判定（空格转 '+'）。
     */
    fun encodedQuery(str: String): Boolean = isEncoded(str, notNeedEncodingQuery)

    fun encodedForm(str: String): Boolean = isEncoded(str, notNeedEncodingForm)

    private fun isEncoded(str: String, allow: BitSet): Boolean {
        var i = 0
        while (i < str.length) {
            val c = str[i]
            if (allow.get(c.code)) {
                i++
                continue
            }
            if (c == '%' && i + 2 < str.length) {
                val c1 = str[i + 1]
                val c2 = str[i + 2]
                if (isDigit16Char(c1) && isDigit16Char(c2)) {
                    i += 3
                    continue
                }
            }
            return false
        }
        return true
    }

    private fun isDigit16Char(c: Char): Boolean {
        return c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f'
    }

    fun getAbsoluteURL(baseURL: String?, relativePath: String): String {
        if (baseURL.isNullOrEmpty()) return relativePath.trim()
        var base: URL? = null
        try {
            base = URL(baseURL.substringBefore(","))
        } catch (_: Exception) {
        }
        val path = relativePath.trim()
        if (base == null) return path
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) return path
        if (path.startsWith("data:")) return path
        if (path.startsWith("javascript")) return ""
        return try {
            URL(base, relativePath).toString()
        } catch (_: Exception) {
            path
        }
    }

    fun getBaseUrl(url: String?): String? {
        url ?: return null
        val jsIndex = url.indexOf("@js:", ignoreCase = true)
        val urlToParse = if (jsIndex >= 0) url.substring(0, jsIndex) else url
        if (!urlToParse.startsWith("http://", true) && !urlToParse.startsWith("https://", true)) {
            return null
        }
        val parsed = runCatching { URL(urlToParse) }.getOrNull() ?: return null
        if (parsed.host.isEmpty()) return null
        return "${urlToParse.substringBefore("://")}://${parsed.authority}"
    }

    /** `https://www.example.com/a` → `example.com`；IP 直接返回自身。 */
    fun getSubDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return runCatching {
            val host = URL(baseUrl).host
            if (isIPAddress(host)) return host
            PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) ?: host
        }.getOrDefault(baseUrl)
    }

    fun getSubDomainOrNull(url: String): String? {
        val baseUrl = getBaseUrl(url) ?: return null
        return runCatching {
            val host = URL(baseUrl).host
            if (isIPAddress(host)) return host
            PublicSuffixDatabase.get().getEffectiveTldPlusOne(host) ?: host
        }.getOrDefault(null)
    }

    fun getDomain(url: String): String {
        val baseUrl = getBaseUrl(url) ?: return url
        return runCatching { URL(baseUrl).host }.getOrDefault(baseUrl)
    }

    /**
     * 去除 URL query 中的重复参数（保留最后一个值）。
     *
     * 一些规则（如用户提供的思必驰规则集）存在 `voiceId=xx&...&voiceId=yy` 的写法，
     * 部分 HTTP 服务端对这种重复 key 的容忍度很低（直接返回 400）。这里按"后声明覆盖先声明"
     * 的常规惯例合并，避免因此造成整批规则不可用。无重复参数的 URL 不会被改写。
     */
    fun dedupQueryParams(url: String): String {
        val index = url.indexOf('?')
        if (index < 0) return url
        val base = url.substring(0, index)
        val query = url.substring(index + 1)
        val hashIdx = query.indexOf('#')
        val queryPart = if (hashIdx < 0) query else query.substring(0, hashIdx)
        val fragment = if (hashIdx < 0) null else query.substring(hashIdx)
        val seen = LinkedHashMap<String, String?>()
        queryPart.split('&').forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            val (key, value) = if (eq < 0) pair to null else pair.substring(0, eq) to pair.substring(eq + 1)
            seen[key] = value
        }
        val rebuilt = seen.entries.joinToString("&") { (key, value) ->
            if (value == null) key else "$key=$value"
        }
        return buildString {
            append(base)
            if (rebuilt.isNotEmpty()) {
                append('?').append(rebuilt)
            }
            if (fragment != null) append(fragment)
        }
    }

    fun isIPAddress(input: String?): Boolean {
        if (input.isNullOrEmpty()) return false
        if (input.contains(":")) return true
        return Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(input)
    }
}
