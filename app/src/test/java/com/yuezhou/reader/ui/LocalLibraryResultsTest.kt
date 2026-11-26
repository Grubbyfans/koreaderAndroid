package com.yuezhou.reader.ui

import com.yuezhou.reader.reader.LibraryStore
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalLibraryResultsTest {
    @Test
    fun `作者书名与文件名搜索继续服从阅读筛选和排序`() {
        val active = entry("river", "Rivers", 0.5, 5L)
        val newer = entry("garden", "Garden", 0.1, 9L)
        val finished = entry("other", "River Review", 1.0, 10L)
        val books = listOf(active, newer, finished)
        val authors = mapOf("garden" to listOf("River Li"))
        assertEquals(
            listOf(newer, active),
            localLibraryResults(books, "river", ReadingFilter.READING, LibrarySort.RECENT, authors),
        )
        assertEquals(
            listOf(finished),
            localLibraryResults(books, "other.epub", ReadingFilter.ALL, LibrarySort.TITLE, authors),
        )
    }

    private fun entry(id: String, title: String, fraction: Double, time: Long) = LibraryStore.Entry(
        bookId = id,
        fileName = "$id.epub",
        size = 1L,
        coverPng = null,
        title = title,
        percent = fraction,
        location = "saved-cfi",
        lastReadAt = time,
        sourceUri = null,
        localPath = "$id.epub",
    )
}
