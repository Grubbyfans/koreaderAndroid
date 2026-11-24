package com.yuezhou.reader.core.online.model

/** 在线书源检索条件。 pageSize 默认 30，对齐参考实现的 limit。 */
data class SearchQuery(
    val keyword: String,
    val language: String? = null,
    val format: String? = "epub",
    /** UI 层排序标识；Z-Library 适配器负责映射为参考实现的 order 数组。 */
    val sort: String = "relevance",
    val cursor: String? = null,
    val pageSize: Int = 30,
)

/** 在线书源分页结果。 */
data class BookPage(
    val items: List<OnlineBook>,
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val totalCount: Int? = null,
    /** 是否来自本地缓存（云存储书源沿用现有 CloudCache 语义）。 */
    val fromCache: Boolean = false,
    /** 最后成功获取时间（epoch millis）。 */
    val fetchedAt: Long = 0L,
)
