package com.yuezhou.reader.reader

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 待发布回执持久化，对齐方案 §11.5/11.6。
 *
 * 「正文上传成功但列表发布失败」时保存一条待发布记录，之后补全时只读取最新列表
 * 并合并该条目，不重传正文。新一次同名上传或删除使旧回执失效。
 *
 * 落盘于 `files/pending_publications.json`（应用私有目录，不参与备份）。
 */
@Serializable
data class PendingPublication(
    val bookId: String,
    val fileName: String,
    val size: Long,
    val coverPath: String?,
    val coverVersion: String?,
    val savedAt: Long,
)

class PendingPublicationStore(private val context: Context) {

    private val file: File get() = File(context.filesDir, "pending_publications.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** 读取全部待发布回执；文件不存在或损坏返回空列表。 */
    fun load(): List<PendingPublication> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<List<PendingPublication>>(file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 写入一条待发布回执（同名覆盖）。 */
    fun upsert(pub: PendingPublication) {
        try {
            val list = load().filter { it.bookId != pub.bookId } + pub
            file.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            // 回执写失败不阻断主流程（最坏情况是下次上传时重传正文）
        }
    }

    /** 移除某本书的待发布回执（发布成功或删除后调用）。 */
    fun remove(bookId: String) {
        try {
            val list = load().filter { it.bookId != bookId }
            if (list.isEmpty()) file.delete() else file.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 清空全部回执。 */
    fun clear() {
        try { file.delete() } catch (e: Exception) { /* 忽略 */ }
    }
}
