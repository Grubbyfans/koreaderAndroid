package com.yuezhou.reader.core.online.config

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** 一次发现/检测的决策结果。 */
data class DiscoveryDecision(
    val selectedBaseUrl: String?,
    val results: List<ProbeResult>,
    val fromCache: Boolean,
    val revision: Long,
    val reason: String? = null,
)

/**
 * 服务器选择与检测策略（发现增量方案 §5/§7 + 服务器设置优化 §4/§8）。
 *
 * 自动选择（auto）：
 * - 优先复用最近一次成功且仍启用的服务器（TTL 缓存，缓存期内不重复探测全部）。
 * - 用户主动检测（force）时按优先级分批探测全部已启用项，按优先级而非耗时选择。
 * - 单飞：同一配置版本共享一次探测；配置变化通过 revision 作废旧任务结果。
 *
 * 手动选择（manual）：
 * - 只使用 manualSelectedServerId 指向的候选项，不主动探测、不自动切换。
 * - 用户主动检测（force）时探测全部已启用项以更新状态，但选择保持不变。
 *
 * 缓存、超时、并发与防竞态机制沿用增量方案，未做破坏性改动。
 */
class ServerDiscovery {
    private val mutex = Mutex()
    private val revisionCounter = AtomicLong(1L)
    private var cached: DiscoveryDecision? = null

    /** 配置变化时调用：递增版本号，丢弃旧探测结果。 */
    fun invalidate() {
        revisionCounter.incrementAndGet()
        cached = null
    }

    suspend fun resolve(
        config: ZLibraryConfig,
        state: DiscoveryState,
        probe: suspend (String, String) -> ProbeResult,
        force: Boolean = false,
        onResult: ((ProbeResult) -> Unit)? = null,
    ): DiscoveryDecision = mutex.withLock {
        val revision = revisionCounter.get()
        val mode = ServerMode.fromWire(config.servers.mode)

        // 手动选择：从统一列表中按 id 取选中项；不探测，除非用户主动检测全部。
        if (mode == ServerMode.MANUAL) {
            val selected = selectedManual(config)
            if (!force) {
                return@withLock DiscoveryDecision(selected?.baseUrl, emptyList(), false, revision)
            }
            // 主动检测：探测全部已启用项更新状态，但选择保持不变（§4.3 / §8.1）。
            val enabled = config.servers.candidates.filter { it.enabled }
            if (enabled.isEmpty()) {
                return@withLock DiscoveryDecision(
                    selected?.baseUrl,
                    emptyList(),
                    false,
                    revision,
                    "没有已启用的服务器",
                )
            }
            val results = runBatches(enabled, config, revision, probe, onResult)
            if (revision != revisionCounter.get()) {
                return@withLock DiscoveryDecision(selected?.baseUrl, results, false, revision, "配置已变更，本次探测结果已作废")
            }
            return@withLock DiscoveryDecision(selected?.baseUrl, results, false, revision)
        }

        // 自动选择：候选为空时无服务器可选。
        val enabled = config.servers.candidates.filter { it.enabled }
        if (enabled.isEmpty()) {
            return@withLock DiscoveryDecision(null, emptyList(), false, revision, "没有已启用的服务器")
        }

        if (!force) {
            // 进程内缓存：同一配置版本共享上一次决策（single-flight 结果复用）。
            val previous = cached
            if (previous != null && previous.revision == revision && previous.selectedBaseUrl != null) {
                return@withLock previous.copy(fromCache = true)
            }
            // 持久化运行状态缓存：最近一次成功连接且仍启用且在有效期内，直接复用。
            val cachedUrl = state.lastAvailableBaseUrl
            val checkedAt = state.lastCheckedAt
            val ttlMs = config.discovery.cacheTtlSeconds * 1000L
            val stillEnabled = enabled.any { normalize(it.baseUrl) == cachedUrl?.let(::normalize) }
            if (cachedUrl != null && checkedAt != null && stillEnabled &&
                System.currentTimeMillis() - checkedAt <= ttlMs
            ) {
                return@withLock DiscoveryDecision(cachedUrl, emptyList(), true, revision)
            }
        }

        val results = runBatches(enabled, config, revision, probe, onResult)
        if (revision != revisionCounter.get()) {
            // 探测期间配置变更：结果作废，不提交。
            return@withLock DiscoveryDecision(null, results, false, revision, "配置已变更，本次探测结果已作废")
        }

        // 按优先级选择最高优先级的可用项（不以响应耗时作为唯一依据）。
        val availableByPriority = enabled.mapNotNull { candidate ->
            results.firstOrNull {
                it.candidateId == candidate.id && it.status == ProbeStatus.AVAILABLE.name
            }?.let { candidate.baseUrl to candidate.priority }
        }.minByOrNull { it.second }?.first

        val decision = DiscoveryDecision(
            selectedBaseUrl = availableByPriority,
            results = results,
            fromCache = false,
            revision = revision,
            reason = if (availableByPriority == null) "所有候选服务器均不可用" else null,
        )
        cached = decision
        return@withLock decision
    }

    /** 手动模式下当前选中的候选项：必须存在且启用。 */
    private fun selectedManual(config: ZLibraryConfig): ServerCandidate? =
        config.servers.manualSelectedServerId?.let { id ->
            config.servers.candidates.firstOrNull { it.id == id && it.enabled }
        }

    /** 按优先级分批并发探测；每批结束检查 revision，过期则提前终止。 */
    private suspend fun runBatches(
        enabled: List<ServerCandidate>,
        config: ZLibraryConfig,
        revision: Long,
        probe: suspend (String, String) -> ProbeResult,
        onResult: ((ProbeResult) -> Unit)?,
    ): List<ProbeResult> {
        val results = mutableListOf<ProbeResult>()
        val batches = enabled.sortedBy { it.priority }.chunked(config.discovery.maxConcurrentProbes.coerceIn(1, 8))
        for (batch in batches) {
            if (revision != revisionCounter.get()) break
            val batchResults = coroutineScope {
                batch.map { candidate ->
                    async {
                        val result = probe(candidate.baseUrl, candidate.id)
                        onResult?.invoke(result)
                        result
                    }
                }.awaitAll()
            }
            results.addAll(batchResults)
        }
        return results
    }

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
}
