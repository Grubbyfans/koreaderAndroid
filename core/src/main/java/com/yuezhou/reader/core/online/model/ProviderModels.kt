package com.yuezhou.reader.core.online.model

/** 书源连接状态。 */
sealed interface ConnectionState {
    data object NotConfigured : ConnectionState
    data object Connected : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** 书源错误分类：额度/认证/限流错误不盲目重试。 */
enum class ProviderErrorCode {
    NOT_CONFIGURED,
    AUTH,
    NETWORK,
    QUOTA,
    RATE_LIMITED,
    UNSUPPORTED,
    INVALID_CONFIG,
    NOT_FOUND,
    IO,
    CANCELLED,
    UNKNOWN,
}

/** 书源统一异常，由各适配器把后端错误映射到此类型。 */
class ProviderException(
    val code: ProviderErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 下载请求：fileName 是本地唯一性判断依据。 */
data class BookDownloadRequest(
    val providerId: String,
    val accountScope: String = "",
    val remoteId: String,
    val fileName: String,
    val remotePath: String? = null,
    val expectedSize: Long? = null,
)

/** 下载结果。具体文件落盘与导入由后续阶段的下载管理器/导入协调器负责。 */
sealed interface DownloadResult {
    data class Success(val fileName: String, val localBookId: String) : DownloadResult
    data object Cancelled : DownloadResult
    data class Failed(val code: ProviderErrorCode, val message: String) : DownloadResult
}

/** 上传结果。检索型书源不支持上传。 */
sealed interface UploadResult {
    data class Success(val remoteId: String) : UploadResult
    data class Failed(val code: ProviderErrorCode, val message: String) : UploadResult
    data object NotSupported : UploadResult
}
