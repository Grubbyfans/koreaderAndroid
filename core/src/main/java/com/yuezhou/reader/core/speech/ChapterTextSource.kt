package com.yuezhou.reader.core.speech

/**
 * 分句器的输入抽象：把「章节 DOM」提炼成「待朗读的文本流 + 原文锚点追踪」，
 * 使分句逻辑与具体 DOM 实现（foliate-js DOM、测试树模型）解耦。
 *
 * 提取规则对齐技术方案 §7.2：
 *   - 不朗读脚本（script）、样式（style）、隐藏装饰及重复导航文本。
 *   - 跳过 cfi-inert 子树（转换视野中完全移除）与 cfi-skip 包装器的包装层级。
 *
 * 本文件纯 Kotlin，无 Android 依赖。
 */

/**
 * 一个待朗读的文本单元：一段连续文本 + 它在原文中的起始锚点。
 *
 * @param text 待朗读文本（已去掉不可朗读内容，保留原文相对顺序）。
 * @param anchor 该文本第一个字符对应的原文锚点。
 */
data class SpeechTextUnit(
    val text: String,
    val anchor: SourceAnchor,
)

/**
 * 章节文本提取器：从任意章节数据源产出一组 [SpeechTextUnit]。
 *
 * 实现方负责：
 *   1. 按文档序遍历文本节点，跳过 script/style/隐藏/cfi-inert 子树；
 *   2. 为每个文本单元生成稳定的 [SourceAnchor.nodeId]；
 *   3. 保留换行/段落边界信息（以空白或分隔字符体现）。
 */
interface ChapterTextSource {
    /**
     * 提取当前章节的待朗读文本单元序列（已按朗读顺序排列）。
     */
    fun extract(): List<SpeechTextUnit>
}

/**
 * 便捷构造：把单个「文本 + 锚点」封装为单元。
 */
fun speechUnit(text: String, nodeId: String, offset: Int = 0): SpeechTextUnit =
    SpeechTextUnit(text, SourceAnchor(nodeId, offset))


/** Provides later chapters without relying on an active reader screen. */
interface SpeechChapterSource : AutoCloseable {
    suspend fun next(after: SpeechPlan): SpeechPlan?
}
