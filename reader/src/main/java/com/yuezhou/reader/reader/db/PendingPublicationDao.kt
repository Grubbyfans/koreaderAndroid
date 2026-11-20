package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 待发布回执 DAO。 */
@Dao
interface PendingPublicationDao {
    @Query("SELECT * FROM pending_publication")
    suspend fun all(): List<PendingPublicationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingPublicationEntity)

    @Query("DELETE FROM pending_publication WHERE bookId = :bookId")
    suspend fun delete(bookId: String)

    @Query("DELETE FROM pending_publication")
    suspend fun clear()
}
