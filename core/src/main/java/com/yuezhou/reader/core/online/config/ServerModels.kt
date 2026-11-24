package com.yuezhou.reader.core.online.config

import kotlinx.serialization.Serializable

/** 服务器选择模式：auto 从启用候选中按优先级探测，manual 只用固定地址。 */
enum class ServerMode(val wire: String) {
    AUTO("auto"),
    MANUAL("manual"),
    ;

    companion object {
        fun fromWire(value: String): ServerMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/** 单个候选服务器：priority 数字越小越优先，排序后重新编号。 */
@Serializable
data class ServerCandidate(
    val id: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val priority: Int,
)

/** 探测状态；当前使用标记独立保存，不混入探测结果。 */
enum class ProbeStatus {
    UNTESTED, CHECKING, AVAILABLE, TIMEOUT, NETWORK_ERROR, TLS_ERROR, API_INCOMPATIBLE, CANCELLED,
}

/** 单项探测结果（运行状态，不写入用户导出配置）。 */
@Serializable
data class ProbeResult(
    val candidateId: String,
    val status: String,
    val latencyMs: Long? = null,
    val checkedAt: Long = 0L,
)

/** 自动发现运行状态：仅本地保存，不导出、不随配置回滚。 */
@Serializable
data class DiscoveryState(
    val lastAvailableBaseUrl: String? = null,
    val lastCheckedAt: Long? = null,
    val configRevision: Long = 0L,
)

/** 发现策略参数：探测超时与业务超时分离。 */
@Serializable
data class DiscoveryConfig(
    /** 单次探测总超时；需小于 or 等于业务超时，默认 5 秒。 */
    val timeoutMs: Int = 5_000,
    /** 最近可用地址缓存有效期，默认 24 小时。 */
    val cacheTtlSeconds: Long = 86_400L,
    /** 最大并发探测数，默认 3。 */
    val maxConcurrentProbes: Int = 3,
)
