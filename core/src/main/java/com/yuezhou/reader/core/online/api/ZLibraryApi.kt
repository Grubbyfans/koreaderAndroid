package com.yuezhou.reader.core.online.api

import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.model.BookPage
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.core.online.model.SearchQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Z-Library 网络层：按参考项目 b7eacbab 的接口语义用 Kotlin 独立实现，不复制 Lua 代码。
 *
 * 安全约束（方案 §6）：
 * - 只使用配置中通过 HTTPS 校验的主机；不自动跟随重定向，3xx 的 Location 必须仍是 HTTPS 且主机在可信列表内。
 * - Cookie 仅在显式调用时随请求发送，绝不写入配置、日志或随配置导出。
 * - 错误按认证/额度/限流/网络分类，额度与认证错误不盲目重试。
 */
class ZLibraryApi(
    private val config: ZLibraryConfig,
    private val client: OkHttpClient = buildClient(config),
) {
    /** 登录会话：cookies 为 "k=v; k2=v2"，由 reader 层加密持久化。 */
    data class AuthSession(val cookies: String, val userId: String? = null)

    /** 下载额度（按日）。null 表示服务端未提供。 */
    data class Quota(val downloadsToday: Int? = null, val downloadsLimit: Int? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private val allowedHosts: Set<String> = config.servers.baseUrls.mapNotNull { runCatching { URL(it).host }.getOrNull() }.toSet()
    /** 当前生效地址：由配置仓库同步为自动选中项或手动选中项；未选择时给出明确错误而非下标越界。 */
    private val baseUrl: String get() = config.servers.baseUrls.firstOrNull()?.trimEnd('/')
        ?: throw com.yuezhou.reader.core.online.model.ProviderException(
            com.yuezhou.reader.core.online.model.ProviderErrorCode.NOT_CONFIGURED,
            if (config.servers.mode.equals("manual", ignoreCase = true)) "尚未手动选择服务器" else "暂无可用的 Z-Library 服务器",
        )

    suspend fun login(username: String, password: String): Result<AuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            val errors = com.yuezhou.reader.core.online.config.ZLibraryConfigValidator.validate(config)
            require(errors.isEmpty()) { errors.joinToString("；") }
            val primary = execute(buildLoginRequest(config.endpoints.login, username, password))
            val element = parseJson(primary)

            if (successFlag(element) == true) {
                return@runCatching sessionFrom(primary, element)
            }
            // 仅「凭证被拒绝」才回退备用登录端点；限流/封禁不回退，避免放大请求。
            val message = textField(element, "message").orEmpty()
            if (isRateLimited(message) || isQuotaError(message)) {
                throw classify(primary.code, message)
            }
            val fallback = execute(buildLoginRequest(config.endpoints.loginFallback, username, password))
            val fallbackElement = parseJson(fallback)
            if (successFlag(fallbackElement) != true) {
                throw classify(fallback.code, textField(fallbackElement, "message").orEmpty())
            }
            sessionFrom(fallback, fallbackElement)
        }
    }

    suspend fun search(query: SearchQuery, session: AuthSession? = null): Result<BookPage> = withContext(Dispatchers.IO) {
        runCatching {
            val page = query.cursor?.toIntOrNull() ?: 1
            val response = execute(buildSearchRequest(query, page), session)
            val element = parseJson(response)
            val books = findBooks(element)
            BookPage(
                items = books.map { it.toOnlineBook() },
                nextCursor = if (books.isEmpty()) null else (page + 1).toString(),
                hasMore = books.isNotEmpty() && books.size >= query.pageSize,
                totalCount = findInt(element, "total"),
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 搜索被服务端拒绝时的降级入口：官方「最热门」列表仍可用（参考实现 b7eacbab）。
     * 该端点只提供固定一页，因此不生成下一页游标。
     */
    suspend fun getMostPopular(
        pageSize: Int = 30,
        session: AuthSession? = null,
    ): Result<BookPage> = withContext(Dispatchers.IO) {
        runCatching {
            val limit = pageSize.coerceIn(1, 30)
            val url = baseUrl + config.endpoints.mostPopular.path + "?page=1&limit=$limit"
            val response = execute(Request.Builder().url(url).get().build(), session)
            val element = parseJson(response)
            val books = findBooks(element)
            BookPage(
                items = books.map { it.toOnlineBook() },
                nextCursor = null,
                hasMore = false,
                totalCount = findInt(element, "total"),
                fetchedAt = System.currentTimeMillis(),
                notice = "Z-Library 服务端暂未开放搜索，当前展示官方「最热门」书单；登录、详情与下载不受影响。",
            )
        }
    }

    suspend fun getBookDetail(id: String, hash: String, session: AuthSession? = null): Result<OnlineBook> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = config.endpoints.bookDetail.path
                    .replace("{id}", id).replace("{hash}", hash)
                val response = execute(buildGetRequest(path), session)
                val element = parseJson(response)
                findBookObject(element)?.toOnlineBook()
                    ?: throw ProviderException(ProviderErrorCode.NOT_FOUND, "未找到书籍详情")
            }
        }

    suspend fun getDownloadLink(id: String, hash: String, session: AuthSession): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = config.endpoints.downloadLink.path
                    .replace("{id}", id).replace("{hash}", hash)
                val response = execute(buildGetRequest(path), session)
                val element = parseJson(response)
                val link = findString(element, "downloadLink")
                    ?: throw ProviderException(ProviderErrorCode.NOT_FOUND, "服务端未返回下载链接")
                val url = runCatching { URL(link) }.getOrNull()
                    ?: throw ProviderException(ProviderErrorCode.NETWORK, "下载链接格式无效")
                // 签名下载链接本身是凭证等价物：只允许 HTTPS，且不把 Cookie 发送给该主机。
                if (url.protocol != "https") {
                    throw ProviderException(ProviderErrorCode.NETWORK, "下载链接必须使用 HTTPS")
                }
                link
            }
        }

    /**
     * 下载封面字节：不携带 Cookie、不跟随重定向、限制大小与内容类型。
     * 封面 URL 即使来自可信书源，也只作为无凭证的静态资源处理。
     */
    suspend fun fetchCover(url: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val target = runCatching { URL(url) }.getOrNull()
                ?: throw ProviderException(ProviderErrorCode.NETWORK, "封面地址格式无效")
            if (target.protocol != "https") {
                throw ProviderException(ProviderErrorCode.NETWORK, "封面必须使用 HTTPS")
            }
            val response = execute(Request.Builder().url(url).get().build())
            verifyRedirect(response)
            response.use { resp ->
                if (resp.code !in 200..299) throw classify(resp.code, "")
                val body = resp.body
                val type = body.contentType()?.toString().orEmpty()
                if (!type.startsWith("image/")) {
                    throw ProviderException(ProviderErrorCode.NETWORK, "封面地址返回的不是图片")
                }
                val declared = body.contentLength()
                if (declared > MAX_COVER_BYTES) {
                    throw ProviderException(ProviderErrorCode.NETWORK, "封面文件过大")
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                body.byteStream().use { stream ->
                    var total = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_COVER_BYTES) {
                            throw ProviderException(ProviderErrorCode.NETWORK, "封面文件过大")
                        }
                        out.write(buffer, 0, read)
                    }
                }
                out.toByteArray()
            }
        }
    }

    /**
     * 按服务端签名链接流式下载到指定临时文件。
     *
     * 签名链接本身是凭证等价物：不附加 Cookie，重定向只允许 HTTPS，且最多跟随 3 次；
     * 目标主机由服务端签名决定，不向它发送任何账号凭证。调用方负责取消与临时文件清理。
     */
    suspend fun downloadToFile(
        url: String,
        target: File,
        expectedSize: Long? = null,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            var currentUrl = url
            var response = openDownload(currentUrl)
            var hops = 0
            while (response.code in 300..399) {
                val location = response.header("Location")
                response.close()
                if (hops++ >= 3 || location.isNullOrBlank()) {
                    throw ProviderException(ProviderErrorCode.NETWORK, "下载重定向次数过多或缺少目标")
                }
                val next = runCatching { URL(URL(currentUrl), location).toString() }.getOrNull()
                    ?: throw ProviderException(ProviderErrorCode.NETWORK, "下载重定向地址无效")
                if (URL(next).protocol != "https") {
                    throw ProviderException(ProviderErrorCode.NETWORK, "下载重定向必须使用 HTTPS")
                }
                currentUrl = next
                response = openDownload(currentUrl)
            }
            response.use { resp ->
                if (resp.code !in 200..299) throw classify(resp.code, "下载失败（HTTP ${resp.code}）")
                val body = resp.body
                val total = body.contentLength().takeIf { it > 0 } ?: expectedSize?.takeIf { it > 0 }
                if (total != null && total > MAX_DOWNLOAD_BYTES) {
                    throw ProviderException(ProviderErrorCode.NETWORK, "文件过大，已拒绝下载")
                }
                var received = 0L
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            received += read
                            if (received > MAX_DOWNLOAD_BYTES) {
                                throw ProviderException(ProviderErrorCode.NETWORK, "文件过大，已拒绝下载")
                            }
                            out.write(buffer, 0, read)
                            onProgress(received, total)
                        }
                    }
                }
                if (total != null && received != total) {
                    throw ProviderException(ProviderErrorCode.NETWORK, "下载文件大小与服务端声明不符")
                }
                received
            }
        }
    }

    private fun openDownload(url: String): Response {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: throw ProviderException(ProviderErrorCode.NETWORK, "下载地址格式无效")
        if (parsed.protocol != "https") {
            throw ProviderException(ProviderErrorCode.NETWORK, "下载地址必须使用 HTTPS")
        }
        return client.newCall(Request.Builder().url(url).get().build()).execute()
    }

    suspend fun getQuota(session: AuthSession): Result<Quota> = withContext(Dispatchers.IO) {
        runCatching {
            val response = execute(buildGetRequest(config.endpoints.profile.path), session)
            val element = parseJson(response)
            Quota(
                downloadsToday = findInt(element, "downloads_today"),
                downloadsLimit = findInt(element, "downloads_limit"),
            )
        }
    }

    /** 动态域名发现：只返回候选，不自动信任；须经用户确认并写入配置后才可使用。 */
    suspend fun discoverDomains(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = execute(buildGetRequest(config.endpoints.domains.path))
            val element = parseJson(response)
            val domains = mutableListOf<String>()
            fun collect(node: JsonElement, depth: Int) {
                if (depth > 6) return
                when (node) {
                    is JsonPrimitive -> {
                        val value = node.contentOrNull ?: return
                        if (value.startsWith("https://")) domains.add(value.trimEnd('/'))
                    }
                    is JsonArray -> node.forEach { collect(it, depth + 1) }
                    is JsonObject -> node.values.forEach { collect(it, depth + 1) }
                }
            }
            collect(element, 0)
            domains.distinct()
        }
    }

    /** 供单测验证端点与表单构造。字段语义按固定 commit b7eacbab 的实际请求体修正。 */
    internal fun buildSearchRequest(query: SearchQuery, page: Int = 1): Request {
        val form = FormBody.Builder()
            .add("message", query.keyword)
            .add("page", page.toString())
            .add("limit", query.pageSize.coerceIn(1, 30).toString())
            // 第一版只检索 EPUB 图书；文章模式会忽略格式过滤，故不发送 content_type=article。
            .add("extensions[0]", (query.format ?: "epub").uppercase())
            .add("order", query.sort)
        query.language?.takeIf { it.isNotBlank() }?.let { form.add("languages[0]", it) }
        return Request.Builder()
            .url(baseUrl + config.endpoints.search.path)
            .post(form.build())
            .build()
    }

    internal fun buildLoginRequest(endpoint: ZLibraryConfig.Endpoint, username: String, password: String): Request {
        val form = FormBody.Builder()
            .add("action", "login")
            .add("gg_json_mode", "1")
            .add("email", username)
            .add("password", password)
            .build()
        val method = if (endpoint.method.equals("POST", ignoreCase = true)) "POST" else "GET"
        return Request.Builder().url(baseUrl + endpoint.path).method(method, if (method == "POST") form else null).build()
    }

    internal fun buildGetRequest(path: String): Request = Request.Builder().url(baseUrl + path).get().build()

    internal fun verifyRedirect(response: Response) {
        if (response.code !in 300..399) return
        val location = response.header("Location")
        val target = location?.let { runCatching { URL(it) }.getOrNull() }
            ?: throw ProviderException(ProviderErrorCode.NETWORK, "服务器重定向缺少有效 Location")
        if (target.protocol != "https" || target.host !in allowedHosts) {
            throw ProviderException(ProviderErrorCode.NETWORK, "重定向目标不在可信主机列表内，已阻止")
        }
    }

    internal fun classify(code: Int, message: String): ProviderException = when {
        isSearchRefused(message) -> ProviderException(
            ProviderErrorCode.SERVER_REJECTED,
            "Z-Library 服务端拒绝搜索：${message}",
        )
        isQuotaError(message) -> ProviderException(ProviderErrorCode.QUOTA, message)
        isRateLimited(message) -> ProviderException(ProviderErrorCode.RATE_LIMITED, message)
        code == 401 || code == 403 -> ProviderException(ProviderErrorCode.AUTH, "登录已失效，请重新登录")
        code == 429 -> ProviderException(ProviderErrorCode.RATE_LIMITED, "请求过于频繁，请稍后再试")
        code in 500..599 -> ProviderException(ProviderErrorCode.NETWORK, "服务端错误（HTTP $code）")
        else -> ProviderException(ProviderErrorCode.UNKNOWN, message.ifBlank { "请求失败（HTTP $code）" })
    }

    private fun execute(request: Request, session: AuthSession? = null): Response {
        val requestWithCookie = if (session?.cookies.isNullOrBlank()) request
        else request.newBuilder().header("Cookie", session.cookies).build()
        val response = client.newCall(requestWithCookie).execute()
        verifyRedirect(response)
        return response
    }

    private fun parseJson(response: Response): JsonObject {
        val body = response.body.string()
        if (response.code !in 200..299) throw classify(response.code, messageFromBody(body))
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull()
            ?: throw ProviderException(ProviderErrorCode.NETWORK, "服务端返回了无法解析的内容")
        return element as? JsonObject
            ?: throw ProviderException(ProviderErrorCode.NETWORK, "服务端返回的数据格式无效")
    }

    private fun messageFromBody(body: String): String {
        val obj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return "请求失败"
        // 服务端拒绝搜索时返回 {"success":0,"error":"Access denied"/"拒绝访问"}，没有 message 字段。
        return textField(obj, "message") ?: textField(obj, "error") ?: "请求失败"
    }

    private fun sessionFrom(response: Response, element: JsonObject): AuthSession {
        val cookies = response.headers("Set-Cookie")
            .mapNotNull { it.substringBefore(';').takeIf { part -> part.contains('=') } }
            .joinToString("; ")
        val userId = textField(element, "user_id") ?: findString(element, "userid")
        return AuthSession(cookies, userId)
    }

    private fun successFlag(element: JsonObject): Boolean? = when (val value = element["success"]) {
        is JsonPrimitive -> when (value.contentOrNull) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
        else -> null
    }

    private fun isRateLimited(message: String): Boolean =
        message.contains("rate", true) || message.contains("频繁", true) || message.contains("too many", true)

    private fun isQuotaError(message: String): Boolean =
        message.contains("quota", true) || message.contains("limit", true) || message.contains("额度", true)

    /** 服务端自 2026-11-23 起对搜索统一返回 400 Access denied，与会话/请求格式无关。 */
    private fun isSearchRefused(message: String): Boolean =
        message.contains("access denied", true) || message.contains("拒绝访问", true)

    private fun findBooks(element: JsonObject): List<JsonObject> {
        val candidate = element["books"] as? JsonArray
            ?: (element["data"] as? JsonObject)?.get("books") as? JsonArray
            ?: return emptyList()
        return candidate.mapNotNull { it as? JsonObject }.filter { it.containsKey("id") || it.containsKey("title") }
    }

    private fun findBookObject(element: JsonObject): JsonObject? {
        (element["book"] as? JsonObject)?.let { return it }
        ((element["data"] as? JsonObject)?.get("book") as? JsonObject)?.let { return it }
        return element.takeIf { it.containsKey("title") && (it.containsKey("id") || it.containsKey("hash")) }
    }

    private fun JsonObject.toOnlineBook(): OnlineBook {
        val id = textField(this, "id").orEmpty()
        val hash = textField(this, "hash").orEmpty()
        val title = textField(this, "title").orElse("未命名书籍")
        val format = (textField(this, "format") ?: "epub").lowercase()
        val authorText = textField(this, "author").orEmpty()
        val authors = (this["author"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: authorText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val cover = textField(this, "cover")?.takeIf { it.startsWith("https://") }
        val year = (textField(this, "year") ?: textField(this, "publish_year"))?.toIntOrNull()
        val size = (this["filesize"] as? JsonPrimitive)?.longOrNull
            ?: textField(this, "filesize")?.toLongOrNull()
        return OnlineBook(
            providerId = ZLibraryConfig.PROVIDER_ID,
            remoteId = listOf(id, hash).filter { it.isNotEmpty() }.joinToString(":"),
            title = title,
            fileName = sanitizeFileName(title) + "." + format,
            format = format,
            authors = authors,
            coverUrl = cover,
            description = textField(this, "description"),
            publisher = textField(this, "publisher"),
            publishYear = year,
            isbn = textField(this, "identifier"),
            language = textField(this, "language"),
            fileSize = size,
        )
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().take(120).ifEmpty { "book" }

    private fun textField(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

    private fun findString(obj: JsonObject, key: String): String? {
        textField(obj, key)?.let { return it }
        obj.values.forEach { value ->
            when (value) {
                is JsonObject -> findString(value, key)?.let { return it }
                is JsonArray -> value.forEach { item ->
                    (item as? JsonObject)?.let { findString(it, key)?.let { found -> return found } }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun findInt(obj: JsonObject, key: String): Int? {
        (obj[key] as? JsonPrimitive)?.let { value ->
            value.intOrNull?.let { return it }
            value.contentOrNull?.toIntOrNull()?.let { return it }
        }
        obj.values.forEach { value ->
            when (value) {
                is JsonObject -> findInt(value, key)?.let { return it }
                is JsonArray -> value.forEach { item ->
                    (item as? JsonObject)?.let { findInt(it, key)?.let { found -> return found } }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun String?.orElse(default: String): String = this ?: default

    companion object {
        /** 封面装饰资源限制单张 2 MiB；正文下载上限 1 GiB，与现有云端下载一致。 */
        const val MAX_COVER_BYTES = 2L * 1024 * 1024
        const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024

        fun buildClient(config: ZLibraryConfig): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(config.network.connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(config.network.readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        /** 仅供单测构造假响应。 */
        internal fun fakeBody(text: String): ResponseBody =
            text.toResponseBody("application/json".toMediaType())
    }
}
