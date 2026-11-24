package com.yuezhou.reader.core.online.provider

import com.yuezhou.reader.core.online.model.ConnectionState

/**
 * 书源注册表：纯逻辑，不依赖 Android Context。
 *
 * 实例由上层（reader/app）创建后注入；注册表只维护启停、默认书源与查询，
 * 不负责创建具体书源，也不持有网络客户端。
 */
class ProviderRegistry(providers: Collection<BookProvider> = emptyList()) {

    private val providers = LinkedHashMap<String, BookProvider>()
    private val enabledIds = LinkedHashSet<String>()

    /** 默认书源；始终指向一个已启用书源，或 null。 */
    var defaultProviderId: String? = null
        private set

    init {
        providers.forEach(::register)
    }

    fun register(provider: BookProvider) {
        providers[provider.id] = provider
        enabledIds.add(provider.id)
        if (defaultProviderId == null) defaultProviderId = provider.id
    }

    fun unregister(id: String) {
        providers.remove(id)
        enabledIds.remove(id)
        if (defaultProviderId == id) defaultProviderId = enabledIds.firstOrNull()
    }

    fun get(id: String): BookProvider? = providers[id]

    fun all(): List<BookProvider> = providers.values.toList()

    fun enabled(): List<BookProvider> = providers.values.filter { it.id in enabledIds }

    fun isEnabled(id: String): Boolean = id in enabledIds

    fun setEnabled(id: String, enabled: Boolean) {
        if (!providers.containsKey(id)) return
        if (enabled) {
            enabledIds.add(id)
        } else {
            enabledIds.remove(id)
            if (defaultProviderId == id) defaultProviderId = enabledIds.firstOrNull()
        }
    }

    fun setDefault(id: String) {
        if (id in enabledIds) defaultProviderId = id
    }

    suspend fun connectionStates(): Map<String, ConnectionState> =
        providers.values.associate { it.id to it.getConnectionState() }
}
