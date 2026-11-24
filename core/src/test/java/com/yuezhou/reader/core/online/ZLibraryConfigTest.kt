package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.config.ZLibraryConfigValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZLibraryConfigTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `默认配置通过校验且不包含凭证字段`() {
        val config = ZLibraryConfig.DEFAULT
        assertTrue(ZLibraryConfigValidator.validate(config).isEmpty())
        val text = json.encodeToString(config)
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
        assertFalse(text.contains("cookie", ignoreCase = true))
        assertEqualsConfig(config, json.decodeFromString(text))
    }

    @Test
    fun `拒绝明文HTTP地址`() {
        val bad = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(baseUrls = listOf("http://example.com")),
        )
        assertTrue(ZLibraryConfigValidator.validate(bad).any { it.contains("HTTPS") })
    }

    @Test
    fun `拒绝地址中的用户名密码与查询参数`() {
        val withUser = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(baseUrls = listOf("https://user:pass@example.com")),
        )
        assertTrue(ZLibraryConfigValidator.validate(withUser).any { it.contains("userinfo") || it.contains("用户名") })
        val withQuery = ZLibraryConfig.DEFAULT.copy(
            servers = ZLibraryConfig.DEFAULT.servers.copy(baseUrls = listOf("https://example.com?a=1")),
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
    fun `拒绝错误的schema版本与格式`() {
        val badSchema = ZLibraryConfig.DEFAULT.copy(schemaVersion = 2)
        assertTrue(ZLibraryConfigValidator.validate(badSchema).any { it.contains("schemaVersion") })
        val badVersion = ZLibraryConfig.DEFAULT.copy(configVersion = "latest")
        assertTrue(ZLibraryConfigValidator.validate(badVersion).any { it.contains("configVersion") })
    }

    private fun assertEqualsConfig(a: ZLibraryConfig, b: ZLibraryConfig) {
        assertTrue(a == b, "配置序列化往返应保持一致")
    }
}
