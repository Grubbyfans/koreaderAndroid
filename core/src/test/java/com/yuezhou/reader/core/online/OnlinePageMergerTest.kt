package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.model.OnlineBook
import com.yuezhou.reader.core.online.model.OnlinePageMerger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OnlinePageMergerTest {
    private fun book(id: String) = OnlineBook(providerId = "zlibrary", remoteId = id, title = id, fileName = "$id.epub")

    @Test
    fun `分页追加按remoteId去重且保持顺序`() {
        val first = listOf(book("1"), book("2"))
        val second = listOf(book("2"), book("3"))
        assertEquals(
            listOf("1", "2", "3"),
            OnlinePageMerger.append(first, second).map { it.remoteId },
        )
    }

    @Test
    fun `空页与重复页不会改写原列表`() {
        val first = listOf(book("1"))
        assertEquals(first, OnlinePageMerger.append(first, emptyList()))
        assertEquals(first, OnlinePageMerger.append(first, listOf(book("1"))))
    }
}
