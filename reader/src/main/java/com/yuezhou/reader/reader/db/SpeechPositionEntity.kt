package com.yuezhou.reader.reader.db

import androidx.room.Entity

/**
 * 朗读位置 Room 实体（对齐方案 §8.2「SpeechPlan」表的朗读位置持久化部分）。
 *
 * 每本书只保留一份「最后朗读位置」，主键 [bookId]。用于跨进程恢复：
 * 前台朗读服务被系统终止后，重开书可从本表恢复到上次朗读的章节 + 片段 + 字符偏移。
 *
 * 完整朗读计划（章节文本、逐字符锚点映射）体积大且可由 EPUB 重新生成，
 * 因此不持久化，只持久化「定位用的游标」；重开时按游标重新解析生成计划并 seek 到该位置。
 *
 * [anchorNodeId] 是稳定原文锚点（WebView 路径为 CFI），[charOffset] 为锚点节点内偏移；
 * 恢复时优先按锚点重新映射，只有 [fileGeneration] 与 [plannerVersion] 都匹配时才允许
 * 退回缓存 [segmentIndex]，避免同名覆盖或分句规则升级后索引漂移。
 */
@Entity(tableName = "speech_position", primaryKeys = ["bookId"])
data class SpeechPositionEntity(
    val bookId: String,
    val fileGeneration: String,
    val chapterIndex: Int,
    val segmentIndex: Int,
    val charOffset: Int,
    val savedAt: Long,
    val anchorNodeId: String = "",
    val plannerVersion: Int = 0,
)
