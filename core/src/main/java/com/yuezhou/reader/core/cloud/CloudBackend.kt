package com.yuezhou.reader.core.cloud

import java.io.File

/**
 * 云端后端抽象，对齐 BaiduPanSync `modules/backend_*.lua` 的接口面。
 *
 * WebDAV 与百度网盘各自实现本接口，上层 [CloudService] 只依赖本抽象做编排。
 *
 * 约定：
 * - 所有 path 均为相对同步根目录的路径（如 "books/书名.epub"、"library.json"）。
 * - 读类失败抛 [CloudException]（outcome 恒为 Failed）。
 * - 写类失败抛 [CloudException]，必须如实标注 outcome 为 [Outcome.Uncertain] 或 [Outcome.Failed]。
 * - 下载正文到 [downloadTo]，流式落盘，不整本进内存。
 */
interface CloudBackend {

    /** 后端种类。 */
    val kind: BackendKind

    /** 后端稳定身份标识（不含凭据），用于缓存/待发布记录按后端隔离。 */
    val backendId: String

    /** 用户可见的根目录描述（WebDAV 根 URL 或百度 /apps/xxx）。 */
    val root: String

    /** 能力描述。 */
    fun capabilities(): BackendCapabilities

    /** 授权就绪检查；百度需校验 token 未过期，WebDAV 校验凭据可用。 */
    suspend fun ensureAuthorized()

    /** 列目录，返回直接子项。[continuation] 为分页游标（WebDAV 恒不支持续页）。 */
    suspend fun list(path: String, continuation: String? = null): ListResult

    /** 取单个条目元数据；不存在时抛 [ErrorCode.NOT_FOUND]。 */
    suspend fun stat(path: String): CloudStat

    /** 创建目录（幂等：已存在则返回）。 */
    suspend fun mkdir(path: String)

    /** 下载小 JSON（列表/进度/封面），上限按调用方传入，返回原始字节。 */
    suspend fun downloadBytes(path: String, maxBytes: Int): ByteArray

    /** 流式下载到目标文件（EPUB 正文），[maxBytes] 为大小上限，[onProgress] 回调字节进度。 */
    suspend fun downloadTo(
        path: String,
        destination: File,
        maxBytes: Long,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    )

    /** 上传小 JSON（列表/进度/封面），文本已由协议层编码好。 */
    suspend fun uploadSmallJson(path: String, raw: String, precondition: Precondition = Precondition.Unconditional)

    /** 流式上传本地文件（EPUB 正文）。 */
    suspend fun upload(
        path: String,
        localFile: File,
        precondition: Precondition = Precondition.Unconditional,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    )

    /** 删除条目。 */
    suspend fun delete(path: String, precondition: Precondition = Precondition.Unconditional)
}

/** 列目录结果。 */
data class ListResult(
    val entries: List<CloudEntry>,
    val continuation: String? = null,
)
