package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.model.BookDownloadRequest
import com.yuezhou.reader.core.online.model.ConnectionState
import com.yuezhou.reader.core.online.model.DownloadResult
import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.provider.BookProvider
import com.yuezhou.reader.core.online.provider.ProviderCapability
import com.yuezhou.reader.core.online.provider.ProviderRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderContractTest {

    private class FakeProvider(
        override val id: String,
        override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.BROWSE),
        private val state: ConnectionState = ConnectionState.Connected,
    ) : BookProvider {
        override val displayName: String get() = id
        override suspend fun getConnectionState(): ConnectionState = state
        override suspend fun getBookDetail(remoteId: String): OnlineBook =
            OnlineBook(providerId = id, remoteId = remoteId, title = remoteId, fileName = "$remoteId.epub")
        override suspend fun download(
            request: BookDownloadRequest,
            onProgress: (Long, Long?) -> Unit,
            isBookOpen: (String) -> Boolean,
        ): DownloadResult = DownloadResult.Success(request.fileName, request.remoteId)
    }

    @Test
    fun `注册表按注册顺序维护书源与默认书源`() = runTest {
        val first = FakeProvider("baidu")
        val second = FakeProvider("webdav")
        val registry = ProviderRegistry(listOf(first, second))

        assertEquals(listOf("baidu", "webdav"), registry.all().map { it.id })
        assertEquals("baidu", registry.defaultProviderId)
        assertTrue(registry.isEnabled("webdav"))
        assertEquals(
            mapOf("baidu" to ConnectionState.Connected, "webdav" to ConnectionState.Connected),
            registry.connectionStates(),
        )
    }

    @Test
    fun `禁用默认书源后自动回退到下一个启用书源`() {
        val registry = ProviderRegistry(listOf(FakeProvider("baidu"), FakeProvider("webdav")))
        registry.setDefault("webdav")
        registry.setEnabled("webdav", false)

        assertEquals("baidu", registry.defaultProviderId)
        assertFalse(registry.isEnabled("webdav"))
        assertEquals(listOf("baidu"), registry.enabled().map { it.id })

        registry.setEnabled("webdav", true)
        assertEquals(listOf("baidu", "webdav"), registry.enabled().map { it.id })
    }

    @Test
    fun `注销书源后不再可查询且默认书源同步更新`() {
        val registry = ProviderRegistry(listOf(FakeProvider("baidu"), FakeProvider("webdav")))
        registry.unregister("baidu")

        assertNull(registry.get("baidu"))
        assertEquals("webdav", registry.defaultProviderId)
    }

    @Test
    fun `能力声明区分云存储书源与检索书源`() {
        val cloud = FakeProvider("webdav", setOf(ProviderCapability.BROWSE, ProviderCapability.UPLOAD))
        val catalog = FakeProvider("zlibrary", setOf(ProviderCapability.SEARCH, ProviderCapability.DOWNLOAD))

        assertTrue(ProviderCapability.UPLOAD in cloud.capabilities)
        assertTrue(ProviderCapability.UPLOAD !in catalog.capabilities)
        assertTrue(ProviderCapability.SEARCH in catalog.capabilities)
    }
}
