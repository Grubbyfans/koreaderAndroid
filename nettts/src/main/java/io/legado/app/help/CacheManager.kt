package io.legado.app.help

/**
 * 缓存管理器（有界内存实现）。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/CacheManager.kt`（GPLv3）。
 * `saveTime` 单位为秒，0 表示不过期；所有条目统一受 256 项 LRU 限制。
 */
@Suppress("unused")
object CacheManager {

    private data class Entry(val value: Any, val expiresAtMs: Long = 0L)

    private val memoryLru = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > 256
    }

    fun put(key: String, value: String, saveTime: Int = 0) {
        val expiresAt = if (saveTime > 0) System.currentTimeMillis() + saveTime * 1000L else 0L
        synchronized(memoryLru) { memoryLru[key] = Entry(value, expiresAt) }
    }

    fun get(key: String): String? = getFromMemory(key) as? String

    fun delete(key: String) {
        deleteMemory(key)
    }

    fun putMemory(key: String, value: String) {
        synchronized(memoryLru) { memoryLru[key] = Entry(value) }
    }

    fun getFromMemory(key: String): Any? = synchronized(memoryLru) {
        val entry = memoryLru[key] ?: return@synchronized null
        if (entry.expiresAtMs > 0L && entry.expiresAtMs <= System.currentTimeMillis()) {
            memoryLru.remove(key)
            null
        } else {
            entry.value
        }
    }

    fun deleteMemory(key: String) {
        synchronized(memoryLru) { memoryLru.remove(key) }
    }

    /** 清空进程内缓存（规则删除/退出朗读时调用）。 */
    fun clearMemory() {
        synchronized(memoryLru) { memoryLru.clear() }
    }
}
