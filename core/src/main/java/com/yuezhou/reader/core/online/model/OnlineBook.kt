package com.yuezhou.reader.core.online.model

/** 服务端返回的格式字段解析结果；只有 [CONFIRMED] 可确认标准格式并进入原生下载。 */
enum class BookFormatState { CONFIRMED, UNSUPPORTED, MISSING, CONFLICT }

/** The cloud catalog version and account associated with a cover. */
data class CloudCoverReference(val path: String, val version: String, val backendId: String)

/**
 * 在线书源统一书籍模型。
 *
 * 仅用于在线书籍的展示、搜索、详情与下载；不替代本地 `bookId`，也不参与 KOReader 协议。
 * 本地书籍唯一性按文件名（含扩展名）判断，见技术方案 §8。
 */
data class OnlineBook(
    val providerId: String,
    /** 远程资源标识；与账号作用域共同定位资源，不替代本地 bookId。 */
    val remoteId: String,
    val title: String,
    /** 文件名（含扩展名），唯一判断与下载落盘依据。 */
    val fileName: String,
    val format: String = "epub",
    /** 格式解析状态；其他来源保持既有映射时默认为 [BookFormatState.CONFIRMED]。 */
    val formatState: BookFormatState = BookFormatState.CONFIRMED,
    val authors: List<String> = emptyList(),
    val coverUrl: String? = null,
    /** 云存储书源已有的封面 PNG（优先于 coverUrl 展示）。 */
    val coverPng: ByteArray? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publishYear: Int? = null,
    val isbn: String? = null,
    val language: String? = null,
    val fileSize: Long? = null,
    val remotePath: String? = null,
    /** 是否已在本地书库（按文件名判断）。 */
    val local: Boolean = false,
    val cloudCover: CloudCoverReference? = null,
) {
    /** 列表与详情的格式展示文案；缺失/冲突/不支持都给出明确状态，不伪装成 EPUB。 */
    val formatLabel: String
        get() = when (formatState) {
            BookFormatState.CONFIRMED -> format.uppercase()
            BookFormatState.UNSUPPORTED -> "${format.uppercase()} · 暂不支持"
            BookFormatState.MISSING -> "格式未知"
            BookFormatState.CONFLICT -> "格式冲突"
        }
}
