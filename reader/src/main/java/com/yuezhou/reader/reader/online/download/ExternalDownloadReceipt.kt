package com.yuezhou.reader.reader.online.download

/**
 * 网页进程交给主进程的下载完成回执。
 *
 * 只包含任务标识、本地 content URI 与必要元数据；禁止包含 Cookie、认证信息或签名下载链接。
 * [remoteRef] 仅用于展示来源，会去掉 query/fragment，避免把临时令牌写进交接文件。
 */
data class ExternalDownloadReceipt(
    val transferId: String,
    val contentUri: String,
    val expectedBytes: Long? = null,
    val suggestedFileName: String? = null,
    val remoteRef: String? = null,
    val accountScope: String = "",
    val downloadId: Long? = null,
)

sealed interface ExternalAcceptResult {
    data class Accepted(val taskId: String, val awaitingName: Boolean) : ExternalAcceptResult
    data class Duplicate(val taskId: String) : ExternalAcceptResult
    data class Rejected(val reason: String) : ExternalAcceptResult
    data class Failed(val taskId: String?, val reason: String) : ExternalAcceptResult
}

/** 网页来源在任务表中的固定来源标识，与原生 API 书源区分，不伪造 API id:hash。 */
const val ZLIBRARY_WEB_PROVIDER_ID = "ZLIBRARY_WEB"

/** 单本外部文件的安全上限；与 EPUB 校验的解压上限独立。 */
const val MAX_EXTERNAL_DOWNLOAD_BYTES = 512L * 1024 * 1024
