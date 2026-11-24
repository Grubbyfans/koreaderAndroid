package com.yuezhou.reader.ui.online

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuezhou.reader.core.cloud.BackendKind
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.provider.CloudBookProvider
import com.yuezhou.reader.core.online.provider.ProviderRegistry
import com.yuezhou.reader.reader.CloudCredentialStore
import com.yuezhou.reader.reader.CloudSync
import com.yuezhou.reader.reader.online.cloud.BaiduBookProvider
import com.yuezhou.reader.reader.online.cloud.WebDavBookProvider
import kotlinx.coroutines.launch

/** 单个书源的页面状态：搜索/筛选/分页/滚动由后续检索书源阶段扩展，当前保存列表与加载态。 */
data class ProviderUiState(
    val items: List<OnlineBook> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val backendLabel: String = "",
    val fetchedAt: Long = 0L,
    val fromCache: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * 在线书库统一入口 ViewModel。
 *
 * 只承载云端作用域内已有书源；Z-Library 检索页在阶段 C/D 接入。
 * 每个书源独立保存状态，切换书源不重复加载已加载数据；主动刷新才强制重拉。
 */
class OnlineLibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val cloudSync = CloudSync(context)
    private val baiduProvider = BaiduBookProvider(context, cloudSync)
    private val webDavProvider = WebDavBookProvider(context, cloudSync)

    val registry = ProviderRegistry(listOf(baiduProvider, webDavProvider))

    var selectedProviderId: String by mutableStateOf("baidu")
        private set

    private val states = mutableStateMapOf<String, ProviderUiState>()

    val selectedState: ProviderUiState
        get() = states[selectedProviderId] ?: ProviderUiState()

    fun stateOf(providerId: String): ProviderUiState = states[providerId] ?: ProviderUiState()

    init {
        // 沿用用户已有的同步后端选择，避免切换入口后目标云端发生变化。
        viewModelScope.launch {
            val active = CloudCredentialStore(context).load().activeBackend
            if (active == "webdav") {
                selectedProviderId = "webdav"
                loadSelected()
            }
        }
    }

    fun selectedBackendKind(): BackendKind? = when (selectedProviderId) {
        "baidu" -> BackendKind.Baidu
        "webdav" -> BackendKind.WebDav
        else -> null
    }

    fun selectProvider(providerId: String) {
        if (providerId == selectedProviderId || registry.get(providerId) == null) return
        selectedProviderId = providerId
        viewModelScope.launch { loadSelected() }
    }

    /** 加载当前书源列表；已加载且非强制刷新时直接复用，避免切换书源重复请求。 */
    suspend fun loadSelected(force: Boolean = false): ProviderUiState {
        val id = selectedProviderId
        val current = stateOf(id)
        if (current.loaded && !force && !current.loading) return current

        val provider = registry.get(id) as? CloudBookProvider
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
                    val changed = runCatching { cloudSync.discoverExternalBooks(forced = selectedBackendKind()) }
                        .getOrDefault(0)
                    if (changed > 0) loadSelected(force = true)
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

    /** 主动更新：先登记外部书籍，再强制重拉当前书源列表。 */
    suspend fun refreshSelected(): Pair<ProviderUiState, String?> {
        val kind = selectedBackendKind() ?: return selectedState to "请先配置云同步后端"
        val message = try {
            cloudSync.refreshLibrary(forced = kind)
        } catch (e: Exception) {
            val failed = selectedState.copy(refreshing = false, error = e.message ?: "更新书库失败")
            states[selectedProviderId] = failed
            return failed to "更新书库失败：${e.message ?: "未知错误"}"
        }
        return loadSelected(force = true) to message
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
}
