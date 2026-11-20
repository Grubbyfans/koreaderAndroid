package com.yuezhou.reader.reader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 阅舟本地 Room 数据库（对齐方案 §8.2「本地结构化数据用 Room」）。
 *
 * 首版仅纳入「需按 key 查询/事务化、且无云端协议耦合」的表：
 * - [PendingPublicationEntity]：待发布回执（本地故障恢复元数据）。
 * - [DiagnosticEventEntity]：诊断事件（本地脱敏诊断）。
 *
 * 阅读进度（ReadingPosition）因与 KOReader 共享协议文件（progress/<bookId>.json）、
 * 需随云端上传下载，保留 JSON 实现，暂不迁 Room 以免引入双数据源一致性风险。
 */
@Database(
    entities = [
        PendingPublicationEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingPublicationDao(): PendingPublicationDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yuezhou_reader.db",
                ).build().also { instance = it }
            }
    }
}
