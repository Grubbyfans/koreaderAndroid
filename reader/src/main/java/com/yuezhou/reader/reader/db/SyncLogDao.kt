package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 同步记录 DAO。 */
@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entity: SyncLogEntity): Long

    /** 按时间倒序读取最近 N 条。 */
    @Query("SELECT * FROM sync_log ORDER BY at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SyncLogEntity>

    /** 删除最旧的 N 条（用于上限淘汰）。 */
    @Query("DELETE FROM sync_log WHERE id IN (SELECT id FROM sync_log ORDER BY at ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    /** 总数。 */
    @Query("SELECT COUNT(*) FROM sync_log")
    suspend fun count(): Int
}
