package com.yuezhou.reader.core.cloud

/**
 * 写操作的先决条件，对应插件 `precondition`。
 *
 * 三种形态：
 * - [Unconditional]：无条件覆盖，用于同名替换正文/列表/进度等单写入者场景。
 * - [Absent]：要求目标不存在（映射到 `If-None-Match: *`）。
 * - [MatchEtag]：要求目标当前 ETag 等于指定强 ETag（映射到 `If-Match: <etag>`）。
 */
sealed interface Precondition {
    data object Unconditional : Precondition
    data object Absent : Precondition
    data class MatchEtag(val etag: String) : Precondition
}

/**
 * 后端能力描述，对应插件 `capabilities()`。
 *
 * 百度后端除 mkdir/delete 外均为 false；WebDAV 的条件能力需实测探测后才为 true。
 */
data class BackendCapabilities(
    val mkdir: Boolean = true,
    val delete: Boolean = true,
    val conditionalCreate: Boolean = false,
    val conditionalReplace: Boolean = false,
    val conditionalDelete: Boolean = false,
    val move: Boolean = false,
    val atomicPublish: Boolean = false,
    val optimisticVersions: Boolean = false,
    val verifyUploadedContent: Boolean = false,
)

/** 后端种类。 */
enum class BackendKind { WebDav, Baidu }
