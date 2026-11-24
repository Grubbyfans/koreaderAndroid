package com.yuezhou.reader.core.online.config

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** 一次发现的决策结果。 */
data class DiscoveryDecision(
    val selectedBaseUrl: String?,
    val results: List<ProbeResult>,
    val fromCache: Boolean,
    val revision: Long,
    val reason: String? = null,
)

/**
 * 自动发现策略（增量方案 §5/§7）。
 *
 * - single-flight：同一配置版本共享一次探测；不同版本通过 revision 作废旧任务结果。
 * - 按优先级分批，最大并发由配置控制；高优先级批次未结束不提交低优先级结果。
 * - 选择优先级最高的通过项，而非最快返回项。
 * - 缓存未过期且地址仍启用/仍在列表中时直接复用。
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
        if (mode == ServerMode.MANUAL) {
            val manual = config.servers.manualBaseUrl.trim().trimEnd('/')
            return@withLock DiscoveryDecision(manual.ifBlank { null }, emptyList(), false, revision)
        }

        val enabled = config.servers.candidates.filter { it.enabled }
        if (enabled.isEmpty()) {
            return@withLock DiscoveryDecision(null, emptyList(), false, revision, "没有启用的候选服务器")
        }

        if (!force) {
            // 进程内缓存：同一配置版本共享上一次决策（single-flight 结果复用）。
            val previous = cached
            if (previous != null && previous.revision == revision && previous.selectedBaseUrl != null) {
                return@withLock previous.copy(fromCache = true)
            }
            // 持久化运行状态缓存：应用重启后仍可复用最近可用地址。
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

        val results = mutableListOf<ProbeResult>()
        val batches = enabled.chunked(config.discovery.maxConcurrentProbes.coerceIn(1, 8))
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
        if (revision != revisionCounter.get()) {
            // 探测期间配置变更：结果作废，不提交。
            return@withLock DiscoveryDecision(null, results, false, revision, "配置已变更，本次探测结果已作废")
        }

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

    private fun normalize(url: String): String = url.trim().trimEnd('/').lowercase()
}
