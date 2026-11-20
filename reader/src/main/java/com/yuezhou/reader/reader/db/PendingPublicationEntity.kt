package com.yuezhou.reader.reader.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yuezhou.reader.reader.PendingPublication

/**
 * 待发布回执的 Room 实体（对齐方案 §8.2「PendingPublication」表）。
 *
 * 记录「正文上传成功但列表发布失败」的待补全回执。以 [bookId] 为主键，
 * 同名 upsert 覆盖；发布成功或删除后移除。
 */
@Entity(tableName = "pending_publication")
data class PendingPublicationEntity(
    @PrimaryKey val bookId: String,
    val fileName: String,
    val size: Long,
    val coverPath: String?,
    val coverVersion: String?,
    val savedAt: Long,
) {
    fun toDomain(): PendingPublication = PendingPublication(
        bookId = bookId,
        fileName = fileName,
        size = size,
        coverPath = coverPath,
        coverVersion = coverVersion,
        savedAt = savedAt,
    )

    companion object {
        fun fromDomain(p: PendingPublication) = PendingPublicationEntity(
            bookId = p.bookId,
            fileName = p.fileName,
            size = p.size,
            coverPath = p.coverPath,
            coverVersion = p.coverVersion,
            savedAt = p.savedAt,
        )
    }
}
