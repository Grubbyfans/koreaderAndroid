package com.yuezhou.reader.core.online.model

/**
 * 检索结果的纯逻辑合并：分页追加时按 remoteId 去重，避免服务端重复返回或重复点击加载更多
 * 造成列表出现重复条目。账号作用域与 provider 由调用方的状态容器隔离，这里只合并同一页内的书籍。
 */
object OnlinePageMerger {
    fun append(current: List<OnlineBook>, incoming: List<OnlineBook>): List<OnlineBook> {
        if (incoming.isEmpty()) return current
        val seen = HashSet<String>(current.size + incoming.size)
        val result = ArrayList<OnlineBook>(current.size + incoming.size)
        current.forEach { book ->
            if (seen.add(book.remoteId)) result.add(book)
        }
        incoming.forEach { book ->
            if (seen.add(book.remoteId)) result.add(book)
        }
        return result
    }
}
