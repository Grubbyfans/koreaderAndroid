package io.legado.app.help

import java.util.concurrent.ConcurrentHashMap

/**
 * 缓存管理器（内存实现）。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/CacheManager.kt`（GPLv3）。
 * 裁剪：原版会把键值持久化到 Room（cache 表）。静读侧网络朗读的登录信息/变量属于
 * 独立关注点，改由 [io.legado.app.nettts.NetworkTtsRuntime.credentials] 落到加密存储；
 * 本类只负责进程内缓存，语义（put/get/delete + 内存层）与原版一致。
 */
@Suppress("unused")
object CacheManager {

    private val memoryCache = ConcurrentHashMap<String, Any>()
    private val memoryLru = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean =
            size > 256
    }

    /** 写入缓存；[saveTime] 仅为与原版签名兼容，内存实现忽略。 */
    fun put(key: String, value: String, saveTime: Int = 0) {
        memoryCache[key] = value
        synchronized(memoryLru) { memoryLru[key] = value }
    }

    fun get(key: String): String? {
        return (memoryCache[key] as? String)
            ?: synchronized(memoryLru) { memoryLru[key] as? String }
    }

    fun delete(key: String) {
        memoryCache.remove(key)
        synchronized(memoryLru) { memoryLru.remove(key) }
    }

    fun putMemory(key: String, value: String) {
        memoryCache[key] = value
    }

    fun getFromMemory(key: String): Any? {
        return memoryCache[key]
    }

    fun deleteMemory(key: String) {
        memoryCache.remove(key)
    }

    /** 清空进程内缓存（规则删除/退出朗读时调用）。 */
    fun clearMemory() {
        memoryCache.clear()
        synchronized(memoryLru) { memoryLru.clear() }
    }
}
