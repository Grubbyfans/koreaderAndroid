package com.yuezhou.reader.reader.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 在线下载任务持久化（阶段 E1）。状态以字符串保存枚举名，避免迁移时改列类型。 */
@Entity(tableName = "online_download_task")
data class OnlineDownloadTaskEntity(
    @PrimaryKey val taskId: String,
    val providerId: String,
    val accountScope: String,
    val remoteId: String,
    val bookTitle: String,
    val fileName: String,
    val status: String,
    val receivedBytes: Long,
    val totalBytes: Long?,
    val localPath: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val attempts: Int,
    val overwriteApproved: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val localBookId: String?,
)

@Dao
interface OnlineDownloadTaskDao {
    @Query("SELECT * FROM online_download_task ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OnlineDownloadTaskEntity>>

    @Query("SELECT * FROM online_download_task WHERE taskId = :taskId")
    suspend fun find(taskId: String): OnlineDownloadTaskEntity?

    @Query("SELECT * FROM online_download_task WHERE status IN ('QUEUED','PREPARING','DOWNLOADING','VERIFYING','IMPORTING')")
    suspend fun unfinished(): List<OnlineDownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: OnlineDownloadTaskEntity)

    @Query("DELETE FROM online_download_task WHERE taskId = :taskId")
    suspend fun delete(taskId: String)

    @Query("DELETE FROM online_download_task WHERE status NOT IN ('QUEUED','PREPARING','DOWNLOADING','VERIFYING','IMPORTING')")
    suspend fun clearFinished()
}
