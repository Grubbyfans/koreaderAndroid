package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 在线书籍与本地书籍的关联，仅用于展示下载状态与「阅读」入口。
 * 明确不作为书籍重复判断依据；判重只看文件名（含扩展名）。
 */
@Entity(tableName = "online_book_mapping", primaryKeys = ["providerId", "accountScope", "remoteId"])
data class OnlineBookMappingEntity(
    val providerId: String,
    val accountScope: String,
    val remoteId: String,
    val fileName: String,
    val localBookId: String,
    val downloadedAt: Long,
    val active: Boolean = true,
)

@Dao
interface OnlineBookMappingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(mapping: OnlineBookMappingEntity)

    @Query("SELECT * FROM online_book_mapping WHERE providerId = :providerId AND accountScope = :accountScope AND remoteId = :remoteId")
    suspend fun find(providerId: String, accountScope: String, remoteId: String): OnlineBookMappingEntity?

    @Query("SELECT remoteId FROM online_book_mapping WHERE active = 1")
    fun observeActiveRemoteIds(): Flow<List<String>>

    @Query("UPDATE online_book_mapping SET active = 0 WHERE fileName = :fileName")
    suspend fun deactivateByFileName(fileName: String)
}
