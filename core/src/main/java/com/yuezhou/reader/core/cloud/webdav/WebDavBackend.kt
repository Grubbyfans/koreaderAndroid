package com.yuezhou.reader.core.cloud.webdav

import com.yuezhou.reader.core.cloud.BackendCapabilities
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.cloud.CloudBackend
import com.yuezhou.reader.core.cloud.CloudEntry
import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.CloudStat
import com.yuezhou.reader.core.cloud.EntryType
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.cloud.ListResult
import com.yuezhou.reader.core.cloud.Outcome
import com.yuezhou.reader.core.cloud.Precondition
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * WebDAV 后端实现，对齐 BaiduPanSync `modules/webdav.lua`。
 *
 * - 认证：HTTP Basic。
 * - 禁止重定向（3xx 一律报 NETWORK）。
 * - 条件写入仅在 [conditionalVerified] 为 true 时启用（探测通过后设置）。
 * - 正文下载/上传流式落盘/读盘，小 JSON 走内存。
 *
 * 注意：本实现使用 OkHttp，底层 TLS 校验由系统证书链完成；如后续需指定 CA 文件，
 * 通过 [clientFactory] 注入自定义 OkHttpClient。
 */
class WebDavBackend private constructor(
    override val root: String,
    private val username: String,
    private val password: String,
    private val client: OkHttpClient,
    private val conditionalVerified: Boolean,
    private val conditionalDeleteVerified: Boolean,
) : CloudBackend {

    override val kind: BackendKind = BackendKind.WebDav
    override val backendId: String = "webdav:$root:$username"

    private fun err(code: ErrorCode, message: String, uncertain: Boolean = false): CloudException =
        CloudException(code, message, if (uncertain) Outcome.Uncertain else Outcome.Failed)

    override fun capabilities(): BackendCapabilities = BackendCapabilities(
        mkdir = true,
        delete = true,
        conditionalCreate = conditionalVerified,
        conditionalReplace = conditionalVerified,
        conditionalDelete = conditionalDeleteVerified,
    )

    override suspend fun ensureAuthorized() {
        // stat 根目录，凭据错误会映射为 AUTH。
        stat("")
    }

    // region 请求封装

    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        val writing = request.method == "PUT" || request.method == "DELETE" || request.method == "MKCOL"
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            val mapped = when (code) {
                401 -> err(ErrorCode.AUTH, "WebDAV 凭据错误")
                403 -> err(ErrorCode.FORBIDDEN, "WebDAV 越权")
                404 -> err(ErrorCode.NOT_FOUND, "WebDAV 资源不存在")
                409, 412 -> err(ErrorCode.CONFLICT, "WebDAV 条件冲突或目录已存在")
                405 -> err(ErrorCode.UNSUPPORTED, "WebDAV 方法不支持")
                507 -> err(ErrorCode.IO, "WebDAV 空间不足")
                in 300..399 -> err(ErrorCode.NETWORK, "WebDAV 禁止重定向，请配置最终根地址")
                else -> err(ErrorCode.NETWORK, "WebDAV HTTP $code", uncertain = writing && code >= 500)
            }
            throw mapped
        }
        return response
    }

    private fun url(path: String): String = root + DavPath.encode(path)

    private fun request(
        method: String,
        path: String,
        body: RequestBody? = null,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        if (!DavPath.safe(path)) throw err(ErrorCode.INVALID_INPUT, "路径必须位于同步根目录内")
        val builder = Request.Builder().url(url(path)).method(method, body)
        builder.header("Authorization", Credentials.basic(username, password))
        for ((k, v) in headers) builder.header(k, v)
        return execute(builder.build())
    }

    // endregion

    // region 条件写入

    private fun condition(pre: Precondition): Map<String, String> {
        if (pre == Precondition.Unconditional) return emptyMap()
        if (!conditionalVerified) throw err(ErrorCode.UNSUPPORTED, "服务器条件写入尚未验证")
        return when (pre) {
            is Precondition.Absent -> mapOf("If-None-Match" to "*")
            is Precondition.MatchEtag -> {
                if (pre.etag.matches(Regex("^\"[^\"\\u0000-\\u001f]+\"$"))) mapOf("If-Match" to pre.etag)
                else throw err(ErrorCode.INVALID_INPUT, "需要强 ETag；弱 ETag 不能用于 If-Match")
            }
            else -> emptyMap()
        }
    }

    // endregion

    // region stat

    private val STAT_BODY =
        """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"""

    override suspend fun stat(path: String): CloudStat {
        val response = request("PROPFIND", path, null, mapOf("Depth" to "0", "Content-Type" to "application/xml"))
        response.use { r ->
            val body = r.body?.string() ?: throw err(ErrorCode.INVALID_INPUT, "无效的 DAV stat 响应")
            if (r.code != 207) throw err(ErrorCode.INVALID_INPUT, "无效的 DAV stat 响应")
            val multistatus = DavXml.parse(body)
            val expectedPath = decode(url(path))
            for (resp in multistatus.responses) {
                val href = normalizeHref(resp.href)
                if (href == expectedPath) {
                    if (resp.status == null || !resp.status.contains("200")) {
                        throw err(ErrorCode.FORBIDDEN, "DAV stat 属性不可读")
                    }
                    if (resp.isCollection == null) throw err(ErrorCode.INVALID_INPUT, "DAV stat 缺少资源类型")
                    return CloudStat(
                        type = if (resp.isCollection) EntryType.Directory else EntryType.File,
                        size = resp.size ?: 0,
                        etag = resp.etag,
                        weakEtag = resp.etag?.startsWith("W/") == true,
                        modified = resp.modified,
                    )
                }
            }
            throw err(ErrorCode.INVALID_INPUT, "DAV stat 响应缺少目标")
        }
    }

    private fun normalizeHref(href: String): String {
        // 去掉可能的前缀 scheme/host，再百分号解码，归一到不含尾斜杠的相对路径。
        val uri = try {
            URI(href)
        } catch (e: Exception) {
            throw err(ErrorCode.FORBIDDEN, "DAV href 非法")
        }
        val raw = uri.rawPath ?: throw err(ErrorCode.FORBIDDEN, "DAV href 非法")
        val decoded = URLDecoder.decode(raw, Charsets.UTF_8).trimEnd('/')
        return decoded
    }

    private fun decode(url: String): String =
        URLDecoder.decode(url, Charsets.UTF_8).trimEnd('/')

    // endregion

    // region list

    override suspend fun list(path: String, continuation: String?): ListResult {
        if (continuation != null) throw err(ErrorCode.UNSUPPORTED, "此 WebDAV 枚举不支持续页")
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/></d:prop></d:propfind>"""
        val response = request("PROPFIND", path, null, mapOf("Depth" to "1", "Content-Type" to "application/xml"))
        response.use { r ->
            val xml = r.body?.string() ?: throw err(ErrorCode.INVALID_INPUT, "无效的 DAV 多状态响应")
            if (r.code != 207) throw err(ErrorCode.INVALID_INPUT, "无效的 DAV 多状态响应")
            val multistatus = DavXml.parse(xml)
            val entries = mutableListOf<CloudEntry>()
            val base = decode(root)
            val prefix = if (path.isEmpty()) "" else path.trimEnd('/') + "/"
            for (resp in multistatus.responses) {
                val rel = relative(resp.href, base)
                if (rel == null || !DavPath.safeListing(rel)) throw err(ErrorCode.FORBIDDEN, "DAV 条目路径无效")
                if (rel == path || rel == prefix) continue
                // 只收直接子项
                if (!rel.startsWith(prefix) || rel.substring(prefix.length).contains('/')) {
                    if (rel == path) continue
                    throw err(ErrorCode.FORBIDDEN, "DAV 返回非直接子项")
                }
                if (resp.status == null || !resp.status.contains("200")) {
                    throw err(ErrorCode.FORBIDDEN, "DAV 条目属性读取失败，枚举不完整")
                }
                entries.add(
                    CloudEntry(
                        path = rel,
                        type = if (resp.isCollection == true) EntryType.Directory else EntryType.File,
                        size = resp.size ?: 0,
                        etag = resp.etag,
                        weakEtag = resp.etag?.startsWith("W/") == true,
                        version = resp.etag,
                    )
                )
            }
            return ListResult(entries)
        }
    }

    private fun relative(href: String, base: String): String? {
        val decoded = try {
            URI(href).rawPath?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: return null
        } catch (e: Exception) {
            return null
        }
        if (!decoded.startsWith(base)) return null
        val rel = decoded.substring(base.length).removePrefix("/")
        return rel
    }

    // endregion

    // region download

    override suspend fun downloadBytes(path: String, maxBytes: Int): ByteArray {
        val response = request("GET", path)
        response.use { r ->
            val bytes = r.body?.bytes() ?: throw err(ErrorCode.NETWORK, "WebDAV 下载无响应体")
            if (bytes.size > maxBytes) throw err(ErrorCode.INVALID_INPUT, "下载内容超过 $maxBytes 字节上限")
            return bytes
        }
    }

    override suspend fun downloadTo(
        path: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val response = request("GET", path)
        response.use { r ->
            val source = r.body?.source() ?: throw err(ErrorCode.NETWORK, "WebDAV 下载无响应体")
            destination.outputStream().use { out ->
                var received = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buffer)
                    if (n == -1) break
                    received += n
                    if (received > maxBytes) throw err(ErrorCode.INVALID_INPUT, "下载内容超过 $maxBytes 字节上限")
                    out.write(buffer, 0, n)
                    onProgress?.invoke(received, r.body?.contentLength() ?: -1)
                }
            }
        }
    }

    // endregion

    // region upload

    override suspend fun uploadSmallJson(path: String, raw: String, precondition: Precondition) {
        val max = if (path == "library.json") 4 * 1024 * 1024 else 65536
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > max) throw err(ErrorCode.INVALID_INPUT, "JSON 超过大小限制")
        val headers = condition(precondition) + ("Content-Type" to "application/json")
        request("PUT", path, RequestBody.create(JSON_MEDIA_TYPE, bytes), headers).close()
    }

    override suspend fun upload(
        path: String,
        localFile: File,
        precondition: Precondition,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        if (!localFile.isFile) throw err(ErrorCode.IO, "上传源文件不可读")
        val headers = condition(precondition) + ("Content-Type" to "application/epub+zip")
        val body = object : RequestBody() {
            override fun contentType() = EPUB_MEDIA_TYPE
            override fun contentLength(): Long = localFile.length()
            override fun writeTo(sink: okio.BufferedSink) {
                var sent = 0L
                localFile.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        sink.write(buffer, 0, n)
                        sent += n
                        onProgress?.invoke(sent, localFile.length())
                    }
                }
            }
        }
        request("PUT", path, body, headers).close()
    }

    // endregion

    // region mkdir / delete

    override suspend fun mkdir(path: String) {
        val response = request("MKCOL", path)
        response.close()
    }

    override suspend fun delete(path: String, precondition: Precondition) {
        if (precondition == Precondition.Unconditional) {
            val validTarget = path.matches(Regex("^books/.+\\.[Ee][Pp][Uu][Bb]$")) ||
                path.matches(Regex("^progress/.+\\.json$")) ||
                path.matches(Regex("^covers/.+\\.json$"))
            if (!validTarget) throw err(ErrorCode.FORBIDDEN, "删除路径无效")
            request("DELETE", path).close()
            return
        }
        // 条件删除：需验证能力 + 强 ETag
        if (!conditionalDeleteVerified) throw err(ErrorCode.UNSUPPORTED, "条件删除尚未验证，拒绝删除书籍文件")
        val headers = condition(precondition)
        request("DELETE", path, null, headers).close()
    }

    // endregion

    companion object {

        private val JSON_MEDIA_TYPE = okhttp3.MediaType.parse("application/json; charset=utf-8")!!
        private val EPUB_MEDIA_TYPE = okhttp3.MediaType.parse("application/epub+zip")!!

        /** 构建 WebDAV 后端。条件写入能力默认关闭，探测通过后由调用方开启。 */
        fun create(
            rootUrl: String,
            username: String,
            password: String,
            conditionalVerified: Boolean = false,
            conditionalDeleteVerified: Boolean = false,
            clientFactory: (() -> OkHttpClient)? = null,
        ): WebDavBackend {
            val root = normalizeRoot(rootUrl)
                ?: throw CloudException(ErrorCode.INVALID_INPUT, "WebDAV 根目录 URL 无效")
            if (password.any { it.code < 0x20 || it.code == 0x7f } || username.contains(':')) {
                throw CloudException(ErrorCode.INVALID_INPUT, "WebDAV 凭据格式无效")
            }
            val client = clientFactory?.invoke() ?: defaultClient()
            return WebDavBackend(root, username, password, client, conditionalVerified, conditionalDeleteVerified)
        }

        private fun normalizeRoot(url: String): String? {
            val trimmed = url.trimEnd('/')
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
            return try {
                URI(trimmed).let { if (it.host == null) null else trimmed }
            } catch (e: Exception) {
                null
            }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
