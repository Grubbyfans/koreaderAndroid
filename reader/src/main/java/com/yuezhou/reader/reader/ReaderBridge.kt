package com.yuezhou.reader.reader

/**
 * 阅读器桥接事件：由 WebView 内的 foliate-js 回传，供 Kotlin 层消费。
 */
sealed interface ReaderEvent {
    data class JumpVerified(val requestId: String, val verified: Boolean) : ReaderEvent
    /** 书籍已打开。 */
    data class BookOpened(val sections: Int, val title: String) : ReaderEvent
    /** 位置变化（翻页/跳转），携带 CFI。 */
    data class Relocated(
        val cfi: String?,
        val index: Int?,
        val fraction: Float?,
        val tocId: Int?,
        val tocHref: String?,
    ) : ReaderEvent
    /** 目录（TOC）已就绪，携带目录树。 */
    data class TocLoaded(val items: List<TocItem>) : ReaderEvent
    /** 当前章节正文文本已提取（朗读用），携带 { cfi, text } 列表。 */
    data class ChapterText(val items: List<ChapterTextNode>, val index: Int?) : ReaderEvent
    /** 错误。 */
    data class Error(val message: String) : ReaderEvent
}

/** 章节中的一个文本节点：文本 + 其起始位置的 CFI 锚点。 */
data class ChapterTextNode(
    val cfi: String,
    val text: String,
)

/** 目录项（对应 foliate-js book.toc 的层级结构）。 */
data class TocItem(
    val label: String,
    val href: String,
    /** 子章节；空列表表示叶子节点。 */
    val subitems: List<TocItem> = emptyList(),
    val id: Int? = null,
)

/**
 * Kotlin 侧桥接接口：暴露给 WebView JS 层调用（@JavascriptInterface）。
 * 保持方法签名稳定，JS 侧 `window.__reader` 与之对应。
 */
interface ReaderBridge {
    /**
     * JS 回传事件。data 为 JSON 字符串，由实现方解析。
     * 注意：@JavascriptInterface 方法必须在 UI 线程被调用，且参数为原始类型/String。
     */
    fun onReaderEvent(type: String, data: String)
}
