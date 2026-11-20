package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.PendingPublicationEntity
import kotlinx.serialization.Serializable

/**
 * 待发布回执持久化，对齐方案 §11.5/11.6。
 *
 * 「正文上传成功但列表发布失败」时保存一条待发布记录，之后补全时只读取最新列表
 * 并合并该条目，不重传正文。新一次同名上传或删除使旧回执失效。
 *
 * 存储改用 Room（`pending_publication` 表，方案 §8.2），以 backendId 和 bookId 为主键，
 * upsert/remove 具备事务性与按 key 查询能力。
 */
@Serializable
data class PendingPublication(
    val backendId: String,
    val bookId: String,
    val fileName: String,
    val size: Long,
    val coverPath: String?,
    val coverVersion: String?,
    val savedAt: Long,
)

class PendingPublicationStore(context: Context) {

    private val dao = AppDatabase.get(context).pendingPublicationDao()

    /** 读取全部待发布回执；空则返回空列表。 */
    suspend fun load(backendId: String): List<PendingPublication> = dao.all(backendId).map { it.toDomain() }

    /** 写入一条待发布回执（同名覆盖）。 */
    suspend fun upsert(pub: PendingPublication) {
        try {
            dao.upsert(PendingPublicationEntity.fromDomain(pub))
        } catch (e: Exception) {
            // 回执写失败不阻断主流程（最坏情况是下次上传时重传正文）
        }
    }

    /** 移除某本书的待发布回执（发布成功或删除后调用）。 */
    suspend fun remove(backendId: String, bookId: String) {
        try {
            dao.delete(backendId, bookId)
        } catch (e: Exception) {
            // 忽略
        }
    }

    /** 清空全部回执。 */
    suspend fun clear() {
        try {
            dao.clear()
        } catch (e: Exception) { /* 忽略 */ }
    }
}
