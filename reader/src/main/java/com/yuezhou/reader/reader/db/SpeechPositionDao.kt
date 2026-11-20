package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 朗读位置 DAO。 */
@Dao
interface SpeechPositionDao {
    @Query("SELECT * FROM speech_position WHERE bookId = :bookId")
    suspend fun get(bookId: String): SpeechPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SpeechPositionEntity)

    @Query("DELETE FROM speech_position WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    @Query("DELETE FROM speech_position")
    suspend fun clear()
}
