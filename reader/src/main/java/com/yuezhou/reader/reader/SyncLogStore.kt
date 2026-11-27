package com.yuezhou.reader.reader

import android.content.Context
import android.util.Log
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.SyncLogEntity

/**
 * 自动同步记录的持久化存储。
 *
 * 追加式写入每次「拉取/上传进度」的结果，读取时按时间倒序返回最近 [MAX_ENTRIES] 条，
 * 供设置页展示。仅保存脱敏信息（方向/书名/时间/成功/消息），不含凭据与正文。
 */
class SyncLogStore(context: Context) {

    private val dao = AppDatabase.get(context).syncLogDao()

    companion object {
        const val MAX_ENTRIES = 20
        private const val TAG = "SyncLogStore"
    }

    /**
     * 追加一条同步记录，超出上限时淘汰最旧条目。
     *
     * [outcome] 取 [SyncLogEntity.Outcome]；其中 [SyncLogEntity.Outcome.OFFER]
     * 表示等待用户确认或主动跳过，不算失败。
     */
    suspend fun append(
        direction: String,
        bookTitle: String,
        outcome: String,
        message: String,
    ) {
        try {
            dao.insert(
                SyncLogEntity(
                    direction = direction,
                    bookTitle = bookTitle,
                    at = System.currentTimeMillis(),
                    success = outcome != SyncLogEntity.Outcome.FAILED,
                    message = message,
                    outcome = outcome,
                ),
            )
            val total = dao.count()
            if (total > MAX_ENTRIES) {
                dao.deleteOldest(total - MAX_ENTRIES)
            }
        } catch (e: Exception) {
            // 写失败不影响主流程
            Log.w(TAG, "同步记录写入失败 direction=$direction", e)
        }
    }

    /** 读取最近 [MAX_ENTRIES] 条记录（时间倒序）。 */
    suspend fun recent(): List<SyncLogEntity> = dao.recent(MAX_ENTRIES)
}
