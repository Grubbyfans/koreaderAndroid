package io.legado.app.nettts

import io.legado.app.constant.AppConst
import java.io.File

/**
 * 网络 TTS 运行时配置。
 *
 * 原版这些值来自 `AppConfig`/`AppDb`；静读侧用显式注入，避免 nettts 依赖 app 模块。
 * 未注入时使用安全默认值（默认 UA、内存 cookie、无磁盘缓存目录）。
 */
object NetworkTtsRuntime {

    /** Android 上下文（用于 androidId 等系统信息）；未装配时相关能力返回空。 */
    @Volatile
    var appContext: android.content.Context? = null

    /** 默认请求 UA；规则里显式配置的 header 优先。 */
    @Volatile
    var userAgent: String = AppConst.DEFAULT_USER_AGENT

    /** cookie 持久化（可选）；为 null 时仅进程内有效。 */
    @Volatile
    var cookieStore: NetworkTtsCookieStore? = null

    /** 登录信息加密存储；为 null 时不写入任何凭证。 */
    @Volatile
    var secretStore: NetworkTtsSecretStore? = null

    /** 落盘音频目录容量上限（字节），默认 256 MB，超出按最久未用淘汰。 */
    @Volatile
    var maxCacheBytes: Long = 256L * 1024 * 1024

    /** 单条音频落盘上限（字节），超过即判为异常响应。 */
    @Volatile
    var maxAudioBytes: Long = 32L * 1024 * 1024

    /** 规则 JS 里 `java.clearTtsCache()` 的实现。 */
    @Volatile
    var ttsCacheClearer: (() -> Unit)? = null
}

/** cookie 持久化端口，由 app 层用 DataStore/加密文件实现。 */
interface NetworkTtsCookieStore {
    fun get(domain: String): String?
    fun put(domain: String, cookie: String)
    fun remove(domain: String)
}
