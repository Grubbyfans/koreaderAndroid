package com.yuezhou.reader.core.position

/**
 * 轻量 DOM 树模型：供单元测试与无浏览器环境使用。
 * 维护 parent 指针，支持解析与构建双向。
 */
class TestElement(
    override val name: String,
    private val attrs: Map<String, String> = emptyMap(),
) : DomNode {
    private val _children = ArrayList<DomNode>()
    override var parent: DomNode? = null
        internal set

    override val isElement: Boolean = true
    override val text: String get() = ""

    override val children: List<DomNode>
        get() = _children

    override fun attribute(name: String): String? = attrs[name]

    fun addChild(child: DomNode) {
        _children.add(child)
        when (child) {
            is TestElement -> child.parent = this
            is TestText -> child.parent = this
        }
    }

    companion object {
        /** 便捷构造：tag("div", "text") 或 tag("p", text("hi"), tag("span", "x")). */
        fun tag(name: String, vararg children: Any): TestElement {
            val el = TestElement(name)
            children.forEach { el.addChild(anyToNode(it)) }
            return el
        }

        fun tagWithAttrs(name: String, attrs: Map<String, String>, vararg children: Any): TestElement {
            val el = TestElement(name, attrs)
            children.forEach { el.addChild(anyToNode(it)) }
            return el
        }

        private fun anyToNode(c: Any): DomNode = when (c) {
            is DomNode -> c
            is String -> TestText(c)
            else -> throw IllegalArgumentException("不支持的子节点类型: $c")
        }
    }
}

class TestText(override val text: String) : DomNode {
    override var parent: DomNode? = null
    override val isElement: Boolean = false
    override val name: String get() = "#text"
    override val children: List<DomNode> get() = emptyList()
    override fun attribute(name: String): String? = null
}

/**
 * 针对轻量树模型的 DomContext 实现。
 * body 作为章节根，XPointer 的 /body 段对应它。
 */
class TestDomContext(
    override val sectionBody: DomNode,
    override val spineIndex: Int = 0,
) : DomContext {

    override fun resolveElement(steps: List<XPointerPath.XPointerStep>): DomNode? {
        var current: DomNode = sectionBody
        for (step in steps) {
            val children = effectiveChildren(current)
            val sameTag = children.filter { it.isElement && it.name == step.tag }
            val idx = if (step.index == 0) 0 else step.index - 1
            current = sameTag.getOrNull(idx) ?: return null
        }
        return current
    }

    override fun buildSteps(element: DomNode): List<XPointerPath.XPointerStep> {
        val steps = ArrayList<XPointerPath.XPointerStep>()
        var current: DomNode? = element
        while (current != null && current !== sectionBody) {
            val parent = current.parent ?: break
            val siblings = effectiveChildren(parent).filter { it.isElement && it.name == current!!.name }
            val idx = siblings.indexOf(current) // 0-based
            val oneBased = idx + 1
            // 唯一标签省略序号（index 存 0 表示省略）；>1 时才写
            val totalSame = siblings.size
            steps.add(
                XPointerPath.XPointerStep(
                    tag = current.name,
                    index = if (totalSame == 1) 0 else oneBased,
                ),
            )
            current = parent
        }
        steps.reverse()
        return steps
    }

    private fun effectiveChildren(element: DomNode): List<DomNode> {
        val result = ArrayList<DomNode>()
        for (child in element.children) {
            if (child.isCfiInert) continue
            if (child.isCfiSkip) result.addAll(effectiveChildren(child))
            else result.add(child)
        }
        return result
    }
}
