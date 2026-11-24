package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.api.ServerProbeClient
import com.yuezhou.reader.core.online.config.DiscoveryState
import com.yuezhou.reader.core.online.config.ProbeResult
import com.yuezhou.reader.core.online.config.ProbeStatus
import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerDiscovery
import com.yuezhou.reader.core.online.config.ServerMode
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServerDiscoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun config(
        candidates: List<ServerCandidate>,
        mode: ServerMode = ServerMode.AUTO,
        manualSelectedId: String? = null,
    ) = ZLibraryConfig.DEFAULT.copy(
        servers = ZLibraryConfig.DEFAULT.servers.copy(
            mode = mode.wire,
            manualSelectedServerId = manualSelectedId,
            candidates = candidates,
        ),
    )

    private fun candidate(id: String, priority: Int, enabled: Boolean = true) = ServerCandidate(
        id = id,
        baseUrl = "https://$id.example.com",
        enabled = enabled,
        priority = priority,
    )

    @Test
    fun `健康探测只接受预期JSON结构`() {
        assertTrue(ServerProbeClient.isExpectedStructure(json.parseToJsonElement("""{"a":[1],"b":{}}""")))
        assertFalse(ServerProbeClient.isExpectedStructure(json.parseToJsonElement("""{"a":1}""")))
        assertFalse(ServerProbeClient.isExpectedStructure(json.parseToJsonElement(""""ok"""")))
        assertFalse(ServerProbeClient.isExpectedStructure(runCatching { json.parseToJsonElement("<html>") }.getOrNull()))
    }

    @Test
    fun `选择优先级最高的可用项而非最快返回`() = runBlocking {
        val cfg = config(listOf(candidate("a", 1), candidate("b", 2), candidate("c", 3)))
        val discovery = ServerDiscovery()
        val decision = discovery.resolve(cfg, DiscoveryState(), probe = { _, id ->
            when (id) {
                "a" -> {
                    delay(120)
                    ProbeResult(id, ProbeStatus.AVAILABLE.name, 120, 0)
                }
                "b" -> {
                    delay(10)
                    ProbeResult(id, ProbeStatus.AVAILABLE.name, 10, 0)
                }
                else -> ProbeResult(id, ProbeStatus.TIMEOUT.name, null, 0)
            }
        })
        assertEquals("https://a.example.com", decision.selectedBaseUrl)
        assertEquals(3, decision.results.size)
        assertFalse(decision.fromCache)
    }

    @Test
    fun `禁用项不参与探测`() = runBlocking {
        val cfg = config(listOf(candidate("a", 1, enabled = false), candidate("b", 2)))
        val discovery = ServerDiscovery()
        val decision = discovery.resolve(cfg, DiscoveryState(), probe = { _, id ->
            ProbeResult(id, ProbeStatus.AVAILABLE.name, 50, 0)
        })
        assertEquals(listOf("b"), decision.results.map { it.candidateId })
    }

    @Test
    fun `缓存未过期时复用最近可用地址`() = runBlocking {
        val cfg = config(listOf(candidate("a", 1), candidate("b", 2)))
        val discovery = ServerDiscovery()
        val state = DiscoveryState(
            lastAvailableBaseUrl = "https://b.example.com",
            lastCheckedAt = System.currentTimeMillis(),
            configRevision = 1,
        )
        // 新实例模拟应用重启：仅靠持久化运行状态命中缓存。
        val restarted = ServerDiscovery()
        val decision = restarted.resolve(cfg, state, probe = { _, _ ->
            error("缓存命中不应再探测")
        })
        assertEquals("https://b.example.com", decision.selectedBaseUrl)
        assertTrue(decision.fromCache)
    }

    @Test
    fun `配置版本变化后旧缓存作废`() = runBlocking {
        val cfg = config(listOf(candidate("a", 1), candidate("b", 2)))
        val discovery = ServerDiscovery()
        discovery.resolve(cfg, DiscoveryState(), probe = { _, id ->
            ProbeResult(id, ProbeStatus.AVAILABLE.name, 10, 0)
        })
        discovery.invalidate()
        var probeCalls = 0
        val decision = discovery.resolve(cfg, DiscoveryState(), probe = { _, id ->
            probeCalls++
            ProbeResult(id, ProbeStatus.AVAILABLE.name, 10, 0)
        })
        assertEquals(2, probeCalls)
        assertFalse(decision.fromCache)
    }

    @Test
    fun `手动模式只返回选中候选且不探测`() = runBlocking {
        val selected = candidate("a", 1)
        val cfg = config(listOf(selected, candidate("b", 2)), mode = ServerMode.MANUAL, manualSelectedId = selected.id)
        val decision = ServerDiscovery().resolve(cfg, DiscoveryState(), probe = { _, _ ->
            error("手动模式不应探测")
        })
        assertEquals("https://a.example.com", decision.selectedBaseUrl)
        assertTrue(decision.results.isEmpty())
    }

    @Test
    fun `手动模式主动检测更新结果但不改变选择`() = runBlocking {
        val selected = candidate("a", 1)
        val other = candidate("b", 2)
        val cfg = config(listOf(selected, other), mode = ServerMode.MANUAL, manualSelectedId = selected.id)
        val decision = ServerDiscovery().resolve(cfg, DiscoveryState(), probe = { _, id ->
            ProbeResult(id, if (id == "b") ProbeStatus.AVAILABLE.name else ProbeStatus.TIMEOUT.name, 50, 0)
        }, force = true)
        // 选中项仍是 a（即使 b 探测可用），仅更新两个结果。
        assertEquals("https://a.example.com", decision.selectedBaseUrl)
        assertEquals(2, decision.results.size)
    }

    @Test
    fun `手动模式选中项被禁用时无可用选择`() = runBlocking {
        val selected = candidate("a", 1, enabled = false)
        val cfg = config(listOf(selected), mode = ServerMode.MANUAL, manualSelectedId = selected.id)
        val decision = ServerDiscovery().resolve(cfg, DiscoveryState(), probe = { _, _ ->
            error("禁用项不应被探测")
        })
        assertNull(decision.selectedBaseUrl)
    }

    @Test
    fun `同一版本并发调用共享单次探测`() = runBlocking {
        val cfg = config(listOf(candidate("a", 1)))
        val discovery = ServerDiscovery()
        var calls = 0
        val first = async {
            discovery.resolve(cfg, DiscoveryState(), probe = { _, id ->
                calls++
                delay(60)
                ProbeResult(id, ProbeStatus.AVAILABLE.name, 60, 0)
            })
        }
        val second = async {
            discovery.resolve(cfg, DiscoveryState(), probe = { _, _ ->
                error("single-flight 不应重复探测")
            })
        }
        val a = first.await()
        val b = second.await()
        assertEquals(1, calls)
        assertNotNull(a.selectedBaseUrl)
        assertTrue(b.fromCache)
        assertEquals(a.selectedBaseUrl, b.selectedBaseUrl)
    }

    @Test
    fun `全部失败返回明确原因`() = runBlocking {
        val cfg = config(listOf(candidate("a", 1)))
        val decision = ServerDiscovery().resolve(cfg, DiscoveryState(), probe = { _, id ->
            ProbeResult(id, ProbeStatus.TIMEOUT.name, null, 0)
        })
        assertNull(decision.selectedBaseUrl)
        assertNotNull(decision.reason)
    }
}
