package com.yuezhou.reader.reader

import android.content.Context
import com.yuezhou.reader.reader.db.AppDatabase
import com.yuezhou.reader.reader.db.SpeechPositionEntity

/**
 * 朗读位置持久化封装：把「最后朗读位置」落库（Room `speech_position` 表），
 * 供前台朗读服务被系统终止后跨进程恢复。
 *
 * 只持久化定位游标（bookId + fileGeneration + chapterIndex + segmentIndex + charOffset），
 * 完整朗读计划由 EPUB 重新解析生成（见 [EpubSpeechChapterSource]）。
 *
 * 本类由 app 层调用（app 依赖 reader/speech 两模块，避免 speech 反向依赖 reader 的 DAO）。
 */
object SpeechPositionStore {

    private val dao = { context: Context ->
        AppDatabase.get(context.applicationContext).speechPositionDao()
    }

    /** 保存朗读位置（bookId 主键，upsert 覆盖）。 */
    suspend fun save(
        context: Context,
        bookId: String,
        fileGeneration: String,
        chapterIndex: Int,
        segmentIndex: Int,
        charOffset: Int,
    ) {
        dao(context).upsert(
            SpeechPositionEntity(
                bookId = bookId,
                fileGeneration = fileGeneration,
                chapterIndex = chapterIndex,
                segmentIndex = segmentIndex,
                charOffset = charOffset,
                savedAt = System.currentTimeMillis(),
            )
        )
    }

    /** 读取某本书的最后朗读位置；无记录返回 null。 */
    suspend fun load(context: Context, bookId: String): SpeechPositionEntity? =
        dao(context).get(bookId)

    /** 删除某本书的朗读位置（移除书籍时调用）。 */
    suspend fun remove(context: Context, bookId: String) {
        dao(context).delete(bookId)
    }
}
