package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** 诊断事件 DAO。 */
@Dao
interface DiagnosticEventDao {
    @Insert
    suspend fun insert(entity: DiagnosticEventEntity): Long

    /** 按时间升序读取全部。 */
    @Query("SELECT * FROM diagnostic_event ORDER BY at ASC")
    suspend fun allAsc(): List<DiagnosticEventEntity>

    /** 删除最旧的 N 条（用于上限淘汰，超出保留数时清理）。 */
    @Query("DELETE FROM diagnostic_event WHERE id IN (SELECT id FROM diagnostic_event ORDER BY at ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    /** 总数。 */
    @Query("SELECT COUNT(*) FROM diagnostic_event")
    suspend fun count(): Int
}
