package com.yuezhou.reader.core.online.config

import kotlinx.serialization.Serializable

/**
 * Z-Library 书源配置（原方案 §6 + 服务器发现增量方案 §3 + 服务器设置优化 §11）。
 *
 * 只描述服务器、网络、发现策略、端点与偏好；**不包含任何账号、密码、Token、Cookie**。
 * 配置以 JSON 文件管理，凭证由 reader 层独立加密存储。
 *
 * schemaVersion=1 为旧版（servers.baseUrls 字符串数组），加载时依次迁移到 v2、v3。
 * v3 起自动/手动共用同一份 candidates；manualSelectedServerId 指向手动选中的候选。
 */
@Serializable
data class ZLibraryConfig(
    val schemaVersion: Int = SCHEMA_VERSION,
    val providerId: String = "zlibrary",
    val configVersion: String = "1.0.0",
    val servers: Servers = Servers(),
    val network: Network = Network(),
    val discovery: DiscoveryConfig = DiscoveryConfig(),
    val endpoints: Endpoints = Endpoints(),
    val preferences: Preferences = Preferences(),
) {
    @Serializable
    data class Servers(
        /** auto 从启用候选按优先级选择；manual 只在候选中选一个固定使用。 */
        val mode: String = "auto",
        /**
         * 兼容字段：旧版手动固定地址。schema v3 起并入 candidates 后写回为空；
         * 仅在读取旧配置时使用，不再作为独立入口。
         */
        val manualBaseUrl: String = "",
        /** 手动模式选中的候选 id；须指向 candidates 中启用的项，空表示尚未选择。 */
        val manualSelectedServerId: String? = null,
        /** 自动/手动共用的统一候选列表；默认空，由用户在服务器管理页添加。 */
        val candidates: List<ServerCandidate> = emptyList(),
        /**
         * 兼容镜像（不再作为独立配置入口）：auto 为启用候选地址，manual 为选中候选地址。
         * 供搜索/连接/探测/凭证模块读取当前生效主机，A/B/C 代码无需改动。
         */
        val baseUrls: List<String> = emptyList(),
    )

    @Serializable
    data class Network(
        val connectTimeoutMs: Int = 15_000,
        val readTimeoutMs: Int = 30_000,
    )

    @Serializable
    data class Endpoint(
        val path: String,
        val method: String = "GET",
    )

    @Serializable
    data class Endpoints(
        val search: Endpoint = Endpoint("/eapi/book/search", "POST"),
        /** 搜索被服务端拒绝时的降级列表端点（参考实现 b7eacbab 仍可用）。 */
        val mostPopular: Endpoint = Endpoint("/eapi/book/most-popular"),
        val bookDetail: Endpoint = Endpoint("/eapi/book/{id}/{hash}"),
        val downloadLink: Endpoint = Endpoint("/eapi/book/{id}/{hash}/file"),
        val profile: Endpoint = Endpoint("/eapi/user/profile"),
        val login: Endpoint = Endpoint("/rpc.php", "POST"),
        val loginFallback: Endpoint = Endpoint("/eapi/user/login", "POST"),
        val domains: Endpoint = Endpoint("/eapi/info/domains/singlelogin"),
        /** 无认证健康探测端点：须返回可解析的预期 JSON 结构。 */
        val health: Endpoint = Endpoint("/eapi/info/domains/singlelogin"),
    )

    @Serializable
    data class Preferences(
        val defaultLanguage: String = "zh",
        val defaultFormat: String = "epub",
        /** 参考实现固定 limit=30；允许调小但不得超过服务端上限。 */
        val pageSize: Int = 30,
    )

    companion object {
        const val PROVIDER_ID = "zlibrary"
        const val SCHEMA_VERSION = 3
        const val MAX_CANDIDATES = 50
        val DEFAULT: ZLibraryConfig = ZLibraryConfig()
    }
}

/**
 * 把配置规范化为当前生效状态：
 * - schema v1：baseUrls 字符串数组迁移为启用候选。
 * - schema v2：manualBaseUrl 并入统一候选列表；手动模式建立 manualSelectedServerId。
 * - 候选按 priority 去重排序并重编号；规范化相同地址只保留首个。
 * - 校正 manualSelectedServerId 只指向启用候选；baseUrls 同步为生效地址镜像。
 * - 全程幂等：迁移后的配置再次 normalize 结果不变。
 */
object ZLibraryConfigMigration {
    fun normalize(config: ZLibraryConfig): ZLibraryConfig {
        // v1 → v2：字符串地址数组转为启用候选。
        var upgraded = config
        if (upgraded.schemaVersion < 2) {
            val legacy = upgraded.servers.baseUrls
            val candidates = if (upgraded.servers.candidates.isEmpty() && legacy.isNotEmpty()) {
                legacy.mapIndexed { index, url ->
                    ServerCandidate(
                        id = "migrated-${index + 1}",
                        baseUrl = url.trim().trimEnd('/'),
                        enabled = true,
                        priority = index + 1,
                    )
                }
            } else upgraded.servers.candidates
            upgraded = upgraded.copy(
                schemaVersion = 2,
                servers = upgraded.servers.copy(candidates = candidates),
            )
        }

        // v2 → v3：旧 manualBaseUrl 并入统一候选列表并建立选择关系。
        if (upgraded.schemaVersion < 3) {
            upgraded = migrateManualBaseUrl(upgraded)
        }

        // 去重、按优先级排序、重编号。
        val seen = HashSet<String>()
        val distinct = upgraded.servers.candidates.mapNotNull { candidate ->
            val normalized = runCatching { ServerUrlPolicy.normalize(candidate.baseUrl) }.getOrNull()
                ?: candidate.baseUrl.trim().trimEnd('/')
            if (!seen.add(normalized)) null else candidate.copy(baseUrl = candidate.baseUrl.trim().trimEnd('/'))
        }
        val sorted = distinct.sortedBy { it.priority }.mapIndexed { index, candidate ->
            candidate.copy(priority = index + 1)
        }

        // 选择关系只允许指向启用候选；否则置空（提示用户重新选择）。
        val selectedId = sorted.firstOrNull {
            it.id == upgraded.servers.manualSelectedServerId && it.enabled
        }?.id

        // 生效地址镜像：manual 只取选中项，auto 取全部启用项。
        val effective = when (ServerMode.fromWire(upgraded.servers.mode)) {
            ServerMode.MANUAL -> sorted.firstOrNull { it.id == selectedId }?.let { listOf(it.baseUrl) }.orEmpty()
            ServerMode.AUTO -> sorted.filter { it.enabled }.map { it.baseUrl }
        }.map { it.trim().trimEnd('/') }

        return upgraded.copy(
            schemaVersion = ZLibraryConfig.SCHEMA_VERSION,
            servers = upgraded.servers.copy(
                manualBaseUrl = "",
                manualSelectedServerId = selectedId,
                candidates = sorted,
                baseUrls = effective,
            ),
        )
    }

    /** 把旧 manualBaseUrl 并入统一候选列表并建立选择关系；不重复、不丢失、幂等。 */
    private fun migrateManualBaseUrl(config: ZLibraryConfig): ZLibraryConfig {
        val manual = config.servers.manualBaseUrl.trim().trimEnd('/')
        if (manual.isBlank()) {
            return config.copy(schemaVersion = ZLibraryConfig.SCHEMA_VERSION)
        }
        val key = runCatching { ServerUrlPolicy.normalize(manual) }.getOrNull() ?: manual
        val existing = config.servers.candidates.firstOrNull { candidate ->
            (runCatching { ServerUrlPolicy.normalize(candidate.baseUrl) }.getOrNull()
                ?: candidate.baseUrl.trim().trimEnd('/')) == key
        }
        val candidates: List<ServerCandidate>
        val migratedId: String
        if (existing != null) {
            candidates = config.servers.candidates
            migratedId = existing.id
        } else {
            migratedId = "manual-migrated"
            val nextPriority = (config.servers.candidates.maxOfOrNull { it.priority } ?: 0) + 1
            candidates = config.servers.candidates + ServerCandidate(migratedId, manual, true, nextPriority)
        }
        // 仅手动模式建立选择关系；自动模式只保证地址进入候选列表，不丢配置。
        val selectedId = if (ServerMode.fromWire(config.servers.mode) == ServerMode.MANUAL) {
            migratedId
        } else {
            config.servers.manualSelectedServerId
        }
        return config.copy(
            schemaVersion = ZLibraryConfig.SCHEMA_VERSION,
            servers = config.servers.copy(
                candidates = candidates,
                manualSelectedServerId = selectedId,
            ),
        )
    }
}

/** 配置校验结果：错误列表为空才可保存。 */
object ZLibraryConfigValidator {
    private val VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    private val LANGUAGE = Regex("[A-Za-z]{2}(-[A-Za-z]{2,4})?")
    private val METHODS = setOf("GET", "POST")

    fun validate(config: ZLibraryConfig): List<String> = buildList {
        if (config.schemaVersion !in 1..ZLibraryConfig.SCHEMA_VERSION) {
            add("schemaVersion 必须为 1 或 ${ZLibraryConfig.SCHEMA_VERSION}")
        }
        if (config.providerId != ZLibraryConfig.PROVIDER_ID) add("providerId 必须为 zlibrary")
        if (!VERSION.matches(config.configVersion)) add("configVersion 必须为 x.y.z 格式")

        if (config.servers.mode.lowercase() !in setOf("auto", "manual")) {
            add("servers.mode 只能是 auto 或 manual")
        }
        // 允许空列表（方案 §7.3）：用户可从空列表开始添加。
        // manualSelectedServerId 必须指向存在的候选项；启用性由 normalize 校正。
        config.servers.manualSelectedServerId?.let { selected ->
            if (config.servers.candidates.none { it.id == selected }) {
                add("servers.manualSelectedServerId 必须指向候选列表中的服务器")
            }
        }
        // 兼容字段：旧配置非空地址仍需格式合法；新配置写回为空。
        if (config.servers.manualBaseUrl.isNotBlank()) {
            val error = ServerUrlPolicy.validate(config.servers.manualBaseUrl)
            if (error != null) add("servers.manualBaseUrl: $error")
        }
        config.servers.candidates.forEachIndexed { index, candidate ->
            val error = ServerUrlPolicy.validate(candidate.baseUrl)
            if (error != null) add("servers.candidates[$index].baseUrl: $error")
            if (candidate.id.isBlank()) add("servers.candidates[$index].id 不能为空")
            if (candidate.priority < 1) add("servers.candidates[$index].priority 必须大于 0")
        }
        if (config.servers.candidates.size > ZLibraryConfig.MAX_CANDIDATES) {
            add("候选服务器最多 ${ZLibraryConfig.MAX_CANDIDATES} 项")
        }
        if (config.servers.candidates.map { it.id }.toSet().size != config.servers.candidates.size) {
            add("候选服务器 id 不可重复")
        }
        val normalized = config.servers.candidates.mapNotNull {
            runCatching { ServerUrlPolicy.normalize(it.baseUrl) }.getOrNull()
        }
        if (normalized.toSet().size != normalized.size) add("规范化后服务器地址不可重复")
        config.servers.baseUrls.forEachIndexed { index, raw ->
            val error = ServerUrlPolicy.validate(raw)
            if (error != null) add("servers.baseUrls[$index]: $error")
        }

        if (config.network.connectTimeoutMs !in 1_000..120_000) add("连接超时需在 1000–120000ms 之间")
        if (config.network.readTimeoutMs !in 1_000..180_000) add("读取超时需在 1000–180000ms 之间")
        if (config.discovery.timeoutMs !in 1_000..30_000) add("探测超时需在 1000–30000ms 之间")
        if (config.discovery.cacheTtlSeconds !in 0..604_800) add("缓存有效期需在 0–604800 秒之间")
        if (config.discovery.maxConcurrentProbes !in 1..8) add("并发探测数需在 1–8 之间")

        listOf(
            "search" to config.endpoints.search,
            "mostPopular" to config.endpoints.mostPopular,
            "bookDetail" to config.endpoints.bookDetail,
            "downloadLink" to config.endpoints.downloadLink,
            "profile" to config.endpoints.profile,
            "login" to config.endpoints.login,
            "loginFallback" to config.endpoints.loginFallback,
            "domains" to config.endpoints.domains,
            "health" to config.endpoints.health,
        ).forEach { (name, endpoint) -> validateEndpoint(name, endpoint)?.let(::add) }

        if (!LANGUAGE.matches(config.preferences.defaultLanguage)) add("默认语言格式不正确")
        if (config.preferences.defaultFormat != "epub") add("当前版本仅支持 epub 格式")
        if (config.preferences.pageSize !in 1..30) add("分页数量需在 1–30 之间")
    }

    /** 仅允许 HTTPS 绝对地址，禁止 userinfo / 查询 / 片段 / 路径穿越 / 私有网络。 */
    fun validateBaseUrl(raw: String): String? = ServerUrlPolicy.validate(raw)

    private fun validateEndpoint(name: String, endpoint: ZLibraryConfig.Endpoint): String? = when {
        !endpoint.path.startsWith("/") -> "endpoints.$name.path 必须以 / 开头"
        endpoint.path.contains("..") -> "endpoints.$name.path 不允许路径穿越"
        endpoint.method.uppercase() !in METHODS -> "endpoints.$name.method 只支持 GET/POST"
        else -> null
    }
}
