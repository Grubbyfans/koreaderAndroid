package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 网络 TTS 规则读写。 */
@Dao
interface NetworkTtsRuleDao {

    @Query("SELECT * FROM network_tts_rule ORDER BY name COLLATE NOCASE ASC")
    suspend fun all(): List<NetworkTtsRuleEntity>

    @Query("SELECT * FROM network_tts_rule WHERE id = :id")
    suspend fun byId(id: Long): NetworkTtsRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: NetworkTtsRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<NetworkTtsRuleEntity>)

    @Query("DELETE FROM network_tts_rule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM network_tts_rule WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM network_tts_rule")
    suspend fun clear()
}
