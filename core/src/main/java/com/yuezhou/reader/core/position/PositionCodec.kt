package com.yuezhou.reader.core.position

/**
 * CFI ↔ XPointer 双向转换核心（PositionCodec）。
 *
 * 独立实现。本类聚焦“XPointer ↔ DOM 锚点”这一可单元测试的核心，
 * 真正的 EPUB CFI 字符串编解码由上层（foliate-js epubcfi / 桥接层）
 * 提供，本类负责与 CREngine XPointer 的精确对接。
 *
 * 锚点模型：{ node: DomNode(文本或元素), offset: Int }，
 * offset 对文本节点是 DOM 字符偏移，对元素节点是子节点索引。
 */
class PositionCodec(private val context: DomContext) {

    /** 解析出的 DOM 锚点。 */
    data class Anchor(val node: DomNode, val offset: Int)

    sealed interface Resolution {
        data class Exact(val anchor: Anchor) : Resolution
        data class Unavailable(val reason: String) : Resolution
    }

    /**
     * XPointer → DOM 锚点。
     * 支持三种文本定位，含 CREngine 空白折叠偏移还原。
     */
    fun resolveXPointer(xpointer: String): Resolution {
        val path = XPointerCodec.parse(xpointer)
            ?: return Resolution.Unavailable("非法 XPointer 格式: $xpointer")
        if (path.spineIndex != context.spineIndex)
            return Resolution.Unavailable(
                "XPointer spine 序号 ${path.spineIndex} 与当前章节 ${context.spineIndex} 不符",
            )

        val element = context.resolveElement(path.steps)
            ?: return Resolution.Unavailable("无法解析 XPointer 元素路径: ${path.steps}")

        val text = path.text ?: return Resolution.Exact(Anchor(element, 0))

        // /tag[idx].N：元素累计偏移（无显式 text 步）
        if (text.cumulative) {
            val found = findTextNodeAtCumulativeOffset(element, text.offset)
                ?: return Resolution.Unavailable("元素内无文本可定位累计偏移 ${text.offset}")
            return Resolution.Exact(found)
        }

        // /text().N 或 /text()[K].N：直接文本子节点
        val directTextChildren = crengineTextChildren(element)
        if (text.textNodeIndex == 0) {
            // 唯一直接文本子节点（CREngine 省略序号时）
            val node = directTextChildren.firstOrNull { !CrengineWhitespace.isBlank(it.text) }
                ?: directTextChildren.firstOrNull()
                ?: return Resolution.Unavailable("元素无直接文本子节点")
            val raw = CrengineWhitespace.toRawOffset(node.text, text.offset)
            return Resolution.Exact(Anchor(node, raw))
        } else {
            val node = directTextChildren.getOrNull(text.textNodeIndex - 1)
                ?: return Resolution.Unavailable(
                    "文本子节点序号 ${text.textNodeIndex} 越界（共 ${directTextChildren.size} 个）",
                )
            val raw = CrengineWhitespace.toRawOffset(node.text, text.offset)
            return Resolution.Exact(Anchor(node, raw))
        }
    }

    /**
     * DOM 锚点 → XPointer 字符串。
     * 文本节点锚点产出 /text().N 或 /text()[K].N；元素锚点产出元素路径。
     */
    fun toXPointer(anchor: Anchor): String? {
        val node = anchor.node
        return if (node.isText) {
            val parent = node.parent ?: return null
            val baseSteps = context.buildSteps(parent)
            val siblings = crengineTextChildren(parent)
            val index = siblings.indexOf(node)
            if (index < 0) {
                // 被丢弃的块首空白 → 父元素路径
                XPointerCodec.build(context.spineIndex, baseSteps, null)
            } else {
                val collapsed = CrengineWhitespace.toCollapsedOffset(node.text, anchor.offset)
                val text = if (siblings.size <= 1)
                    XPointerPath.TextTarget(0, collapsed)
                else
                    XPointerPath.TextTarget(index + 1, collapsed)
                XPointerCodec.build(context.spineIndex, baseSteps, text)
            }
        } else if (node.isElement) {
            XPointerCodec.build(context.spineIndex, context.buildSteps(node), null)
        } else null
    }

    /** 元素内累计文本偏移 → 文本节点锚点（用于 /tag[idx].N）。 */
    private fun findTextNodeAtCumulativeOffset(element: DomNode, offset: Int): Anchor? {
        val textNodes = ArrayList<DomNode>()
        collectTextNodes(element, textNodes)
        var current = 0
        for (t in textNodes) {
            val len = t.text.length
            if (current + len >= offset) {
                return Anchor(t, offset - current)
            }
            current += len
        }
        // 偏移超出全部文本：取最后一个文本节点末尾
        if (textNodes.isNotEmpty()) {
            val last = textNodes.last()
            return Anchor(last, last.text.length)
        }
        return null
    }

    /** 收集元素子树内所有非空文本节点（文档序）。 */
    private fun collectTextNodes(element: DomNode, out: MutableList<DomNode>) {
        for (child in element.children) {
            if (child.isText) {
                if (child.text.isNotEmpty()) out.add(child)
            } else {
                collectTextNodes(child, out)
            }
        }
    }

    /**
     * CREngine 保留的直接文本子节点：
     * 块级元素开头、且不在 pre 内的纯空白文本节点被丢弃。
     */
    fun crengineTextChildren(element: DomNode): List<DomNode> {
        val dropsLeadingBlank =
            element.name in CrengineWhitespace.BLOCK_TAGS && !preservesWhitespace(element)
        val result = ArrayList<DomNode>()
        element.children.forEachIndexed { i, child ->
            if (!child.isText) return@forEachIndexed
            if (child.text.isEmpty()) return@forEachIndexed
            if (dropsLeadingBlank && i == 0 && CrengineWhitespace.isBlank(child.text))
                return@forEachIndexed
            result.add(child)
        }
        return result
    }

    /** 判断节点是否在保留空白的标签内。 */
    private fun preservesWhitespace(node: DomNode): Boolean {
        var cur: DomNode? = node.parent
        while (cur != null) {
            if (cur.isElement && CrengineWhitespace.preservesWhitespace(cur.name)) return true
            cur = cur.parent
        }
        return false
    }
}
