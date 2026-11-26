package com.yuezhou.reader.reader.animation

import android.graphics.Bitmap

/** A page identity is valid only within one book and layout generation. */
data class PageSnapshotKey(
    val bookSession: Long,
    val layoutRevision: Long,
    val cfi: String,
    val width: Int,
    val height: Int,
)

/** Bounded strong references. Recorded Canvas commands may retain evicted images until their frame retires. */
class PageSnapshotPool(private val byteLimit: Long = 32L * 1024 * 1024) : AutoCloseable {
    private val pages = LinkedHashMap<PageSnapshotKey, Bitmap>(3, .75f, true)
    val bytes: Long get() = pages.values.sumOf { it.allocationByteCount.toLong() }

    fun get(key: PageSnapshotKey): Bitmap? = pages[key]

    fun put(key: PageSnapshotKey, bitmap: Bitmap) {
        require(!bitmap.isRecycled)
        require(bitmap.width == key.width && bitmap.height == key.height)
        pages.remove(key)
        pages[key] = bitmap
        while (pages.size > 3 || bytes > byteLimit) {
            val oldest = pages.entries.iterator().next()
            pages.remove(oldest.key)
        }
    }

    override fun close() {
        pages.clear()
    }
}
