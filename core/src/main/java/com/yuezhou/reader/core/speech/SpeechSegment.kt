package com.yuezhou.reader.core.speech

/**
 * 朗读片段模型。对应技术方案 §7.2「每个朗读片段保存」：
 *   - 朗读纯文本
 *   - 朗读文本偏移 → 原文节点偏移的映射
 *
 * 本文件为纯 Kotlin 数据模型，不依赖 Android 或具体 DOM 实现，可离线单测。
 */

/**
 * 原文中的一个锚点：DOM 文本节点 + 节点内偏移。
 *
 * 与 [com.yuezhou.reader.core.position.DomNode] 解耦——这里用「节点标识」而非节点对象，
 * 以便句子计划可被序列化/缓存，且后台服务不持有 WebView 的 DOM 引用。
 *
 * @param nodeId 原文文本节点在章节内稳定标识（由上层 DOM 适配层生成，如遍历序号）。
 * @param offset 该节点内的字符偏移（0-based，按 Unicode 码点计）。
 */
data class SourceAnchor(
    val nodeId: String,
    val offset: Int,
)

/**
 * 一个朗读片段（通常是一句，过长句子会被再切段）。
 *
 * @param text 送入 TTS 的纯文本（已去除脚本/样式/隐藏装饰及导航文本）。
 * @param startAnchor 该片段首个字符对应的原文锚点。
 * @param endAnchor 该片段末字符（exclusive）对应的原文锚点，用于高亮与位置回写。
 * @param charToSource 朗读文本字符偏移 → 原文锚点 的映射（用于 TTS 回调偏移转原文位置）。
 *   [charToSource] 长度应为 `text.length + 1`（含末位边界）。
 */
data class SpeechSegment(
    val text: String,
    val startAnchor: SourceAnchor,
    val endAnchor: SourceAnchor,
    val charToSource: List<SourceAnchor>,
) {
    /** 朗读文本是否为空（空片段不可送入 TTS）。 */
    val isBlank: Boolean get() = text.isBlank()

    /** 将朗读文本内的字符偏移（0..length）映射回原文锚点；越界取最近边界。 */
    fun anchorAt(charOffset: Int): SourceAnchor {
        if (charToSource.isEmpty()) return startAnchor
        val idx = charOffset.coerceIn(0, charToSource.lastIndex)
        return charToSource[idx]
    }
}

/**
 * 一章的完整朗读计划：由若干 [SpeechSegment] 组成。
 *
 * @param bookId 书籍标识（书名去扩展名，对齐 cloud 层）。
 * @param fileGeneration 文件代次；同名覆盖后失效，避免旧计划误用。
 * @param chapterIndex 章节序号（0-based）。
 * @param segments 按朗读顺序排列的片段。
 * @param plannerVersion 分段算法版本；与位置记录不一致时缓存的 segmentIndex 作废。
 */
data class SpeechPlan(
    val bookId: String,
    val fileGeneration: String,
    val chapterIndex: Int,
    val segments: List<SpeechSegment>,
    val plannerVersion: Int = PLANNER_VERSION,
) {
    /** 片段总数。 */
    val segmentCount: Int get() = segments.size

    /** 取指定序号的片段；越界返回 null。 */
    fun segmentAt(index: Int): SpeechSegment? = segments.getOrNull(index)

    /**
     * 查找包含指定原文锚点的片段序号；找不到返回 -1。
     *
     * [offset] 是 [nodeId] 节点内的字符偏移。命中条件是片段内某字符映射到
     * 同一节点且偏移不小于目标，即「从该锚点开始朗读」的首个片段。
     */
    fun findSegmentForAnchor(nodeId: String, offset: Int): Int {
        if (nodeId.isEmpty()) return -1
        for ((index, segment) in segments.withIndex()) {
            for (anchor in segment.charToSource) {
                if (anchor.nodeId == nodeId && anchor.offset >= offset) return index
            }
        }
        return -1
    }

    companion object {
        /** 当前分段算法版本。改动分句规则时应递增，使旧 position 的 segmentIndex 失效。 */
        const val PLANNER_VERSION = 2
    }
}
