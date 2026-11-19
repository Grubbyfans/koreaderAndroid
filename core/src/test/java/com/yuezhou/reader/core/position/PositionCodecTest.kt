package com.yuezhou.reader.core.position

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * PositionCodec 单元测试：验证 XPointer 解析/构建、CREngine 空白折叠、
 * CFI↔XPointer 双向转换（用轻量 DOM 树模拟 EPUB 章节）。
 */
class PositionCodecTest {

    /** 构建一个简单章节 DOM：body > div > p（含文本）。 */
    private fun simpleBody(): TestElement =
        TestElement.tag(
            "body",
            TestElement.tag(
                "div",
                TestElement.tag("p", "Hello world"),
            ),
        )

    // ---------- XPointer 解析 ----------

    @Test
    fun `解析单 spine 无序号 XPointer`() {
        val path = XPointerCodec.parse("/body/DocFragment/body/div/p/text().0")
        assertNotNull(path)
        assertEquals(0, path!!.spineIndex)
        assertEquals(listOf("div", "p"), path.steps.map { it.tag })
        assertEquals(0, path.text!!.textNodeIndex)
        assertEquals(0, path.text!!.offset)
    }

    @Test
    fun `解析带 spine 序号与元素序号的 XPointer`() {
        val path = XPointerCodec.parse("/body/DocFragment[3]/body/div[2]/p[4]/text()[2].12")
        assertNotNull(path)
        assertEquals(2, path!!.spineIndex) // 3-1
        assertEquals(2, path.steps.size)
        assertEquals("div", path.steps[0].tag)
        assertEquals(2, path.steps[0].index)
        assertEquals("p", path.steps[1].tag)
        assertEquals(4, path.steps[1].index)
        assertEquals(2, path.text!!.textNodeIndex)
        assertEquals(12, path.text!!.offset)
    }

    @Test
    fun `解析元素累计偏移形式`() {
        val path = XPointerCodec.parse("/body/DocFragment/body/div[1].0")
        assertNotNull(path)
        assertTrue(path!!.text!!.cumulative)
        assertEquals(0, path.text!!.offset)
    }

    @Test
    fun `解析元素路径无文本步`() {
        val path = XPointerCodec.parse("/body/DocFragment/body/div/p")
        assertNotNull(path)
        assertNull(path!!.text)
    }

    @Test
    fun `拒绝非法 XPointer`() {
        assertNull(XPointerCodec.parse("not-a-xpointer"))
        assertNull(XPointerCodec.parse("/html/body/p/text().0"))
        assertNull(XPointerCodec.parse(""))
    }

    // ---------- CREngine 空白折叠 ----------

    @Test
    fun `连续空白折叠为单空格`() {
        // "a  b" -> 折叠后 "a b"，raw offset 3 (第二个空格) -> collapsed 2
        assertEquals(2, CrengineWhitespace.toCollapsedOffset("a  b", 3))
        // collapsed 2 -> raw 3
        assertEquals(3, CrengineWhitespace.toRawOffset("a  b", 2))
    }

    @Test
    fun `折叠偏移往返一致`() {
        val text = "Hello  world\t\n again"
        for (raw in 0..text.length) {
            val collapsed = CrengineWhitespace.toCollapsedOffset(text, raw)
            val back = CrengineWhitespace.toRawOffset(text, collapsed)
            // 往返后折叠偏移应稳定
            assertEquals(collapsed, CrengineWhitespace.toCollapsedOffset(text, back))
        }
    }

    // ---------- 双向转换（用轻量 DOM） ----------

    @Test
    fun `文本节点锚点转 XPointer 再转回`() {
        val body = simpleBody()
        val ctx = TestDomContext(body)
        val codec = PositionCodec(ctx)

        // 找到 p 里的文本节点 "Hello world"
        val p = body.children[0].children[0] // div > p
        val textNode = p.children[0]
        assertTrue(textNode.isText)

        val anchor = PositionCodec.Anchor(textNode, 6) // 定位到 "world" 的 'w'
        val xp = codec.toXPointer(anchor)
        assertNotNull(xp)
        // 单 spine，div/p 唯一 → 无序号
        assertEquals("/body/DocFragment/body/div/p/text().6", xp)

        // 转回
        val resolved = codec.resolveXPointer(xp!!)
        assertTrue(resolved is PositionCodec.Resolution.Exact)
        val back = (resolved as PositionCodec.Resolution.Exact).anchor
        assertTrue(back.node.isText)
        assertEquals("Hello world", back.node.text)
        assertEquals(6, back.offset)
    }

    @Test
    fun `多段落区分序号`() {
        val body = TestElement.tag(
            "body",
            TestElement.tag(
                "div",
                TestElement.tag("p", "first"),
                TestElement.tag("p", "second"),
                TestElement.tag("p", "third"),
            ),
        )
        val ctx = TestDomContext(body)
        val codec = PositionCodec(ctx)

        val div = body.children[0]
        val p2 = div.children[1] // "second"
        val textNode = p2.children[0]
        val xp = codec.toXPointer(PositionCodec.Anchor(textNode, 0))
        assertNotNull(xp)
        // p 有 3 个，需写序号 p[2]
        assertEquals("/body/DocFragment/body/div/p[2]/text().0", xp)

        val back = codec.resolveXPointer(xp!!)
        assertTrue(back is PositionCodec.Resolution.Exact)
        assertEquals("second", (back as PositionCodec.Resolution.Exact).anchor.node.text)
    }

    @Test
    fun `块首空白文本被丢弃`() {
        // p 开头有纯空白文本节点，应被 CREngine 丢弃
        val body = TestElement.tag(
            "body",
            TestElement.tag(
                "div",
                TestElement.tag("p", "\n   ", "real text"),
            ),
        )
        val ctx = TestDomContext(body)
        val codec = PositionCodec(ctx)
        val p = body.children[0].children[0]

        // crengineTextChildren 应只保留 "real text"
        val kept = codec.crengineTextChildren(p)
        assertEquals(1, kept.size)
        assertEquals("real text", kept[0].text)

        // 定位到 "real text" 的 r，text() 应指向唯一保留文本
        val textNode = p.children[1]
        val xp = codec.toXPointer(PositionCodec.Anchor(textNode, 0))
        assertEquals("/body/DocFragment/body/div/p/text().0", xp)
    }

    @Test
    fun `内联元素切分文本用 text() 序号区分`() {
        // p > "Hello " + <em>world</em> + "!"，两个直接文本子节点
        val body = TestElement.tag(
            "body",
            TestElement.tag(
                "div",
                TestElement.tag(
                    "p",
                    "Hello ",
                    TestElement.tag("em", "world"),
                    "!",
                ),
            ),
        )
        val ctx = TestDomContext(body)
        val codec = PositionCodec(ctx)
        val p = body.children[0].children[0]

        val kept = codec.crengineTextChildren(p)
        assertEquals(2, kept.size) // "Hello " 与 "!"

        // 定位到第二个文本 "!"
        val second = p.children[2]
        val xp = codec.toXPointer(PositionCodec.Anchor(second, 0))
        assertEquals("/body/DocFragment/body/div/p/text()[2].0", xp)

        val back = codec.resolveXPointer(xp!!)
        assertTrue(back is PositionCodec.Resolution.Exact)
        assertEquals("!", (back as PositionCodec.Resolution.Exact).anchor.node.text)
    }

    @Test
    fun `空白折叠偏移在往返中保持`() {
        // 文本含连续空格，验证折叠偏移映射
        // "a  b   c"（a + 2空格 + b + 3空格 + c）
        val body = TestElement.tag(
            "body",
            TestElement.tag("div", TestElement.tag("p", "a  b   c")),
        )
        val ctx = TestDomContext(body)
        val codec = PositionCodec(ctx)
        val textNode = body.children[0].children[0].children[0]

        // raw offset 5（"a  b   c" 中 b 后第一个空格）
        // 折叠：前5字符 "a  b " → "a b "（4字符），collapsed=4
        val xp = codec.toXPointer(PositionCodec.Anchor(textNode, 5))
        assertNotNull(xp)
        // 断言 XPointer 里的折叠偏移为 4
        assertEquals("/body/DocFragment/body/div/p/text().4", xp)

        val back = codec.resolveXPointer(xp!!)
        assertTrue(back is PositionCodec.Resolution.Exact)
        val resolved = (back as PositionCodec.Resolution.Exact).anchor
        // 折叠偏移有损：collapsed=4 反向映射到折叠文本末尾（'c' 前，raw=7）
        // 这是 CREngine 折叠语义的固有行为，不要求 raw offset 精确还原
        assertEquals(7, resolved.offset)
        // 核心不变量：还原后的 raw 再折叠，回到同一 collapsed 值
        assertEquals(
            CrengineWhitespace.toCollapsedOffset(textNode.text, 5),
            CrengineWhitespace.toCollapsedOffset(textNode.text, resolved.offset),
        )
    }

    @Test
    fun `spine 序号不符时报不可用`() {
        val body = simpleBody()
        val ctx = TestDomContext(body, spineIndex = 0)
        val codec = PositionCodec(ctx)
        val r = codec.resolveXPointer("/body/DocFragment[2]/body/div/p/text().0")
        assertTrue(r is PositionCodec.Resolution.Unavailable)
    }

    @Test
    fun `normalize 剥离文本偏移`() {
        assertEquals(
            "/body/DocFragment/body/div/p",
            XPointerCodec.normalize("/body/DocFragment/body/div/p/text().6"),
        )
        assertEquals(
            "/body/DocFragment/body/div/p",
            XPointerCodec.normalize("/body/DocFragment/body/div/p.3"),
        )
    }
}
