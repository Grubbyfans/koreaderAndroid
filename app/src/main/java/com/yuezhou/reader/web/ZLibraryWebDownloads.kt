package com.yuezhou.reader.web

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import com.yuezhou.reader.reader.online.download.AndroidDownloadManagerGateway
import com.yuezhou.reader.reader.online.download.DownloadHandoffStore
import com.yuezhou.reader.reader.online.download.DownloadNamePolicy
import com.yuezhou.reader.reader.online.download.MAX_EXTERNAL_DOWNLOAD_BYTES
import com.yuezhou.reader.reader.online.download.WebDownloadRecord
import com.yuezhou.reader.reader.online.download.WebDownloadRegistry
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/**
 * 网页下载的纯策略逻辑（阶段 D）：只接受用户主动触发的 HTTPS GET 交付，
 * 候选文件名优先取响应头，其次取 URL 末段；占位名交给 EPUB 标题/用户确认。
 */
object DownloadRequestPolicy {

    private val FILENAME_STAR = Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
    private val FILENAME = Regex("filename\\s*=\\s*(\"[^\"]*\"|[^;]+)", RegexOption.IGNORE_CASE)

    fun supportedUrl(raw: String): String? {
        val trimmed = raw.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        if (uri.port != -1 && uri.port != 443) return null
        if (uri.host.isNullOrBlank()) return null
        return trimmed
    }

    fun sourceHost(raw: String): String? =
        supportedUrl(raw)?.let { runCatching { URI(it).host }.getOrNull()?.lowercase() }

    /** 返回可辨认的完整文件名（含 .epub）；占位/无意义时返回 null。 */
    fun candidateFileName(contentDisposition: String?, url: String): String? {
        dispositionName(contentDisposition)?.let { return it }
        val path = runCatching { URI(url).rawPath }.getOrNull().orEmpty()
        val last = path.trimEnd('/').substringAfterLast('/')
        // 无扩展名的末段通常是下载路径/编号，不是文件名；交给 EPUB 标题或用户确认。
        if (last.isBlank() || !last.contains('.')) return null
        return normalizeCandidate(decode(last))
    }

    fun sizeText(bytes: Long?): String = when {
        bytes == null || bytes <= 0L -> "未知"
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024f)
        else -> "$bytes B"
    }

    private fun dispositionName(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        FILENAME_STAR.find(text)?.groupValues?.getOrNull(1)?.let { value ->
            val encoded = stripQuotes(value.trim()).substringAfterLast("''", value)
            normalizeCandidate(decode(encoded))?.let { return it }
        }
        FILENAME.find(text)?.groupValues?.getOrNull(1)?.let { value ->
            normalizeCandidate(decode(stripQuotes(value.trim())))?.let { return it }
        }
        return null
    }

    private fun stripQuotes(value: String): String = value.trim().removeSurrounding("\"")

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%20"), Charsets.UTF_8.name())
    }.getOrDefault(value)

    private fun normalizeCandidate(raw: String?): String? =
        DownloadNamePolicy.normalize(raw)?.takeIf { DownloadNamePolicy.isMeaningful(it) }
}

/** 网页进程与主进程之间的交接约定；Provider 非导出且只接受同 UID 调用。 */
object ZLibraryWebHandoff {
    const val METHOD_WATCH = "watch"
    const val EXTRA_DOWNLOAD_ID = "downloadId"
    const val RESULT_OK = "ok"

    fun authority(context: Context): String = "${context.packageName}.zlibrary.web"

    fun uri(context: Context): Uri = "content://${authority(context)}".toUri()
}

/**
 * 在网页进程内发起系统下载并登记交接记录（阶段 D）。
 *
 * 只做三件事：入队 DownloadManager（应用私有外部目录）、写共享登记文件、
 * 通过非导出 Provider 通知主进程。不读取/转发 Cookie，也不把签名链接写入磁盘。
 */
class ZLibraryWebDownloadStarter(private val context: Context) {

    sealed interface Result {
        data class Started(val downloadId: Long) : Result
        data class Unsupported(val message: String) : Result
        data class Failed(val message: String) : Result
    }

    fun start(
        url: String,
        userAgent: String?,
        mimeType: String?,
        contentDisposition: String?,
        contentLength: Long,
    ): Result {
        val safeUrl = DownloadRequestPolicy.supportedUrl(url)
            ?: return Result.Unsupported("暂不支持该下载方式，请在浏览器中下载后手动导入")
        if (contentLength > MAX_EXTERNAL_DOWNLOAD_BYTES) {
            return Result.Unsupported("文件超过大小上限，暂不支持内置下载")
        }
        val transferId = UUID.randomUUID().toString()
        val gateway = AndroidDownloadManagerGateway(context)
        val downloadId = gateway.enqueue(
            url = safeUrl,
            destinationName = "$transferId.download",
            userAgent = userAgent,
            mimeType = mimeType?.takeIf { it.contains('/') },
        ) ?: return Result.Failed("无法创建系统下载任务")

        val record = WebDownloadRecord(
            transferId = transferId,
            downloadId = downloadId,
            suggestedFileName = DownloadRequestPolicy.candidateFileName(contentDisposition, safeUrl),
            expectedBytes = contentLength.takeIf { it in 1..MAX_EXTERNAL_DOWNLOAD_BYTES },
            // 只保留 origin + path；不落盘 query/fragment 中的临时令牌。
            remoteRef = DownloadHandoffStore.sanitizeRemoteRef(safeUrl),
            accountScope = "",
        )
        val registry = WebDownloadRegistry.forContext(context)
        if (!registry.write(record)) {
            android.util.Log.w("ZLibraryWebDL", "registry write failed for downloadId=$downloadId")
            gateway.remove(downloadId)
            return Result.Failed("无法记录下载任务，请重试")
        }
        notifyMainProcess(downloadId)
        return Result.Started(downloadId)
    }

    /**
     * 通知主进程开始观察。失败不视为下载失败：登记文件已在磁盘，
     * 主进程下次启动（或收到完成广播）时会自行对账。
     */
    private fun notifyMainProcess(downloadId: Long): Boolean {
        val extras = Bundle().apply { putLong(ZLibraryWebHandoff.EXTRA_DOWNLOAD_ID, downloadId) }
        return runCatching {
            context.contentResolver.call(
                ZLibraryWebHandoff.uri(context),
                ZLibraryWebHandoff.METHOD_WATCH,
                downloadId.toString(),
                extras,
            ) != null
        }.getOrDefault(false)
    }
}
