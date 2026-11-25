package com.yuezhou.reader.ui.online

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.BookFormatState
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.OnlinePageMerger
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.core.online.model.SearchQuery
import com.yuezhou.reader.core.online.provider.CatalogBookProvider
import com.yuezhou.reader.core.online.provider.CloudBookProvider
import com.yuezhou.reader.reader.online.config.ZLibraryConfigStore
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.online.zlibrary.ZLibraryBookProvider
import com.yuezhou.reader.ui.LibraryUiPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 原生搜索被服务端拒绝时的统一提示；保留网页入口，不展示降级书单冒充结果。 */
private const val NATIVE_SEARCH_UNAVAILABLE = "原生搜索暂不可用，可使用网页搜索"

/** 检索结果来源：关键词搜索或服务端拒绝后的官方热门书单。 */
enum class CatalogSource { NONE, KEYWORD, POPULAR }

/** 单个书源的页面状态：云书源保存列表，检索书源另存搜索词、筛选、分页、滚动与详情。 */
data class ProviderUiState(
    val items: List<OnlineBook> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val backendLabel: String = "",
    val fetchedAt: Long = 0L,
    val fromCache: Boolean = false,
    val loaded: Boolean = false,
    // —— 检索型书源 ——
    val keyword: String = "",
    val submittedKeyword: String = "",
    val language: String? = null,
    val languageLabel: String = "全部语言",
    val sort: String = "bestmatch",
    val searched: Boolean = false,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    /** 分页追加失败的原因；只在列表底部展示，不覆盖已有结果。 */
    val loadMoreError: String? = null,
    val searchError: String? = null,
    /** 当前列表的来源；热门书单无分页，刷新时按来源选择接口。 */
    val resultSource: CatalogSource = CatalogSource.NONE,
    /** 降级等非错误提示（如服务端拒绝搜索后展示热门书单）。 */
    val notice: String? = null,
    val gridMode: Boolean = true,
    val detail: OnlineBook? = null,
    val detailLoading: Boolean = false,
    val detailMessage: String? = null,
    val downloadEnqueuing: Boolean = false,
    val quotaText: String? = null,
)

/**
 * 在线书库统一入口 ViewModel。
 *
 * 每个书源独立保存状态，切换书源不重复加载已有数据；主动刷新才强制重拉。
 * 检索型书源的新搜索会取消旧请求并清空分页，分页追加按 remoteId 去重，失败保留旧列表。
 */
class OnlineLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val cloudSync = CloudSync(context)
    private val prefs = LibraryUiPrefs(context)
    private val runtime = DownloadManagerHolder.runtime(context)
    private val zLibraryProvider = runtime.zLibraryProvider

    /** 主进程唯一书源注册表；与下载管理器共用同一份实例。 */
    val registry = runtime.registry

    /** 统一下载管理器：并发 2、进度节流、恢复与覆盖确认由 reader 层负责，恢复只在 Holder 首次创建时执行。 */
    val downloadManager = runtime.manager

    var selectedProviderId: String by mutableStateOf(
        // 上次选中的书源同步恢复；已禁用的书源回退到首个可用书源，避免初始化到不可见页。
        prefs.lastCloudProvider?.takeIf { id -> registry.enabled().any { it.id == id } }
            ?: registry.enabled().firstOrNull()?.id
            ?: "baidu",
    )
        private set

    private val states = mutableStateMapOf<String, ProviderUiState>()

    init {
        // 书源设置页保存的默认语言直接作为检索页初始筛选条件。
        val preferred = ZLibraryConfigStore(context).load().preferences.defaultLanguage
        val (language, label) = when (preferred) {
            "zh", "chinese" -> "chinese" to "中文"
            "en", "english" -> "english" to "英文"
            else -> null to "全部语言"
        }
        states["zlibrary"] = ProviderUiState(
            language = language,
            languageLabel = label,
            gridMode = prefs.catalogGridMode,
        )
    }
    private val jobs = mutableMapOf<String, Job>()
    private val requestSeq = mutableMapOf<String, Int>()
    private val detailSeq = mutableMapOf<String, Int>()

    /** 封面只在内存中缓存，失败的地址不再重复请求（账号退出或应用重启后自然清空）。 */
    val coverBytes = mutableStateMapOf<String, ByteArray>()
    private val coverLoading = mutableSetOf<String>()
    private val coverFailed = mutableSetOf<String>()

    val selectedState: ProviderUiState
        get() = states[selectedProviderId] ?: ProviderUiState()

    fun stateOf(providerId: String): ProviderUiState = states[providerId] ?: ProviderUiState()

    val isCatalogSelected: Boolean
        get() = registry.get(selectedProviderId) is CatalogBookProvider

    /** 指定书源是否为检索型；顶栏与页面都按明确 id 判断，不依赖全局选中。 */
    fun isCatalog(providerId: String): Boolean = registry.get(providerId) is CatalogBookProvider

    init {
        // 没有任何书源记录时，沿用用户已有的同步后端选择，避免切换入口后目标云端发生变化。
        viewModelScope.launch {
            if (prefs.lastCloudProvider != null) return@launch
            val active = CloudCredentialStore(context).load().activeBackend
            if (active == "webdav" && registry.get("webdav") != null) {
                selectedProviderId = "webdav"
                loadSelected()
            }
        }
    }

    fun selectProvider(providerId: String) {
        if (providerId == selectedProviderId || registry.get(providerId) == null) return
        selectedProviderId = providerId
        prefs.lastCloudProvider = providerId
        if (registry.get(providerId) is CatalogBookProvider) {
            // 登录状态是页面常驻信息：仅首次进入时预热一次，已查询过则不重复请求；
            // 打开「更多」菜单时仍会主动刷新额度。
            if (stateOf(providerId).quotaText == null) loadQuota(providerId)
        } else {
            viewModelScope.launch { loadSelected(providerId = providerId) }
        }
    }

    /** 书源与云同步后端的映射；不依赖“当前选中”状态，便于页面按 providerId 调用。 */
    fun backendKindOf(providerId: String): BackendKind? = when (providerId) {
        "baidu" -> BackendKind.Baidu
        "webdav" -> BackendKind.WebDav
        else -> null
    }

    fun selectedBackendKind(): BackendKind? = backendKindOf(selectedProviderId)

    /** 加载指定书源列表；已加载且非强制刷新时直接复用，避免切换书源重复请求。 */
    suspend fun loadSelected(
        force: Boolean = false,
        providerId: String = selectedProviderId,
    ): ProviderUiState {
        val id = providerId
        val current = stateOf(id)
        if (current.loaded && !force && !current.loading) return current

        val registered = registry.get(id) ?: return current.copy(error = "书源不存在").also { states[id] = it }
        if (registered is CatalogBookProvider) return current.copy(loaded = true)
        val provider = registered as? CloudBookProvider
            ?: return current.copy(error = "该书源暂未接入").also { states[id] = it }

        states[id] = current.copy(loading = true, refreshing = force, error = null)
        return try {
            val page = provider.listBooks(force = force)
            val next = current.copy(
                items = page.items,
                loading = false,
                refreshing = false,
                error = null,
                backendLabel = provider.displayName,
                fetchedAt = page.fetchedAt,
                fromCache = page.fromCache,
                loaded = true,
            )
            states[id] = next
            if (page.fromCache) {
                viewModelScope.launch {
                    val changed = runCatching { cloudSync.discoverExternalBooks(forced = backendKindOf(id)) }
                        .getOrDefault(0)
                    if (changed > 0) loadSelected(force = true, providerId = id)
                }
            }
            next
        } catch (e: Exception) {
            // 网络失败保留旧列表，只更新错误态（与原有云端书库行为一致）。
            val failed = stateOf(id).copy(loading = false, refreshing = false, error = e.message ?: "书库加载失败")
            states[id] = failed
            failed
        }
    }

    /** 主动更新：云书源登记外部书籍后强制重拉；检索书源按当前数据源刷新。 */
    suspend fun refreshSelected(providerId: String = selectedProviderId): Pair<ProviderUiState, String?> {
        val id = providerId
        if (registry.get(id) is CatalogBookProvider) {
            val current = stateOf(id)
            if (current.resultSource == CatalogSource.POPULAR) {
                refreshPopular(id)
                return stateOf(id) to null
            }
            if (current.submittedKeyword.isBlank()) {
                return current to "请先输入关键词搜索"
            }
            jobs[id]?.cancel()
            val seq = nextRequestSeq(id)
            states[id] = current.copy(refreshing = true, searchError = null)
            doSearch(id, current.submittedKeyword, append = false, seq = seq)
            loadQuota(id)
            return stateOf(id) to null
        }
        val kind = backendKindOf(id) ?: return stateOf(id) to "请先配置云同步后端"
        val message = try {
            cloudSync.refreshLibrary(forced = kind)
        } catch (e: Exception) {
            val failed = stateOf(id).copy(refreshing = false, error = e.message ?: "更新书库失败")
            states[id] = failed
            return failed to "更新书库失败：${e.message ?: "未知错误"}"
        }
        return loadSelected(force = true, providerId = id) to message
    }

    /** 下拉刷新/菜单刷新的非挂起入口。 */
    fun refreshCurrent(providerId: String = selectedProviderId) {
        viewModelScope.launch { refreshSelected(providerId) }
    }

    /** 常驻入口：无需先触发一次被拒搜索，直接浏览官方「最热门」单页。 */
    fun openPopular(providerId: String = selectedProviderId) {
        val id = providerId
        val provider = registry.get(id) as? ZLibraryBookProvider ?: return
        jobs[id]?.cancel()
        val seq = nextRequestSeq(id)
        states[id] = stateOf(id).copy(
            loading = true,
            loadingMore = false,
            loadMoreError = null,
            searchError = null,
            notice = null,
            refreshing = false,
        )
        jobs[id] = viewModelScope.launch {
            try {
                val page = provider.mostPopular(30)
                if (requestSeq[id] != seq) return@launch
                states[id] = stateOf(id).copy(
                    items = page.items,
                    nextCursor = null,
                    hasMore = false,
                    loading = false,
                    loadingMore = false,
                    loadMoreError = null,
                    refreshing = false,
                    fetchedAt = page.fetchedAt,
                    searchError = null,
                    notice = page.notice,
                    searched = true,
                    resultSource = CatalogSource.POPULAR,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestSeq[id] != seq) return@launch
                states[id] = stateOf(id).copy(
                    loading = false,
                    loadingMore = false,
                    searched = true,
                    searchError = readableError(e),
                )
            }
        }
    }

    /** 热门书单没有分页，刷新只重拉固定一页，不触碰关键词搜索条件。 */
    private suspend fun refreshPopular(id: String) {
        val provider = registry.get(id) as? ZLibraryBookProvider ?: return
        val seq = nextRequestSeq(id)
        states[id] = stateOf(id).copy(refreshing = true, searchError = null)
        try {
            val page = provider.mostPopular(30)
            if (requestSeq[id] != seq) return
            states[id] = stateOf(id).copy(
                items = page.items,
                nextCursor = null,
                hasMore = false,
                loading = false,
                loadingMore = false,
                loadMoreError = null,
                refreshing = false,
                fetchedAt = page.fetchedAt,
                searchError = null,
                notice = page.notice,
                searched = true,
                resultSource = CatalogSource.POPULAR,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestSeq[id] != seq) return
            // 刷新失败保留旧热门列表，只提示错误。
            states[id] = stateOf(id).copy(refreshing = false, searchError = readableError(e))
        }
    }

    /** 转换为现有云端详情/下载弹窗所需的模型；原 CloudSync.RemoteBook 不改动。 */
    fun legacyBooks(items: List<OnlineBook>): List<CloudSync.RemoteBook> = items.map {
        CloudSync.RemoteBook(
            bookId = it.remoteId,
            fileName = it.fileName,
            size = it.fileSize ?: 0L,
            coverPath = null,
            coverVersion = null,
            local = it.local,
            coverPng = it.coverPng,
        )
    }

    // —— 检索型书源：输入状态 ——

    fun setKeyword(value: String, providerId: String = selectedProviderId) {
        val id = providerId
        states[id] = stateOf(id).copy(keyword = value)
    }

    fun applyFilters(
        language: String?,
        languageLabel: String,
        sort: String,
        providerId: String = selectedProviderId,
    ) {
        val id = providerId
        val current = stateOf(id)
        states[id] = current.copy(language = language, languageLabel = languageLabel, sort = sort)
        // 已提交过关键词时，筛选变更统一触发一次新搜索；否则只更新条件，不发请求。
        if (current.searched && current.submittedKeyword.isNotBlank()) {
            startSearch(id, current.submittedKeyword)
        }
    }

    fun setGridMode(value: Boolean, providerId: String = selectedProviderId) {
        val id = providerId
        states[id] = stateOf(id).copy(gridMode = value)
        prefs.catalogGridMode = value
    }

    fun clearSearch(providerId: String = selectedProviderId) {
        val id = providerId
        jobs[id]?.cancel()
        states[id] = ProviderUiState(language = stateOf(id).language, languageLabel = stateOf(id).languageLabel)
    }

    fun search(providerId: String = selectedProviderId) {
        val id = providerId
        val keyword = stateOf(id).keyword.trim()
        if (keyword.isEmpty()) {
            states[id] = stateOf(id).copy(searchError = "请输入书名、作者或 ISBN")
            return
        }
        startSearch(id, keyword)
    }

    /** 提示条里的「重新搜索」只重试已提交关键词，不读取输入框中尚未提交的内容。 */
    fun retrySubmittedSearch(providerId: String = selectedProviderId) {
        val id = providerId
        val keyword = stateOf(id).submittedKeyword.trim()
        if (keyword.isEmpty()) return
        startSearch(id, keyword)
    }

    private fun startSearch(id: String, keyword: String) {
        jobs[id]?.cancel()
        val seq = nextRequestSeq(id)
        states[id] = stateOf(id).copy(
            submittedKeyword = keyword,
            searched = true,
            items = emptyList(),
            nextCursor = null,
            hasMore = false,
            error = null,
            searchError = null,
            notice = null,
            loading = true,
            loadingMore = false,
            loadMoreError = null,
            refreshing = false,
            resultSource = CatalogSource.NONE,
            detail = null,
        )
        jobs[id] = viewModelScope.launch { doSearch(id, keyword, append = false, seq = seq) }
    }

    fun loadMore(providerId: String = selectedProviderId) {
        val id = providerId
        val current = stateOf(id)
        if (current.loading || current.loadingMore || !current.hasMore || current.nextCursor == null) return
        val seq = nextRequestSeq(id)
        states[id] = current.copy(loadingMore = true, loadMoreError = null, error = null)
        jobs[id] = viewModelScope.launch { doSearch(id, current.submittedKeyword, append = true, seq = seq) }
    }

    private suspend fun doSearch(id: String, keyword: String, append: Boolean, seq: Int) {
        val provider = registry.get(id) as? CatalogBookProvider ?: return
        val current = stateOf(id)
        try {
            val page = provider.search(
                SearchQuery(
                    keyword = keyword,
                    language = current.language,
                    format = "epub",
                    sort = current.sort,
                    cursor = if (append) current.nextCursor else null,
                    pageSize = 30,
                ),
            )
            if (requestSeq[id] != seq) return // 过期请求：不污染当前列表
            val merged = if (append) OnlinePageMerger.append(current.items, page.items) else page.items
            // 原生搜索被拒时适配器返回官方「最热门」书单并带 notice；
            // 界面按 notice 明确标注为热门书单，不冒充关键词搜索结果（方案 §2/§5）。
            val fallback = page.notice != null
            states[id] = stateOf(id).copy(
                items = merged,
                nextCursor = if (fallback) null else page.nextCursor,
                hasMore = if (fallback) false else page.hasMore && page.nextCursor != null,
                loading = false,
                loadingMore = false,
                loadMoreError = null,
                refreshing = false,
                fetchedAt = page.fetchedAt,
                error = null,
                searchError = null,
                notice = page.notice,
                searched = true,
                resultSource = if (fallback) CatalogSource.POPULAR else CatalogSource.KEYWORD,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestSeq[id] != seq) return
            // 分页失败不影响已加载结果：错误只落在列表底部，由「重试」重新追加。
            states[id] = if (append) {
                stateOf(id).copy(loadingMore = false, loadMoreError = readableError(e))
            } else {
                stateOf(id).copy(
                    loading = false,
                    loadingMore = false,
                    refreshing = false,
                    searchError = readableError(e),
                )
            }
        }
    }

    // —— 详情 ——

    fun openDetail(book: OnlineBook, providerId: String = selectedProviderId) {
        val id = providerId
        val seq = (detailSeq[id] ?: 0) + 1
        detailSeq[id] = seq
        states[id] = stateOf(id).copy(detail = book, detailLoading = true, detailMessage = null)
        viewModelScope.launch {
            val provider = registry.get(id) as? CatalogBookProvider ?: return@launch
            try {
                val full = provider.getBookDetail(book.remoteId)
                if (detailSeq[id] != seq) return@launch
                states[id] = stateOf(id).copy(
                    detail = full.copy(
                        coverUrl = full.coverUrl ?: book.coverUrl,
                        description = full.description ?: book.description,
                        language = full.language ?: book.language,
                    ),
                    detailLoading = false,
                    detailMessage = null,
                )
            } catch (e: Exception) {
                if (detailSeq[id] != seq) return@launch
                val message = if (e is ProviderException && e.code == ProviderErrorCode.AUTH) {
                    "登录后可查看完整详情并下载"
                } else {
                    "完整详情加载失败：${readableError(e)}"
                }
                states[id] = stateOf(id).copy(detail = book, detailLoading = false, detailMessage = message)
            }
        }
    }

    fun closeDetail(providerId: String = selectedProviderId) {
        val id = providerId
        detailSeq[id] = (detailSeq[id] ?: 0) + 1
        states[id] = stateOf(id).copy(detail = null, detailLoading = false, detailMessage = null)
    }

    /** 从详情页发起下载：文件名唯一性检查与覆盖确认在管理器/导入协调器中完成。 */
    fun enqueueDownload(book: OnlineBook, providerId: String, onQueued: () -> Unit) {
        val id = providerId
        // 详情页已按格式/标识状态禁用按钮，这里再兜底一次，避免任何调用路径误入下载。
        if (book.formatState != BookFormatState.CONFIRMED) return
        if (book.remoteId.split(":", limit = 2).let { it.size != 2 || it.any(String::isBlank) }) return
        val provider = registry.get(id)
        if (provider !is CatalogBookProvider || !provider.capabilities.contains(
                com.yuezhou.reader.core.online.provider.ProviderCapability.DOWNLOAD,
            )
        ) return
        val current = stateOf(id)
        if (current.downloadEnqueuing) return
        states[id] = current.copy(downloadEnqueuing = true, detailMessage = null)
        viewModelScope.launch {
            try {
                downloadManager.enqueue(
                    BookDownloadRequest(
                        providerId = id,
                        accountScope = zLibraryProvider.currentAccount().orEmpty(),
                        remoteId = book.remoteId,
                        fileName = book.fileName,
                        expectedSize = book.fileSize,
                    ),
                    title = book.title,
                )
                if (stateOf(id).detail?.remoteId == book.remoteId) onQueued()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val latest = stateOf(id)
                if (latest.detail?.remoteId == book.remoteId) {
                    states[id] = latest.copy(detailMessage = "无法创建下载任务：${e.message ?: "请重试"}")
                }
            } finally {
                states[id] = stateOf(id).copy(downloadEnqueuing = false)
            }
        }
    }

    /** 登录状态与额度只用于展示；凭证查询在 reader 层加密存储中进行。 */
    fun loadQuota(providerId: String = selectedProviderId) {
        val id = providerId
        val provider = registry.get(id) as? ZLibraryBookProvider ?: return
        viewModelScope.launch {
            val account = provider.currentAccount()
            val text = if (account.isNullOrBlank()) {
                "未登录 · 搜索免登录，下载需登录"
            } else {
                states[id] = stateOf(id).copy(quotaText = "正在查询额度…")
                provider.quota().fold(
                    onSuccess = {
                        val used = it.downloadsToday?.toString() ?: "未知"
                        val limit = it.downloadsLimit?.toString() ?: "未知"
                        "已登录：$account · 今日下载 $used / $limit"
                    },
                    onFailure = { "已登录：$account · 额度查询失败，请重新登录" },
                )
            }
            states[id] = stateOf(id).copy(quotaText = text)
        }
    }

    // —— 封面（无凭证静态资源） ——

    fun coverOf(url: String?): ByteArray? = url?.let { coverBytes[it] }

    fun isCoverLoading(url: String?): Boolean = url != null && url in coverLoading

    fun loadCover(url: String?, providerId: String = selectedProviderId) {
        if (url == null || url in coverLoading || url in coverFailed || url in coverBytes) return
        val provider = registry.get(providerId) as? CatalogBookProvider ?: return
        coverLoading.add(url)
        viewModelScope.launch {
            provider.fetchCover(url).fold(
                onSuccess = { bytes ->
                    coverLoading.remove(url)
                    coverBytes[url] = bytes
                    if (coverBytes.size > 80) coverBytes.keys.firstOrNull()?.let { coverBytes.remove(it) }
                },
                onFailure = {
                    coverLoading.remove(url)
                    coverFailed.add(url)
                },
            )
        }
    }

    /** 网页搜索使用当前生效服务器；未配置时返回 null，由入口提示先配置。 */
    fun webBaseUrl(): String? = ZLibraryConfigStore(context).load().servers.baseUrls.firstOrNull()

    /** 书源设置里的默认语言；用于判断筛选是否偏离默认条件，而不是只要设了语言就算激活。 */
    fun defaultLanguage(): String? =
        when (ZLibraryConfigStore(context).load().preferences.defaultLanguage) {
            "zh", "chinese" -> "chinese"
            "en", "english" -> "english"
            else -> null
        }

    private fun nextRequestSeq(id: String): Int {
        val seq = (requestSeq[id] ?: 0) + 1
        requestSeq[id] = seq
        return seq
    }

    private fun readableError(e: Throwable): String = when (e) {
        is ProviderException -> when (e.code) {
            ProviderErrorCode.NOT_CONFIGURED -> "尚未配置 Z-Library 服务器，请到 设置 → 书源设置 → Z-Library 添加"
            ProviderErrorCode.AUTH -> "登录已失效，请重新登录"
            ProviderErrorCode.QUOTA -> "下载额度已用尽"
            ProviderErrorCode.RATE_LIMITED -> "请求过于频繁，请稍后再试"
            ProviderErrorCode.SERVER_REJECTED -> NATIVE_SEARCH_UNAVAILABLE
            // 书源适配器会把底层网络异常包装成 NETWORK/IO，需要看 cause 才能给出可操作提示。
            ProviderErrorCode.NETWORK, ProviderErrorCode.IO -> networkMessage(e.cause ?: e)
            else -> e.message ?: "请求失败，请稍后重试"
        }
        else -> networkMessage(e)
    }

    /** 网络类异常统一转成中文可操作提示，不把英文连接串抛给用户。 */
    private fun networkMessage(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "无法解析服务器地址，请检查网络或代理设置"
        is java.net.SocketTimeoutException -> "请求超时，请检查网络后重试"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络或代理设置"
        is java.net.ConnectException -> "网络不可达，请检查网络或代理设置后重试"
        is java.io.IOException -> "网络连接中断，请检查网络后重试"
        else -> "网络请求失败，请检查网络或代理设置后重试"
    }
}
