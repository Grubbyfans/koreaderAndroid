package com.yuezhou.reader.reader.online.zlibrary

import android.content.Context
import com.yuezhou.reader.core.online.api.ZLibraryApi
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.config.ZLibraryConfigValidator
import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.BookPage
import com.yuezhou.reader.core.online.model.ConnectionState
import com.yuezhou.reader.core.online.model.DownloadResult
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.core.online.model.SearchQuery
import com.yuezhou.reader.core.online.provider.CatalogBookProvider
import com.yuezhou.reader.core.online.provider.ProviderCapability
import com.yuezhou.reader.core.online.config.ServerTrustScope
import com.yuezhou.reader.reader.online.config.OnlineCredentialStore
import com.yuezhou.reader.reader.online.config.OnlineCredentials
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import java.io.File

/**
 * Z-Library 检索型书源适配器（阶段 C2）。
 *
 * 只负责协议调用与数据映射：搜索免登录；详情、下载链接与额度使用已登录会话。
 * 下载落盘、EPUB 校验与导入由阶段 E 的下载管理器实现；此处明确返回 UNSUPPORTED，不写用户文件。
 */
class ZLibraryBookProvider(context: Context) : CatalogBookProvider {
    override val id: String = ZLibraryConfig.PROVIDER_ID
    override val displayName: String = "Z-Library"
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.SEARCH,
        ProviderCapability.FILTER,
        ProviderCapability.BOOK_DETAIL,
        ProviderCapability.DOWNLOAD,
        ProviderCapability.DOWNLOAD_QUOTA,
        ProviderCapability.CUSTOM_SERVER,
        ProviderCapability.IMPORT_CONFIG,
    )

    private val configStore = ZLibraryConfigStore(context)
    private val credentialStore = OnlineCredentialStore(context)
    private val config: ZLibraryConfig get() = configStore.load()
    private fun api(): ZLibraryApi = ZLibraryApi(config)

    suspend fun login(account: String, password: String): Result<ZLibraryApi.AuthSession> {
        val session = api().login(account, password).getOrElse { return Result.failure(it) }
        val saved = credentialStore.save(
            OnlineCredentials(
                account = account.trim(),
                cookies = session.cookies,
                userId = session.userId,
                serverHost = currentHost(),
            ),
        )
        if (!saved) return Result.failure(IllegalStateException("凭证加密保存失败"))
        return Result.success(session)
    }

    suspend fun currentSession(): ZLibraryApi.AuthSession? {
        val account = credentialStore.currentAccount() ?: return null
        val credentials = credentialStore.load(account) ?: return null
        // 会话与服务器主机绑定：切换到未授权的新地址后不复用旧 Cookie，要求重新登录。
        if (!ServerTrustScope.isTrusted(credentials.serverHost, config.servers.baseUrls.firstOrNull().orEmpty())) return null
        return ZLibraryApi.AuthSession(cookies = credentials.cookies, userId = credentials.userId ?: credentials.token)
    }

    /** 当前配置中生效的服务器主机。 */
    fun currentHost(): String = ServerTrustScope.hostOf(config.servers.baseUrls.firstOrNull().orEmpty())

    /** 当前会话是否绑定在生效主机上（供 UI 提示重新登录）。 */
    suspend fun sessionTrusted(): Boolean {
        val account = credentialStore.currentAccount() ?: return false
        val credentials = credentialStore.load(account) ?: return false
        if (credentials.serverHost.isBlank()) return true // 旧版凭证无绑定，视为可信
        return ServerTrustScope.isTrusted(credentials.serverHost, config.servers.baseUrls.firstOrNull().orEmpty())
    }

    fun currentAccount(): String? = credentialStore.currentAccount()

    suspend fun quota(): Result<ZLibraryApi.Quota> {
        val session = currentSession()
            ?: return Result.failure(ProviderException(ProviderErrorCode.AUTH, "请先登录 Z-Library"))
        return api().getQuota(session)
    }

    suspend fun logout() {
        currentAccount()?.let { credentialStore.clear(it) }
    }

    suspend fun testConnection(): Result<String> = configStore.testConnection(config)

    override suspend fun getConnectionState(): ConnectionState {
        val errors = ZLibraryConfigValidator.validate(config)
        if (errors.isNotEmpty()) return ConnectionState.Failed(errors.first())
        if (config.servers.baseUrls.isEmpty()) return ConnectionState.NotConfigured
        return ConnectionState.Connected
    }

    override suspend fun search(query: SearchQuery): BookPage {
        val session = currentSession()
        // UI 使用中文标识排序；实际表单值按参考实现的 SUPPORTED_ORDERS 映射。
        val order = when (query.sort) {
            "relevance", "bestmatch" -> "bestmatch"
            "newest" -> "date"
            else -> query.sort
        }
        val effective = query.copy(
            format = "EPUB",
            sort = order,
            pageSize = config.preferences.pageSize.coerceIn(1, 30),
        )
        val result = api().search(effective, session)
        result.getOrNull()?.let { return it }
        val error = result.exceptionOrNull() ?: ProviderException(ProviderErrorCode.NETWORK, "搜索失败")
        // 方案 §2/§5：原生热门书单保留；搜索被服务端拒绝时展示热门书单并明确提示，
        // 不冒充关键词搜索结果，也不自动切换服务器。
        if ((error as? ProviderException)?.code == ProviderErrorCode.SERVER_REJECTED) {
            return api().getMostPopular(effective.pageSize, session).getOrElse {
                throw asProviderException(error)
            }
        }
        throw asProviderException(error)
    }

    override suspend fun fetchCover(url: String): Result<ByteArray> = api().fetchCover(url)

    override suspend fun getBookDetail(remoteId: String): OnlineBook {
        val parts = remoteId.split(":", limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            throw ProviderException(ProviderErrorCode.INVALID_CONFIG, "远程标识缺少 id:hash，暂不支持原生详情")
        }
        val session = currentSession()
            ?: throw ProviderException(ProviderErrorCode.AUTH, "请先登录 Z-Library")
        return api().getBookDetail(parts[0], parts[1], session).getOrElse { throw asProviderException(it) }
    }

    override suspend fun download(
        request: BookDownloadRequest,
        onProgress: (received: Long, total: Long?) -> Unit,
        isBookOpen: (String) -> Boolean,
    ): DownloadResult {
        if (request.providerId != id) {
            return DownloadResult.Failed(ProviderErrorCode.INVALID_CONFIG, "下载请求与书源不匹配")
        }
        val session = currentSession()
            ?: return DownloadResult.Failed(ProviderErrorCode.AUTH, "请先登录 Z-Library 后再下载")
        val parts = request.remoteId.split(":", limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) {
            return DownloadResult.Failed(ProviderErrorCode.INVALID_CONFIG, "远程标识格式无效")
        }
        val target = request.remotePath?.let(::File)
            ?: return DownloadResult.Failed(ProviderErrorCode.IO, "下载临时路径无效")
        val link = api().getDownloadLink(parts[0], parts[1], session).getOrElse { error ->
            return DownloadResult.Failed(failureCode(error), error.message ?: "获取下载链接失败")
        }
        val bytes = api().downloadToFile(link, target, request.expectedSize, onProgress).getOrElse { error ->
            return DownloadResult.Failed(failureCode(error), error.message ?: "下载失败")
        }
        if (bytes <= 0L) return DownloadResult.Failed(ProviderErrorCode.IO, "下载文件为空")
        // 本地 bookId 由文件名确定，导入协调器在覆盖检查后返回；此处不以 remoteId 代替 bookId。
        return DownloadResult.Success(request.fileName, "")
    }

    private fun failureCode(error: Throwable): ProviderErrorCode =
        (error as? ProviderException)?.code ?: ProviderErrorCode.NETWORK

    private fun asProviderException(throwable: Throwable): ProviderException =
        throwable as? ProviderException ?: ProviderException(ProviderErrorCode.NETWORK, throwable.message ?: "网络请求失败", throwable)
}
