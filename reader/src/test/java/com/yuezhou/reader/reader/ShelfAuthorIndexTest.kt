package com.yuezhou.reader.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShelfAuthorIndexTest {
    @Test
    fun `五百本旧书首次查询回填一次后重启不再打开 EPUB`() {
        val persisted = mutableMapOf<String, String>()
        var writes = 0
        var openings = 0
        fun index() = ShelfAuthorIndex(
            read = persisted::get,
            write = { records -> writes++; persisted.putAll(records) },
        )
        val books = (1..500).map(Int::toString)
        fun search(index: ShelfAuthorIndex) = index.resolve(books, { it }) { book ->
            cachedOrRead(book, "size-mtime") {
                openings++
                if (book == "42") listOf("Author") else emptyList()
            }
        }

        assertEquals(mapOf("42" to listOf("Author")), search(index()))
        assertEquals(500, openings)
        assertEquals(1, writes)
        assertEquals(mapOf("42" to listOf("Author")), search(index()))
        assertEquals(500, openings)
        assertEquals(1, writes)
    }

    @Test
    fun `损坏缓存查询失败和不可信文件代次都不会掩盖可读作者`() {
        val persisted = mutableMapOf("broken" to "invalid json")
        val index = ShelfAuthorIndex(persisted::get, persisted::putAll)
        index.record("replaced", "previous", listOf("Old"))

        val books = listOf("broken", "replaced", "unreadable", "unknown")
        val found = index.resolve(books, { it }) { id ->
            cachedOrRead(id, "current".takeUnless { id == "unknown" }) {
                listOf("New").takeUnless { id == "unreadable" }
            }
        }

        assertEquals(mapOf("broken" to listOf("New"), "replaced" to listOf("New"), "unknown" to listOf("New")), found)
        assertTrue("unknown" !in persisted && "unreadable" !in persisted)
    }
}
