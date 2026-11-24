package com.yuezhou.reader.core.online.config

import kotlinx.serialization.Serializable

/**
 * Z-Library 书源配置（原方案 §6 + 服务器发现增量方案 §3）。
 *
 * 只描述服务器、网络、发现策略、端点与偏好；**不包含任何账号、密码、Token、Cookie**。
 * 配置以 JSON 文件管理，凭证由 reader 层独立加密存储。
 *
 * schemaVersion=1 为旧版（servers.baseUrls 字符串数组），加载时迁移到 v2。
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
        /** 兼容字段：auto 按候选优先级探测，manual 只用 manualBaseUrl。 */
        val mode: String = "auto",
        /** 手动模式固定地址；auto 模式可为空。 */
        val manualBaseUrl: String = "",
        /** 自动检测候选列表；旧版 baseUrls 在加载时迁移至此。 */
        val candidates: List<ServerCandidate> = listOf(DEFAULT_CANDIDATE),
        /** 旧版 schema v1 字段，仅用于读取并迁移，不在新配置中写出。 */
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
        const val SCHEMA_VERSION = 2
        const val MAX_CANDIDATES = 50
        val DEFAULT_CANDIDATE = ServerCandidate(
            id = "default", baseUrl = "https://example.com", enabled = true, priority = 1,
        )
        val DEFAULT: ZLibraryConfig = ZLibraryConfig()
    }
}

/**
 * 把配置规范化为当前生效状态：
 * - 旧 schema v1：baseUrls 迁移为启用候选，补齐 manualBaseUrl，升级 schema。
 * - 候选列表变化时同步 baseUrls（首个启用候选），保证现有 A/B/C 代码继续可用。
 * - 候选按 priority 去重排序并重编号；规范化相同地址只保留首个。
 */
object ZLibraryConfigMigration {
    fun normalize(config: ZLibraryConfig): ZLibraryConfig {
        val upgraded = if (config.schemaVersion < 2) {
            val legacy = config.servers.baseUrls
            val candidates = if (config.servers.candidates.isEmpty() && legacy.isNotEmpty()) {
                legacy.mapIndexed { index, url ->
                    ServerCandidate(
                        id = "migrated-${index + 1}",
                        baseUrl = url.trim().trimEnd('/'),
                        enabled = true,
                        priority = index + 1,
                    )
                }
            } else config.servers.candidates
            config.copy(
                schemaVersion = ZLibraryConfig.SCHEMA_VERSION,
                servers = config.servers.copy(
                    manualBaseUrl = config.servers.manualBaseUrl.ifBlank {
                        legacy.firstOrNull()?.trim()?.trimEnd('/').orEmpty()
                    },
                    candidates = candidates,
                ),
            )
        } else config

        val seen = HashSet<String>()
        val distinct = upgraded.servers.candidates.mapNotNull { candidate ->
            val normalized = runCatching { ServerUrlPolicy.normalize(candidate.baseUrl) }.getOrNull()
                ?: candidate.baseUrl.trim().trimEnd('/')
            if (!seen.add(normalized)) null else candidate.copy(baseUrl = candidate.baseUrl.trim().trimEnd('/'))
        }
        val sorted = distinct.sortedBy { it.priority }.mapIndexed { index, candidate ->
            candidate.copy(priority = index + 1)
        }
        val effective = when (ServerMode.fromWire(upgraded.servers.mode)) {
            ServerMode.MANUAL -> listOf(upgraded.servers.manualBaseUrl).filter { it.isNotBlank() }
            ServerMode.AUTO -> sorted.filter { it.enabled }.map { it.baseUrl }
        }.map { it.trim().trimEnd('/') }
        return upgraded.copy(
            servers = upgraded.servers.copy(candidates = sorted, baseUrls = effective),
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

        val mode = ServerMode.fromWire(config.servers.mode)
        if (config.servers.mode.lowercase() !in setOf("auto", "manual")) {
            add("servers.mode 只能是 auto 或 manual")
        }
        if (config.servers.candidates.isEmpty() && config.servers.baseUrls.isEmpty()) {
            add("至少需要一个服务器地址")
        }
        if (mode == ServerMode.MANUAL) {
            val error = ServerUrlPolicy.validate(config.servers.manualBaseUrl)
            if (error != null) add("servers.manualBaseUrl: $error")
        } else if (config.servers.manualBaseUrl.isNotBlank()) {
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
