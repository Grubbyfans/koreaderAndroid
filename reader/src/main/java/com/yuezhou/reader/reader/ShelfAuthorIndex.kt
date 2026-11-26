package com.yuezhou.reader.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A rebuildable per-book index. Unreadable EPUBs and sources without a reliable stamp are retried. */
internal class ShelfAuthorIndex(
    private val read: (String) -> String?,
    private val write: (Map<String, String>) -> Unit,
) {
    @Serializable
    private data class Record(val stamp: String, val authors: List<String>)

    private fun encode(stamp: String, authors: List<String>): String? {
        if (authors.size > 32 || authors.sumOf(String::length) > 2048) return null
        return Json.encodeToString(Record(stamp, authors))
    }

    fun record(bookId: String, stamp: String, authors: List<String>) {
        encode(stamp, authors)?.let { write(mapOf(bookId to it)) }
    }

    inner class Batch {
        private val updates = mutableMapOf<String, String>()

        fun cachedOrRead(bookId: String, stamp: String?, load: () -> List<String>?): List<String> {
            if (stamp != null) {
                val cached = runCatching {
                    (updates[bookId] ?: read(bookId))?.let { Json.decodeFromString<Record>(it) }
                }.getOrNull()
                if (cached?.stamp == stamp) return cached.authors
            }

            val authors = load() ?: return emptyList()
            if (stamp != null) encode(stamp, authors)?.let { updates[bookId] = it }
            return authors
        }

        fun save() {
            if (updates.isNotEmpty()) runCatching { write(updates) }
        }
    }

    fun <T> resolve(
        books: List<T>,
        bookId: (T) -> String,
        lookup: Batch.(T) -> List<String>,
    ): Map<String, List<String>> {
        val batch = Batch()
        return buildMap {
            for (book in books) {
                val authors = batch.lookup(book)
                if (authors.isNotEmpty()) put(bookId(book), authors)
            }
        }.also { batch.save() }
    }
}
