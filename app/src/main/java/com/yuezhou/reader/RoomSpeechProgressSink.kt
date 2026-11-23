package com.yuezhou.reader

import android.content.Context
import com.yuezhou.reader.core.speech.SpeechProgressSink
import com.yuezhou.reader.reader.SpeechPositionStore

/** 把 Room 朗读位置表适配为服务侧持久化端口。 */
class RoomSpeechProgressSink(context: Context) : SpeechProgressSink {

    private val appContext = context.applicationContext

    override suspend fun save(
        bookId: String,
        fileGeneration: String,
        chapterIndex: Int,
        segmentIndex: Int,
        charOffset: Int,
    ) {
        SpeechPositionStore.save(
            context = appContext,
            bookId = bookId,
            fileGeneration = fileGeneration,
            chapterIndex = chapterIndex,
            segmentIndex = segmentIndex,
            charOffset = charOffset,
        )
    }
}
