package com.yuezhou.reader.reader.online.config

import com.yuezhou.reader.core.online.api.ServerProbeClient
import com.yuezhou.reader.core.online.config.DiscoveryDecision
import com.yuezhou.reader.core.online.config.DiscoveryState
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerDiscovery
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ServerMode
import com.yuezhou.reader.core.online.config.ZLibraryConfig

/**
 * 服务器配置协调器（发现增量 §7 + 服务器设置优化 §4/§8）：
 * 把配置持久化、无认证探测与双模式选择策略串起来，供 UI 调用。
 *
 * 网络与文件 IO 均通过 suspend 函数执行，不阻塞主线程；页面关闭可取消探测协程。
 */
class ServerConfigController(private val store: ZLibraryConfigStore) {
    private val discovery = ServerDiscovery()

    fun loadConfig(): ZLibraryConfig = store.load()
    fun loadState(): DiscoveryState = store.loadState()
    fun previewImport(text: String): Result<ServerImportPreview> = store.previewImport(text)
    fun exportJson(config: ZLibraryConfig): String = store.exportJson(config)

    suspend fun saveConfig(config: ZLibraryConfig): Result<ZLibraryConfig> {
        discovery.invalidate()
        return store.save(config)
    }

    suspend fun applyImport(preview: ServerImportPreview, replace: Boolean): Result<ZLibraryConfig> {
        discovery.invalidate()
        return store.applyImport(preview, replace)
    }

    suspend fun resetServers(): Result<ZLibraryConfig> {
        discovery.invalidate()
        return store.resetServers()
    }

    suspend fun rollback(): Result<ZLibraryConfig> {
        discovery.invalidate()
        return store.rollback()
    }

    /** 单项探测：只更新该项状态，不改变当前服务器选择。 */
    suspend fun probeOne(baseUrl: String, candidateId: String, config: ZLibraryConfig): ProbeResult =
        ServerProbeClient.probe(baseUrl, config, candidateId)

    /**
     * 统一检测入口（合并原「检测全部」「重新选择服务器」）：
     * - 自动模式：按优先级检测全部已启用项并重新选择可用服务器。
     * - 手动模式：检测全部已启用项，仅更新结果，不改变当前选中的服务器。
     * results 逐项回调供 UI 实时展示。
     */
    suspend fun detectAll(
        config: ZLibraryConfig,
        onResult: (ProbeResult) -> Unit,
    ): DiscoveryDecision {
        discovery.invalidate()
        val decision = discovery.resolve(config, store.loadState(), ::probeWithConfig, force = true, onResult = onResult)
        if (decision.results.isNotEmpty()) {
            // 只有选中项实际探测可用才写入最近可用地址缓存；失败不覆盖上一次成功的缓存。
            val selectedAvailable = decision.selectedBaseUrl?.let { url ->
                decision.results.any { result ->
                    result.status == com.yuezhou.reader.core.online.config.ProbeStatus.AVAILABLE.name &&
                        url.equals(config.servers.candidates.firstOrNull { it.id == result.candidateId }?.baseUrl, ignoreCase = true)
                }
            } == true
            val state = DiscoveryState(
                lastAvailableBaseUrl = if (selectedAvailable) decision.selectedBaseUrl else null,
                lastCheckedAt = System.currentTimeMillis(),
                configRevision = decision.revision,
            )
            store.saveState(state)
        }
        return decision
    }

    /** 使用缓存复用最近可用地址；不主动探测。手动模式返回选中项。 */
    suspend fun resolveCached(config: ZLibraryConfig): DiscoveryDecision =
        discovery.resolve(config, store.loadState(), ::probeWithConfig)

    private suspend fun probeWithConfig(baseUrl: String, candidateId: String): ProbeResult =
        ServerProbeClient.probe(baseUrl, loadConfig(), candidateId)

    fun invalidate() = discovery.invalidate()

    /**
     * 切换模式时给出 manualSelectedServerId 的建议（§4.3）：
     * 1) 切到手动时优先沿用当前自动选择的服务器（仍启用）；
     * 2) 否则恢复有效的历史手动选择；
     * 3) 两者都不存在返回 null，由 UI 提示用户选择。
     */
    fun suggestManualSelection(config: ZLibraryConfig, currentAutoUrl: String?): String? {
        val enabled = config.servers.candidates.filter { it.enabled }
        val normalizedAuto = currentAutoUrl?.trim()?.trimEnd('/')?.lowercase()
        val autoMatch = enabled.firstOrNull {
            it.baseUrl.trim().trimEnd('/').lowercase() == normalizedAuto
        }
        if (autoMatch != null) return autoMatch.id
        val history = config.servers.manualSelectedServerId
        return enabled.firstOrNull { it.id == history }?.id
    }

    /** 为候选项生成唯一 id（时间戳 + 随机尾数），避免导入时 id 冲突。 */
    fun newCandidateId(): String = "c-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
}
