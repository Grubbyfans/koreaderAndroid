package com.yuezhou.reader.core.speech

/**
 * 朗读位置持久化端口（技术方案 §9 / §10.2）。
 *
 * 由 `SpeechService` 在朗读过程中调用，app 层注入 Room 实现。服务不直接依赖 reader
 * 模块的 DAO，Activity 也不再承担落库职责。
 *
 * 写入的游标指向「当前未完成句的起点或已确认偏移」，恢复时允许重读当前句，绝不跳过。
 */
interface SpeechProgressSink {
    suspend fun save(
        bookId: String,
        fileGeneration: String,
        chapterIndex: Int,
        segmentIndex: Int,
        charOffset: Int,
    )
}
