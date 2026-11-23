package com.yuezhou.reader.reader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        SpeechPositionEntity::class,
        SyncLogEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingPublicationDao(): PendingPublicationDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
    abstract fun speechPositionDao(): SpeechPositionDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        private val isolatePublications = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE pending_publication_v2 (
                        backendId TEXT NOT NULL, bookId TEXT NOT NULL, fileName TEXT NOT NULL,
                        size INTEGER NOT NULL, coverPath TEXT, coverVersion TEXT, savedAt INTEGER NOT NULL,
                        PRIMARY KEY (backendId, bookId)
                    )
                """.trimIndent())
                // Version 1 did not record the destination. Preserve those rows without assigning a backend.
                db.execSQL("""
                    INSERT INTO pending_publication_v2
                    SELECT '', bookId, fileName, size, coverPath, coverVersion, savedAt
                    FROM pending_publication
                """.trimIndent())
                db.execSQL("DROP TABLE pending_publication")
                db.execSQL("ALTER TABLE pending_publication_v2 RENAME TO pending_publication")
            }
        }

        private val addSpeechPosition = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE speech_position (
                        bookId TEXT NOT NULL,
                        fileGeneration TEXT NOT NULL,
                        chapterIndex INTEGER NOT NULL,
                        segmentIndex INTEGER NOT NULL,
                        charOffset INTEGER NOT NULL,
                        savedAt INTEGER NOT NULL,
                        PRIMARY KEY (bookId)
                    )
                """.trimIndent())
            }
        }

        private val addSyncLog = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE sync_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        direction TEXT NOT NULL,
                        bookTitle TEXT NOT NULL,
                        at INTEGER NOT NULL,
                        success INTEGER NOT NULL,
                        message TEXT NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** 阶段 E：朗读位置增加稳定锚点与分段算法版本（方案 §7.1 / §10.1）。 */
        private val addSpeechAnchor = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speech_position ADD COLUMN anchorNodeId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE speech_position ADD COLUMN plannerVersion INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "yuezhou_reader.db",
                ).addMigrations(isolatePublications, addSpeechPosition, addSyncLog, addSpeechAnchor)
                    .build().also { instance = it }
            }
    }
}
