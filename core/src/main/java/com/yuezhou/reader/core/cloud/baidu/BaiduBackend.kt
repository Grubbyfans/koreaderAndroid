package com.yuezhou.reader.core.cloud.baidu

import com.yuezhou.reader.core.cloud.BackendCapabilities
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.cloud.CloudBackend
import com.yuezhou.reader.core.cloud.CloudEntry
import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.CloudStat
import com.yuezhou.reader.core.cloud.EntryType
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.cloud.ListResult
import com.yuezhou.reader.core.cloud.Precondition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 百度网盘后端实现，对齐 BaiduPanSync `modules/baidu_api.lua` + `baidu_upload_plan.lua`。
 *
 * - 授权：OAuth 2.0，token 过期判定 `now >= expires_at - 30`。
 * - 枚举：`/xpan/file?method=list`，每页 1000，start 为 1000 的倍数。
 * - 上传：预创建 → locateupload 动态域名 → 4MiB 分片 superfile2 → create 提交；支持秒传。
 * - 下载：filemetas 取动态链接，进程内 7 小时缓存，跟随重定向，stale 重试。
 * - 能力：仅 mkdir/delete；无条件写入；不做整书回读校验。
 */
class BaiduBackend private constructor(
    override val root: String,
    private val client: OkHttpClient,
    private val token: String,
    private val expiresAt: Long,
    private val userId: String?,
    private val now: () -> Long,
) : CloudBackend {

    override val kind: BackendKind = BackendKind.Baidu
    override val backendId: String = "baidu:$root:$userId"

    private val http = BaiduHttp(client, token)

    // 下载链接缓存：path -> (dlink, expiresAt)
    private data class CachedLink(val dlink: String, val expiresAt: Long, val fsId: String, val size: Long)
    private val linkCache = mutableMapOf<String, CachedLink>()

    private fun fail(code: ErrorCode, message: String) = CloudException(code, message)

    override fun capabilities(): BackendCapabilities = BackendCapabilities(
        mkdir = true,
        delete = true,
        conditionalCreate = false,
        conditionalReplace = false,
        conditionalDelete = false,
        move = false,
        atomicPublish = false,
        optimisticVersions = false,
        verifyUploadedContent = false,
    )

    private fun authorized() {
        if (token.isEmpty() || now() >= expiresAt - 30) {
            throw fail(ErrorCode.AUTH, "百度授权缺失或已过期，请重新授权")
        }
    }

    override suspend fun ensureAuthorized() {
        authorized()
        val v = http.request("https://pan.baidu.com/rest/2.0/xpan/nas", params = mapOf("method" to "uinfo"))
        val uk = BaiduJson.strField(v, "uk")
        if (uk == null || !uk.matches(Regex("^[1-9]\\d*$")) || uk.length > 24) {
            throw fail(ErrorCode.INVALID_INPUT, "百度未返回可信的稳定用户标识")
        }
        if (userId != null && uk != userId) {
            throw fail(ErrorCode.AUTH, "百度账户身份已变化，请重新授权")
        }
    }

    // region 路径

    private fun path(relative: String): String {
        if (relative.isEmpty()) return root
        if (relative[0] == '/' || relative.contains("//")) {
            throw fail(ErrorCode.INVALID_INPUT, "百度路径必须在应用目录内")
        }
        if (relative.any { it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            throw fail(ErrorCode.INVALID_INPUT, "百度路径必须在应用目录内")
        }
        for (part in relative.split('/')) {
            if (part == "." || part == "..") throw fail(ErrorCode.INVALID_INPUT, "百度路径不能包含上级目录")
        }
        return "$root/" + relative.trimEnd('/')
    }

    // endregion

    // region list / stat

    override suspend fun list(path: String, continuation: String?): ListResult {
        authorized()
        val dir = path(path)
        val start = continuation?.toLongOrNull() ?: 0L
        if (start < 0 || start % 1000 != 0L || start > 100000) {
            throw fail(ErrorCode.INVALID_INPUT, "百度枚举页码无效")
        }
        val v = http.request(
            "https://pan.baidu.com/rest/2.0/xpan/file",
            params = mapOf("method" to "list", "dir" to dir, "start" to start.toString(), "limit" to "1000", "order" to "name"),
        )
        val list = v["list"] as? JsonArray ?: throw fail(ErrorCode.INVALID_INPUT, "百度枚举结果无效")
        if (list.size > 1000) throw fail(ErrorCode.INVALID_INPUT, "百度枚举结果无效")

        val entries = mutableListOf<CloudEntry>()
        for (item in list) {
            val obj = item as? JsonObject ?: throw fail(ErrorCode.INVALID_INPUT, "百度返回了无效条目")
            val p = BaiduJson.strField(obj, "path") ?: throw fail(ErrorCode.INVALID_INPUT, "百度返回了无效条目")
            val isdir = BaiduJson.longField(obj, "isdir")
            val fsId = BaiduJson.strField(obj, "fs_id")
            val size = BaiduJson.longField(obj, "size")
            if (!p.startsWith("$dir/") || p.substring(dir.length + 1).contains('/')) {
                throw fail(ErrorCode.INVALID_INPUT, "百度返回了目录外或无效的条目")
            }
            if (isdir == null || (isdir != 0L && isdir != 1L)) throw fail(ErrorCode.INVALID_INPUT, "百度返回了无效条目")
            if (fsId == null || !fsId.matches(Regex("^\\d+$"))) throw fail(ErrorCode.INVALID_INPUT, "百度返回了无效条目")
            if (size == null || size < 0) throw fail(ErrorCode.INVALID_INPUT, "百度返回了无效条目")

            val md5 = BaiduJson.strField(obj, "md5")
            val mtime = BaiduJson.longField(obj, "server_mtime") ?: 0L
            val version = "[$fsId,$size,$mtime,$md5]"
            entries.add(
                CloudEntry(
                    path = p.substring(root.length + 1),
                    type = if (isdir == 1L) EntryType.Directory else EntryType.File,
                    size = size,
                    fsId = fsId,
                    md5 = md5,
                    version = version,
                )
            )
        }
        val hasMore = list.size == 1000
        return ListResult(entries, if (hasMore) (start + 1000).toString() else null)
    }

    override suspend fun stat(path: String): CloudStat {
        authorized()
        val full = path(path)
        if (full == root) {
            return CloudStat(EntryType.Directory)
        }
        val clean = path.trimEnd('/')
        val parent = clean.substringBeforeLast('/', "").let { if (it == clean) "" else it }
        var page: String? = null
        while (true) {
            val r = list(parent, page)
            for (e in r.entries) if (e.path == clean) {
                return CloudStat(e.type, e.size, e.etag, e.weakEtag)
            }
            page = r.continuation ?: break
        }
        throw fail(ErrorCode.NOT_FOUND, "百度应用目录中不存在该条目")
    }

    // endregion

    // region mkdir

    override suspend fun mkdir(path: String) {
        val v = http.request(
            "https://pan.baidu.com/rest/2.0/xpan/file",
            method = "POST",
            body = BaiduForm.form(mapOf("path" to path(path), "isdir" to "1", "rtype" to "0")),
            writing = true,
            params = mapOf("method" to "create"),
        )
        val retPath = BaiduJson.strField(v, "path")
        if (retPath != path(path)) throw fail(ErrorCode.UNCERTAIN, "百度目录创建结果未确认，请重新查询")
    }

    // endregion

    // region upload

    private fun md5Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun uploadHost(uploadid: String, targetPath: String): String {
        val v = http.request(
            "https://d.pcs.baidu.com/rest/2.0/pcs/file",
            params = mapOf(
                "method" to "locateupload",
                "appid" to "250528",
                "path" to targetPath,
                "uploadid" to uploadid,
                "upload_version" to "2.0",
            ),
        )
        val servers = v["servers"] as? JsonArray ?: JsonArray(emptyList())
        for (s in servers) {
            val obj = s as? JsonObject ?: continue
            val host = BaiduJson.strField(obj, "server") ?: continue
            if (host.matches(Regex("^https://[\\w.\\-]+\\.pcs\\.baidu\\.com/?$"))) {
                return host.trimEnd('/')
            }
        }
        throw fail(ErrorCode.TLS, "百度未返回可信的 HTTPS 上传域名")
    }

    /** 预创建。返回 (uploadid, blockList(待传分片序号), returnType, targetPath)。 */
    private data class PreCreate(val uploadid: String, val blockList: List<Int>, val returnType: Int, val targetPath: String)

    private fun precreate(targetPath: String, size: Long, blocks: List<String>): PreCreate {
        val blockListJson = buildJsonArray { blocks.forEach { add(JsonPrimitive(it)) } }.toString()
        val v = http.request(
            "https://pan.baidu.com/rest/2.0/xpan/file",
            method = "POST",
            body = BaiduForm.form(
                mapOf(
                    "path" to targetPath, "size" to size.toString(), "isdir" to "0",
                    "autoinit" to "1", "rtype" to "3", "block_list" to blockListJson,
                )
            ),
            writing = true,
            params = mapOf("method" to "precreate"),
        )
        val uploadid = BaiduJson.strField(v, "uploadid")
        val blockListEl = v["block_list"] as? JsonArray
        val returnType = BaiduJson.longField(v, "return_type")?.toInt()
        if (uploadid.isNullOrEmpty() || blockListEl == null) throw fail(ErrorCode.INVALID_INPUT, "百度预创建结果无效")
        if (returnType != 1 && returnType != 2) throw fail(ErrorCode.INVALID_INPUT, "百度预创建状态无效")

        var blockList = blockListEl.mapNotNull { BaiduJson.asLong(it)?.toInt() }
        if (returnType == 1 && blockList.isEmpty()) blockList = listOf(0)
        val seen = mutableSetOf<Int>()
        for (idx in blockList) {
            if (idx < 0 || idx >= blocks.size || !seen.add(idx)) {
                throw fail(ErrorCode.INVALID_INPUT, "百度返回无效或重复的分片序号")
            }
        }
        return PreCreate(uploadid, blockList, returnType, targetPath)
    }

    private fun commit(targetPath: String, size: Long, uploadid: String, blocks: List<String>) {
        val v = http.request(
            "https://pan.baidu.com/rest/2.0/xpan/file",
            method = "POST",
            body = BaiduForm.form(
                mapOf(
                    "path" to targetPath, "size" to size.toString(), "isdir" to "0",
                    "uploadid" to uploadid, "rtype" to "3",
                    "block_list" to buildJsonArray { blocks.forEach { add(JsonPrimitive(it)) } }.toString(),
                )
            ),
            writing = true,
            params = mapOf("method" to "create"),
        )
        val retPath = BaiduJson.strField(v, "path")
        val retSize = BaiduJson.longField(v, "size")
        if (retPath != targetPath || retSize != size) throw fail(ErrorCode.UNCERTAIN, "百度文件提交结果不符，请重新查询")
    }

    /** 单分片上传（小 JSON 用）。返回响应中的 md5。 */
    private fun uploadSlice(host: String, targetPath: String, uploadid: String, partseq: Int, content: ByteArray, filename: String): String {
        val boundary = "bps-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16)
        val multipartBody = buildMultipart(boundary, filename, content)
        val v = rawRequest(
            "$host/rest/2.0/pcs/superfile2",
            mapOf("method" to "upload", "type" to "tmpfile", "path" to targetPath, "uploadid" to uploadid, "partseq" to partseq.toString()),
            multipartBody,
            "multipart/form-data; boundary=$boundary",
        )
        return BaiduJson.strField(v, "md5") ?: throw fail(ErrorCode.CONFLICT, "百度上传分片响应缺失摘要")
    }

    private fun buildMultipart(boundary: String, filename: String, content: ByteArray): ByteArray {
        val header = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return header + content + footer
    }

    /** 直接发 multipart 请求并解析 JSON（不走 BaiduHttp 的表单封装）。 */
    private fun rawRequest(url: String, params: Map<String, String>, body: ByteArray, contentType: String): JsonObject {
        authorized()
        val fullParams = params + ("access_token" to token)
        val query = BaiduForm.form(fullParams)
        val mediaType = contentType.toMediaType()
        val requestBody = RequestBody.create(mediaType, body)
        val builder = Request.Builder().url("$url?$query").post(requestBody)
        builder.header("User-Agent", "pan.baidu.com")
        val response = client.newCall(builder.build()).execute()
        response.use { r ->
            val raw = r.body?.string() ?: throw fail(ErrorCode.NETWORK, "百度上传分片无响应")
            if (r.code != 200) throw fail(ErrorCode.NETWORK, "百度上传分片失败：${r.code}")
            val obj = BaiduJson.parse(raw)
            BaiduErrors.checkError(obj)
            return obj
        }
    }

    override suspend fun uploadSmallJson(path: String, raw: String, precondition: Precondition) {
        val max = if (path == "library.json") 4 * 1024 * 1024 else 65536
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size > max) throw fail(ErrorCode.INVALID_INPUT, "JSON 文件超过大小限制")
        // 校验是合法 JSON
        try { BaiduJson.parse(raw) } catch (e: Exception) { throw fail(ErrorCode.INVALID_INPUT, "文件必须是 JSON") }

        val md5 = md5Hex(bytes)
        val blocks = listOf(md5)
        val targetPath = path(path)
        val pre = precreate(targetPath, bytes.size.toLong(), blocks)
        if (pre.returnType == 1) {
            val host = uploadHost(pre.uploadid, targetPath)
            val sliceMd5 = uploadSlice(host, targetPath, pre.uploadid, 0, bytes, "record.json")
            if (sliceMd5 != md5) throw fail(ErrorCode.CONFLICT, "百度上传分片摘要不一致，未提交文件")
        }
        commit(targetPath, bytes.size.toLong(), pre.uploadid, blocks)
    }

    override suspend fun upload(
        path: String,
        localFile: File,
        precondition: Precondition,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        if (!localFile.isFile) throw fail(ErrorCode.IO, "上传源文件不可读")
        val size = localFile.length()
        if (size < 1 || size > MAX_SIZE) throw fail(ErrorCode.INVALID_INPUT, "百度上传要求可读的非空文件，最大 1 GiB")
        val mtime = localFile.lastModified()

        // 计算分片计划：固定 4MiB 分片，每个分片一个 MD5。
        val blockSize = BLOCK_SIZE
        val blocks = mutableListOf<String>()
        val parts = mutableListOf<Pair<Long, Long>>() // (offset, size)
        var offset = 0L
        localFile.inputStream().use { input ->
            while (offset < size) {
                val partSize = minOf(blockSize, size - offset)
                val md = MessageDigest.getInstance("MD5")
                var read = 0L
                val buffer = ByteArray(64 * 1024)
                while (read < partSize) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), partSize - read).toInt())
                    if (n == -1) break
                    md.update(buffer, 0, n)
                    read += n
                }
                if (read != partSize) throw fail(ErrorCode.IO, "上传源文件读取不完整")
                blocks.add(md.digest().joinToString("") { "%02x".format(it) })
                parts.add(offset to partSize)
                offset += partSize
            }
        }
        // 上传前校验文件未变
        if (!localFile.isFile || localFile.length() != size || localFile.lastModified() != mtime) {
            throw fail(ErrorCode.CONFLICT, "上传源文件在分片检查期间发生变化")
        }

        val targetPath = path(path)
        val pre = precreate(targetPath, size, blocks)
        var sent = 0L
        if (pre.returnType == 1) {
            val host = uploadHost(pre.uploadid, targetPath)
            onProgress?.invoke(0, size)
            for (idx in pre.blockList) {
                val (partOffset, partSize) = parts[idx]
                val partBytes = ByteArray(partSize.toInt())
                java.io.RandomAccessFile(localFile, "r").use { raf ->
                    raf.seek(partOffset)
                    var read = 0
                    while (read < partSize) {
                        val n = raf.read(partBytes, read, (partSize - read).toInt())
                        if (n == -1) break
                        read += n
                    }
                    if (read.toLong() != partSize) throw fail(ErrorCode.IO, "上传源文件读取不完整")
                }
                val sliceMd5 = uploadSlice(host, targetPath, pre.uploadid, idx, partBytes, "book.epub")
                if (sliceMd5 != blocks[idx]) throw fail(ErrorCode.CONFLICT, "百度 EPUB 分片摘要不一致，未提交文件")
                sent += partSize
                onProgress?.invoke(sent, size)
            }
        }
        // 上传后校验文件未变
        if (!localFile.isFile || localFile.length() != size || localFile.lastModified() != mtime) {
            throw fail(ErrorCode.CONFLICT, "上传源文件在传输期间发生变化，未提交文件")
        }
        commit(targetPath, size, pre.uploadid, blocks)
        invalidateLink(path)
    }

    // endregion

    // region download

    private fun invalidateLink(path: String) { linkCache.remove(path) }

    private fun trustedUrl(url: String): Boolean {
        if (url.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return false
        val uri = try { java.net.URI(url) } catch (e: Exception) { return false }
        if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.fragment != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host.lowercase()
        return host == "baidu.com" || host.endsWith(".baidu.com") ||
            host == "baidupcs.com" || host.endsWith(".baidupcs.com") ||
            host == "baidubce.com" || host.endsWith(".baidubce.com")
    }

    private fun tokenUrl(url: String): String {
        val (base, query) = url.split('?', limit = 2).let {
            if (it.size == 2) it[0] to it[1] else url to ""
        }
        val parts = query.split('&').filter { it.isNotEmpty() && it.substringBefore('=') != "access_token" }
        return "$base?" + (parts + "access_token=${BaiduForm.escape(token)}").joinToString("&")
    }

    override suspend fun downloadBytes(path: String, maxBytes: Int): ByteArray {
        val max = if (path == "library.json") 4L * 1024 * 1024 else maxBytes.toLong()
        return downloadInternal(path, null, max, null) as ByteArray
    }

    override suspend fun downloadTo(
        path: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        downloadInternal(path, destination, maxBytes, onProgress)
    }

    private suspend fun downloadInternal(
        path: String,
        destination: File?,
        maxBytes: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): Any? {
        authorized()
        val max = if (destination == null && path == "library.json") 4L * 1024 * 1024 else maxBytes
        for (attempt in 1..2) {
            var link = linkCache[path]
            if (link == null || now() >= link.expiresAt) {
                val fsIdReal = statFsId(path)
                val v = http.request(
                    "https://pan.baidu.com/rest/2.0/xpan/multimedia",
                    params = mapOf("method" to "filemetas", "dlink" to "1", "fsids" to "[$fsIdReal]"),
                )
                val list = v["list"] as? JsonArray
                val item = list?.firstOrNull() as? JsonObject
                val dlink = item?.let { BaiduJson.strField(it, "dlink") }
                val itemPath = item?.let { BaiduJson.strField(it, "path") }
                val itemFsId = item?.let { BaiduJson.strField(it, "fs_id") }
                val itemSize = item?.let { BaiduJson.longField(it, "size") }
                if (item == null || itemPath != path(path) || itemFsId != fsIdReal || itemSize == null ||
                    itemSize < 0 || itemSize > max || dlink == null || !trustedUrl(dlink)) {
                    invalidateLink(path)
                    throw fail(ErrorCode.INVALID_INPUT, "百度下载元信息无效或大小超限")
                }
                link = CachedLink(dlink, now() + 7 * 3600, fsIdReal, itemSize)
                linkCache[path] = link
            }

            if (link.size > max) throw fail(ErrorCode.INVALID_INPUT, "百度下载文件超出大小限制")

            var url = link.dlink
            var stale = false
            for (redirect in 1..4) {
                if (!trustedUrl(url)) throw fail(ErrorCode.TLS, "百度下载地址不是可信 HTTPS 地址")
                val result = downloadOnce(tokenUrl(url), destination, max)
                when (result.status) {
                    200 -> {
                        val actual = result.received
                        val expected = link.size
                        if (actual == expected) {
                            onProgress?.invoke(actual, expected)
                            return if (destination != null) null else result.bytes
                        }
                        stale = true
                        break
                    }
                    401, 403, 404, 410 -> { stale = true; break }
                    301, 302, 307, 308 -> {
                        url = result.location ?: throw fail(ErrorCode.NETWORK, "百度下载重定向缺少目标")
                    }
                    else -> throw fail(ErrorCode.NETWORK, "百度下载失败：HTTP ${result.status}")
                }
            }
            invalidateLink(path)
            if (!stale) throw fail(ErrorCode.NETWORK, "百度下载重定向次数过多")
        }
        throw fail(ErrorCode.NETWORK, "下载链接刷新后仍然无效，请检查授权或文件是否存在")
    }

    private data class DownloadResult(val status: Int, val bytes: ByteArray?, val location: String?, val received: Long)

    private fun downloadOnce(url: String, destination: File?, maxBytes: Long): DownloadResult {
        val builder = Request.Builder().url(url).get()
        builder.header("User-Agent", "pan.baidu.com")
        val response = client.newCall(builder.build()).execute()
        response.use { r ->
            if (r.code == 301 || r.code == 302 || r.code == 307 || r.code == 308) {
                return DownloadResult(r.code, null, r.header("Location"), 0L)
            }
            if (r.code != 200) return DownloadResult(r.code, null, null, 0L)
            if (destination != null) {
                var received = 0L
                r.body?.source()?.use { source ->
                    destination.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = source.read(buffer)
                            if (n == -1) break
                            received += n
                            if (received > maxBytes) throw fail(ErrorCode.INVALID_INPUT, "下载内容超过 $maxBytes 字节上限")
                            out.write(buffer, 0, n)
                        }
                    }
                }
                return DownloadResult(200, null, null, received)
            } else {
                val bytes = r.body?.bytes() ?: throw fail(ErrorCode.NETWORK, "百度下载无响应体")
                if (bytes.size > maxBytes) throw fail(ErrorCode.INVALID_INPUT, "下载内容超过 $maxBytes 字节上限")
                return DownloadResult(200, bytes, null, bytes.size.toLong())
            }
        }
    }

    /** 拿某个路径的 fsId（作为字符串）。 */
    private suspend fun statFsId(path: String): String {
        val clean = path.trimEnd('/')
        val parent = clean.substringBeforeLast('/', "").let { if (it == clean) "" else it }
        var page: String? = null
        while (true) {
            val r = list(parent, page)
            for (e in r.entries) if (e.path == clean) return e.fsId ?: throw fail(ErrorCode.NOT_FOUND, "百度条目缺少文件 ID")
            page = r.continuation ?: break
        }
        throw fail(ErrorCode.NOT_FOUND, "百度应用目录中不存在该条目")
    }

    // endregion

    // region delete

    override suspend fun delete(path: String, precondition: Precondition) {
        val valid = path.matches(Regex("^books/.+\\.[Ee][Pp][Uu][Bb]$")) ||
            path.matches(Regex("^progress/.+\\.json$")) ||
            path.matches(Regex("^covers/.+\\.json$"))
        if (!valid) throw fail(ErrorCode.FORBIDDEN, "百度删除路径无效")
        invalidateLink(path)
        val filelist = buildJsonArray { add(JsonPrimitive(path(path))) }.toString()
        val v = http.request(
            "https://pan.baidu.com/rest/2.0/xpan/file",
            method = "POST",
            body = BaiduForm.form(mapOf("async" to "0", "filelist" to filelist)),
            writing = true,
            params = mapOf("method" to "filemanager", "opera" to "delete"),
        )
        val info = v["info"] as? JsonArray
        if (info != null && info.size == 1) {
            val obj = info[0] as? JsonObject
            val p = obj?.let { BaiduJson.strField(it, "path") }
            val errno = obj?.let { BaiduJson.longField(it, "errno") }
            if (p == path(path) && errno == -9L) return // 已不存在，幂等
            if (p == path(path) && errno == 0L) return
        }
        throw fail(ErrorCode.UNCERTAIN, "百度删除结果未确认")
    }

    // endregion

    companion object {
        private const val BLOCK_SIZE = 4L * 1024 * 1024
        private const val MAX_SIZE = 1024L * 1024 * 1024

        fun create(
            appDir: String,
            accessToken: String,
            expiresAt: Long,
            userId: String? = null,
            now: () -> Long = { System.currentTimeMillis() / 1000 },
            clientFactory: (() -> OkHttpClient)? = null,
        ): BaiduBackend {
            val root = normalizeAppDir(appDir)
                ?: throw CloudException(ErrorCode.INVALID_INPUT, "应用目录必须是 /apps/实际应用目录")
            val client = clientFactory?.invoke() ?: defaultClient()
            return BaiduBackend(root, client, accessToken, expiresAt, userId, now)
        }

        private fun normalizeAppDir(dir: String): String? {
            val m = Regex("^/apps/([^/]+)/?$").find(dir) ?: return null
            val name = m.groupValues[1]
            if (name == "." || name == ".." || name.any { it == '\\' || it.code < 0x20 }) return null
            return "/apps/$name"
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        /** 公开的默认 client 工厂，供上层（CloudSync）注入诊断拦截器。 */
        fun defaultClientForDiagnostics(): OkHttpClient = defaultClient()
    }
}
