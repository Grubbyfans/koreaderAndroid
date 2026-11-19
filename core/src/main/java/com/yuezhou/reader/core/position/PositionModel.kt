package com.yuezhou.reader.core.position

/**
 * 位置转换（CFI ↔ XPointer）的核心数据模型。
 *
 * 本文件为独立实现：仅依据 CREngine XPointer 的公开格式与 KOReader
 * 的锚点语义编写，不复制任何第三方源码。
 *
 * CREngine XPointer（单 EPUB spine）形态：
 *   /body/DocFragment[N]/body/div/p[3]/text().12
 *   - DocFragment[N]：1-based spine 序号，全书仅一个 spine item 时省略 [N]。
 *   - 元素步：tag 或 tag[1-based 序号]；同标签唯一时省略序号。
 *   - 文本定位三形态：
 *       /text().N       —— 元素的唯一直接文本子节点，折叠后偏移 N
 *       /text()[K].N    —— 第 K 个（1-based）直接文本子节点，折叠后偏移 N
 *       /tag[idx].N     —— 元素起始处的累计偏移 N（无显式 text() 步）
 */

/** 解析后的 XPointer：元素路径 + 可选文本定位。 */
data class XPointerPath(
    /** spine 序号（0-based），全书单 spine item 时为 0。 */
    val spineIndex: Int,
    /** 从 body 起的元素步列表，每步含标签名与 1-based 序号（唯一时序号为 0 表示省略）。 */
    val steps: List<XPointerStep>,
    /** 文本定位，可为 null（仅指向元素起点）。 */
    val text: TextTarget?,
) {
    /** 单步：标签名 + 1-based 序号（0 表示“唯一、未写序号”）。 */
    data class XPointerStep(val tag: String, val index: Int)

    /** 文本定位：直接文本子节点序号（1-based，唯一时为 0）+ 折叠后偏移。 */
    data class TextTarget(
        val textNodeIndex: Int,
        val offset: Int,
        /** true 表示是 /tag[idx].N 形式（元素累计偏移），textNodeIndex 无效。 */
        val cumulative: Boolean = false,
    )
}

/** XPointer 文本定位解析结果：区分三种 CREngine 形式。 */
internal sealed interface ParsedTextStep {
    /** /text().N —— 唯一直接文本子节点。 */
    data class SingleText(val offset: Int) : ParsedTextStep
    /** /text()[K].N —— 第 K 个直接文本子节点。 */
    data class IndexedText(val index: Int, val offset: Int) : ParsedTextStep
    /** /tag[idx].N —— 元素累计偏移。 */
    data class ElementOffset(val offset: Int) : ParsedTextStep
    /** 无文本步，指向元素起点。 */
    object None : ParsedTextStep
}
