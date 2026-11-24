package com.yuezhou.reader.reader.online.download

import com.yuezhou.reader.core.online.download.DownloadStatus
import com.yuezhou.reader.core.online.download.OnlineDownloadTask
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.OnlineDownloadTaskDao
import com.yuezhou.reader.reader.db.OnlineDownloadTaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room 与 UI 状态模型之间的映射；状态/错误码以枚举名持久化。 */
class OnlineDownloadRepository(database: AppDatabase) {
    private val dao: OnlineDownloadTaskDao = database.onlineDownloadTaskDao()
    private val mappingDao = database.onlineBookMappingDao()

    /** 映射表仅用于展示已下载状态，不参与任何判重逻辑。 */
    fun observeActiveRemoteIds(): Flow<Set<String>> = mappingDao.observeActiveRemoteIds().map { it.toSet() }

    fun observeAll(): Flow<List<OnlineDownloadTask>> = dao.observeAll().map { rows -> rows.map(::toModel) }

    suspend fun find(taskId: String): OnlineDownloadTask? = dao.find(taskId)?.let(::toModel)

    suspend fun unfinished(): List<OnlineDownloadTask> = dao.unfinished().map(::toModel)

    suspend fun save(task: OnlineDownloadTask) = dao.upsert(toEntity(task))

    suspend fun delete(taskId: String) = dao.delete(taskId)

    suspend fun clearFinished() = dao.clearFinished()

    private fun toEntity(task: OnlineDownloadTask) = OnlineDownloadTaskEntity(
        taskId = task.taskId,
        providerId = task.providerId,
        accountScope = task.accountScope,
        remoteId = task.remoteId,
        bookTitle = task.bookTitle,
        fileName = task.fileName,
        status = task.status.name,
        receivedBytes = task.receivedBytes,
        totalBytes = task.totalBytes,
        localPath = task.localPath,
        errorCode = task.errorCode?.name,
        errorMessage = task.errorMessage,
        attempts = task.attempts,
        overwriteApproved = task.overwriteApproved,
        createdAt = task.createdAt,
        updatedAt = task.updatedAt,
        localBookId = task.localBookId,
    )

    private fun toModel(row: OnlineDownloadTaskEntity) = OnlineDownloadTask(
        taskId = row.taskId,
        providerId = row.providerId,
        accountScope = row.accountScope,
        remoteId = row.remoteId,
        bookTitle = row.bookTitle,
        fileName = row.fileName,
        status = runCatching { DownloadStatus.valueOf(row.status) }.getOrDefault(DownloadStatus.FAILED),
        receivedBytes = row.receivedBytes,
        totalBytes = row.totalBytes,
        localPath = row.localPath,
        errorCode = runCatching { ProviderErrorCode.valueOf(row.errorCode.orEmpty()) }.getOrNull(),
        errorMessage = row.errorMessage,
        attempts = row.attempts,
        overwriteApproved = row.overwriteApproved,
        createdAt = row.createdAt,
        updatedAt = row.updatedAt,
        localBookId = row.localBookId,
    )
}
