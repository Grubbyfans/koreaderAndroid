package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.config.ServerCandidate
import com.yuezhou.reader.core.online.config.ServerMode
import com.yuezhou.reader.core.online.config.ServerUrlPolicy
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.config.ZLibraryConfigMigration
import com.yuezhou.reader.core.online.config.ZLibraryConfigValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZLibraryConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `默认配置通过校验且不包含凭证字段`() {
        val config = ZLibraryConfig.DEFAULT
        assertTrue(ZLibraryConfigValidator.validate(config).isEmpty(), ZLibraryConfigValidator.validate(config).joinToString())
        val text = json.encodeToString(config)
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("cookie", ignoreCase = true))
        assertEqualsConfig(config, json.decodeFromString(text))
    }

    @Test
    fun `拒绝明文HTTP地址`() {
        val bad = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(
                candidates = listOf(candidate("http://example.com", 1)),
            ),
        )
        assertTrue(ZLibraryConfigValidator.validate(bad).any { it.contains("HTTPS") })
    }

    @Test
    fun `拒绝地址中的用户名密码与查询参数`() {
        val withUser = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(
                candidates = listOf(candidate("https://user:pass@example.com", 1)),
            ),
        )
        assertTrue(ZLibraryConfigValidator.validate(withUser).any { it.contains("userinfo") || it.contains("用户名") })
        val withQuery = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(
                candidates = listOf(candidate("https://example.com?a=1", 1)),
            ),
        )
        assertTrue(ZLibraryConfigValidator.validate(withQuery).isNotEmpty())
    }

    @Test
    fun `拒绝非法分页与路径穿越`() {
        val badPage = ZLibraryConfig.DEFAULT.copy(
            preferences = ZLibraryConfig.DEFAULT.preferences.copy(pageSize = 31),
        )
        assertTrue(ZLibraryConfigValidator.validate(badPage).any { it.contains("分页") })
        val badPath = ZLibraryConfig.DEFAULT.copy(
            endpoints = ZLibraryConfig.DEFAULT.endpoints.copy(
                search = ZLibraryConfig.Endpoint("/../eapi/book/search", "POST"),
            ),
        )
        assertTrue(ZLibraryConfigValidator.validate(badPath).any { it.contains("路径穿越") })
    }

    @Test
    fun `拒绝越界schema版本与格式`() {
        val badSchema = ZLibraryConfig.DEFAULT.copy(schemaVersion = 99)
        assertTrue(ZLibraryConfigValidator.validate(badSchema).any { it.contains("schemaVersion") })
        val badVersion = ZLibraryConfig.DEFAULT.copy(configVersion = "latest")
        assertTrue(ZLibraryConfigValidator.validate(badVersion).any { it.contains("configVersion") })
    }

    @Test
    fun `拒绝本地与私有网络地址`() {
        listOf(
            "https://localhost",
            "https://127.0.0.1",
            "https://10.0.0.1",
            "https://192.168.1.1",
            "https://169.254.1.1",
        ).forEach { url ->
            assertNotNull(ServerUrlPolicy.validate(url), "$url 应被拒绝")
        }
        assertEquals(null, ServerUrlPolicy.validate("https://example.com"))
    }

    @Test
    fun `规范化地址用于判重`() {
        assertEquals(
            ServerUrlPolicy.normalize("https://EXAMPLE.com:443/path/"),
            ServerUrlPolicy.normalize("https://example.com/path"),
        )
    }

    @Test
    fun `旧版字符串数组迁移为候选并按优先级重排`() {
        val legacy = ZLibraryConfig.DEFAULT.copy(
            schemaVersion = 1,
            servers = ZLibraryConfig.DEFAULT.servers.copy(
                baseUrls = listOf("https://b.example.com", "https://a.example.com"),
                candidates = emptyList(),
                manualBaseUrl = "",
            ),
        )
        val migrated = ZLibraryConfigMigration.normalize(legacy)
        assertEquals(2, migrated.schemaVersion)
        assertEquals(2, migrated.servers.candidates.size)
        assertEquals("https://b.example.com", migrated.servers.candidates[0].baseUrl)
        assertEquals(1, migrated.servers.candidates[0].priority)
        assertEquals("https://a.example.com", migrated.servers.candidates[1].baseUrl)
        assertEquals(2, migrated.servers.candidates[1].priority)
        assertEquals("https://b.example.com", migrated.servers.baseUrls.first())
        assertTrue(ZLibraryConfigValidator.validate(migrated).isEmpty())
    }

    @Test
    fun `禁用候选不进入生效地址且自动模式不重复探测`() {
        val config = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(
                candidates = listOf(
                    candidate("https://a.example.com", 1, enabled = false),
                    candidate("https://b.example.com", 2),
                ),
            ),
        )
        val normalized = ZLibraryConfigMigration.normalize(config)
        assertEquals(listOf("https://b.example.com"), normalized.servers.baseUrls)
    }

    @Test
    fun `手动模式只使用固定地址且模式保留`() {
        val config = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(
                mode = ServerMode.MANUAL.wire,
                manualBaseUrl = "https://manual.example.com",
                candidates = listOf(candidate("https://auto.example.com", 1)),
            ),
        )
        val normalized = ZLibraryConfigMigration.normalize(config)
        assertEquals(listOf("https://manual.example.com"), normalized.servers.baseUrls)
        assertEquals(1, normalized.servers.candidates.size)
        assertTrue(ZLibraryConfigValidator.validate(normalized).isEmpty())
    }

    private fun candidate(url: String, priority: Int, enabled: Boolean = true) =
        ServerCandidate("c$priority", url, enabled, priority)

    private fun assertEqualsConfig(a: ZLibraryConfig, b: ZLibraryConfig) {
        assertTrue(a == b, "配置序列化往返应保持一致")
    }
}
