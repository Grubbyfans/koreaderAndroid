package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 待发布回执 DAO。 */
@Dao
interface PendingPublicationDao {
    @Query("SELECT * FROM pending_publication WHERE backendId = :backendId ORDER BY savedAt")
    suspend fun all(backendId: String): List<PendingPublicationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingPublicationEntity)

    @Query("DELETE FROM pending_publication WHERE backendId = :backendId AND bookId = :bookId")
    suspend fun delete(backendId: String, bookId: String)

    @Query("DELETE FROM pending_publication")
    suspend fun clear()
}
