package com.yuezhou.reader.reader.online.config

import com.yuezhou.reader.core.online.api.ServerProbeClient
import com.yuezhou.reader.core.online.config.DiscoveryDecision
import com.yuezhou.reader.core.online.config.DiscoveryState
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerDiscovery
import com.yuezhou.reader.core.online.config.ServerImportPreview
import com.yuezhou.reader.core.online.config.ZLibraryConfig

/**
 * 服务器配置协调器（增量方案 §7）：把配置持久化、无认证探测与选择策略串起来。
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

    /** 单项探测：只更新该项状态，不改变当前服务器。 */
    suspend fun probeOne(baseUrl: String, candidateId: String, config: ZLibraryConfig): ProbeResult =
        ServerProbeClient.probe(baseUrl, config, candidateId)

    /** 检测全部并按优先级选择；results 逐项回调供 UI 实时展示。 */
    suspend fun detectAll(
        config: ZLibraryConfig,
        onResult: (ProbeResult) -> Unit,
    ): DiscoveryDecision {
        discovery.invalidate()
        val decision = discovery.resolve(config, store.loadState(), ::probeWithConfig, force = true, onResult = onResult)
        if (decision.selectedBaseUrl != null && decision.results.isNotEmpty()) {
            store.saveState(
                DiscoveryState(
                    lastAvailableBaseUrl = decision.selectedBaseUrl,
                    lastCheckedAt = System.currentTimeMillis(),
                    configRevision = decision.revision,
                ),
            )
        }
        return decision
    }

    /** 使用缓存复用最近可用地址；不主动探测。 */
    suspend fun resolveCached(config: ZLibraryConfig): DiscoveryDecision =
        discovery.resolve(config, store.loadState(), ::probeWithConfig)

    private suspend fun probeWithConfig(baseUrl: String, candidateId: String): ProbeResult =
        ServerProbeClient.probe(baseUrl, loadConfig(), candidateId)

    fun invalidate() = discovery.invalidate()

    /** 为候选项生成唯一 id（时间戳 + 随机尾数），避免导入时 id 冲突。 */
    fun newCandidateId(): String = "c-${System.currentTimeMillis()}-${(Math.random() * 10000).toInt()}"
}
