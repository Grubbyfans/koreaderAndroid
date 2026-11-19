package com.yuezhou.reader.core.position

/**
 * DOM 导航抽象：让 CFI↔XPointer 转换逻辑与具体 DOM 实现解耦。
 *
 * 单元测试用轻量树模型；Android 端用 foliate-js 的真实 DOM 通过桥接适配。
 * 只暴露转换所需的最小操作集。
 */
interface DomNode {
    /** 节点类型：元素或文本。 */
    val isElement: Boolean

    /** 元素：标签名（小写）；文本节点：文本内容。 */
    val name: String

    /** 文本节点内容（元素节点返回空串）。 */
    val text: String

    /** 子节点（元素与文本节点混合，保持文档序）。 */
    val children: List<DomNode>

    /** 父节点（根为 null）。 */
    val parent: DomNode?

    /** 元素的属性值（如 cfi-inert / cfi-skip / id）。 */
    fun attribute(name: String): String?

    /** 元素是否为 cfi-inert（转换视野中完全移除，连同子树）。 */
    val isCfiInert: Boolean
        get() = isElement && attribute("cfi-inert") != null

    /** 元素是否为 cfi-skip（布局包装器，其子节点提升到父级）。 */
    val isCfiSkip: Boolean
        get() = isElement && attribute("cfi-skip") != null
}

/** 文本节点便捷判断。 */
val DomNode.isText: Boolean get() = !isElement

/**
 * DOM 树工厂：供转换器定位章节根节点与解析/构建路径。
 *
 * @param sectionRoot 当前章节的 body 元素（XPointer 的 /body 段对应它）。
 * @param spineIndex 当前章节的 0-based spine 序号。
 */
interface DomContext {
    /** 章节根（body）元素。 */
    val sectionBody: DomNode
    /** 当前章节的 0-based spine 序号。 */
    val spineIndex: Int

    /** 从 body 根沿 XPointer 元素步解析目标元素；无法匹配返回 null。 */
    fun resolveElement(steps: List<XPointerPath.XPointerStep>): DomNode?

    /** 构建某元素相对 body 根的 XPointer 元素步列表。 */
    fun buildSteps(element: DomNode): List<XPointerPath.XPointerStep>
}
