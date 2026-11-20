package com.yuezhou.reader.core.cloud

/**
 * 云端条目（文件或目录）的元数据，对应插件 `list`/`stat` 返回的 entry。
 *
 * @param path 相对同步根目录的路径（如 "books/书名.epub"）。
 * @param type 条目类型。
 * @param size 字节数，目录或未知时为 0。
 * @param etag WebDAV 强/弱 ETag；百度无此概念，为 null。
 * @param fsId 百度文件 ID（数字字符串，可能超 2^53，必须保字符串）；WebDAV 为 null。
 * @param md5 百度文件 MD5；WebDAV 为 null。
 * @param version 用于判重的稳定版本串（WebDAV 用 ETag，百度用 fs_id/size/mtime/md5 组合）。
 */
data class CloudEntry(
    val path: String,
    val type: EntryType,
    val size: Long = 0,
    val etag: String? = null,
    val weakEtag: Boolean = false,
    val fsId: String? = null,
    val md5: String? = null,
    val version: String? = null,
)

enum class EntryType { File, Directory }

/** stat 结果。 */
data class CloudStat(
    val type: EntryType,
    val size: Long = 0,
    val etag: String? = null,
    val weakEtag: Boolean = false,
    val modified: String? = null,
)
