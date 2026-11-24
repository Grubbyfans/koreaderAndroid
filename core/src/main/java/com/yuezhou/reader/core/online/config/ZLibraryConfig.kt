package com.yuezhou.reader.core.online.config

import kotlinx.serialization.Serializable

/**
 * Z-Library 书源配置（方案 §6）。
 *
 * 只描述服务器、网络、端点与偏好；**不包含任何账号、密码、Token、Cookie**。
 * 配置以 JSON 文件管理，凭证由 reader 层独立加密存储。
 */
@Serializable
data class ZLibraryConfig(
    val schemaVersion: Int = 1,
    val providerId: String = "zlibrary",
    val configVersion: String = "1.0.0",
    val servers: Servers = Servers(),
    val network: Network = Network(),
    val endpoints: Endpoints = Endpoints(),
    val preferences: Preferences = Preferences(),
) {
    @Serializable
    data class Servers(
        /** auto：按 baseUrls 顺序竞速；manual：只用第一个。 */
        val mode: String = "auto",
        /** 占位地址：用户必须改为自己有权访问的可信 HTTPS 服务器。 */
        val baseUrls: List<String> = listOf("https://example.com"),
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
        const val SCHEMA_VERSION = 1
        val DEFAULT: ZLibraryConfig = ZLibraryConfig()
    }
}

/**
 * 配置校验结果：错误列表为空才可保存。
 *
 * 校验规则对齐方案 §6：schema/版本/字段/HTTPS 地址/可信主机；
 * 不关闭 TLS 验证、不接受 userinfo、不接受路径穿越、不执行任何脚本。
 */
object ZLibraryConfigValidator {
    private val VERSION = Regex("\\d+\\.\\d+\\.\\d+")
    private val LANGUAGE = Regex("[A-Za-z]{2}(-[A-Za-z]{2,4})?")
    private val METHODS = setOf("GET", "POST")

    fun validate(config: ZLibraryConfig): List<String> = buildList {
        if (config.schemaVersion != ZLibraryConfig.SCHEMA_VERSION) {
            add("schemaVersion 必须为 ${ZLibraryConfig.SCHEMA_VERSION}")
        }
        if (config.providerId != ZLibraryConfig.PROVIDER_ID) add("providerId 必须为 zlibrary")
        if (!VERSION.matches(config.configVersion)) add("configVersion 必须为 x.y.z 格式")

        if (config.servers.mode !in setOf("auto", "manual")) add("servers.mode 只能是 auto 或 manual")
        if (config.servers.baseUrls.isEmpty()) add("至少需要一个服务器地址")
        config.servers.baseUrls.forEachIndexed { index, raw ->
            val error = validateBaseUrl(raw)
            if (error != null) add("servers.baseUrls[$index]: $error")
        }
        if (config.servers.baseUrls.toSet().size != config.servers.baseUrls.size) {
            add("服务器地址不可重复")
        }

        if (config.network.connectTimeoutMs !in 1_000..120_000) add("连接超时需在 1000–120000ms 之间")
        if (config.network.readTimeoutMs !in 1_000..180_000) add("读取超时需在 1000–180000ms 之间")

        listOf(
            "search" to config.endpoints.search,
            "bookDetail" to config.endpoints.bookDetail,
            "downloadLink" to config.endpoints.downloadLink,
            "profile" to config.endpoints.profile,
            "login" to config.endpoints.login,
            "loginFallback" to config.endpoints.loginFallback,
            "domains" to config.endpoints.domains,
        ).forEach { (name, endpoint) -> validateEndpoint(name, endpoint)?.let(::add) }

        if (!LANGUAGE.matches(config.preferences.defaultLanguage)) add("默认语言格式不正确")
        if (config.preferences.defaultFormat != "epub") add("当前版本仅支持 epub 格式")
        if (config.preferences.pageSize !in 1..30) add("分页数量需在 1–30 之间")
    }

    /** 仅允许 HTTPS 绝对地址，禁止 userinfo / 查询 / 片段 / 路径穿越。 */
    fun validateBaseUrl(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isEmpty()) return "地址不能为空"
        val url = runCatching { java.net.URL(value) }.getOrNull() ?: return "地址格式无效"
        if (url.protocol != "https") return "必须使用 HTTPS"
        if (url.userInfo != null) return "不允许在地址中携带用户名或密码"
        if (url.query != null || url.ref != null) return "地址不能包含查询参数或片段"
        val path = url.path.orEmpty()
        if (path != "" && path != "/") return "服务器地址只能包含协议与主机"
        if (url.host.isEmpty()) return "主机不能为空"
        return null
    }

    private fun validateEndpoint(name: String, endpoint: ZLibraryConfig.Endpoint): String? = when {
        !endpoint.path.startsWith("/") -> "endpoints.$name.path 必须以 / 开头"
        endpoint.path.contains("..") -> "endpoints.$name.path 不允许路径穿越"
        endpoint.method.uppercase() !in METHODS -> "endpoints.$name.method 只支持 GET/POST"
        else -> null
    }
}
