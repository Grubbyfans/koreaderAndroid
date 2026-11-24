package com.yuezhou.reader.core.online.model

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
)
