package com.yuezhou.reader.core.position

/**
 * CREngine XPointer 字符串的解析与构建。
 *
 * 独立实现，依据 CREngine XPointer 的公开格式：
 *   /body/DocFragment[N]/body/div[2]/p[3]/text()[K].N
 */
object XPointerCodec {

    /**
     * 解析单 spine EPUB 的 XPointer。返回 null 表示格式非法。
     *
     * 新版 crengine 会给无同名兄弟的步也写 [1]（如 div[1]），
     * 解析时统一视为“唯一”处理，序号按 1 计但不影响路径匹配。
     */
    fun parse(xpointer: String): XPointerPath? {
        if (xpointer.isBlank()) return null
        // 去掉首尾空白与可能的尾部注释
        var s = xpointer.trim()
        // 必须从 /body/DocFragment 开始（单 EPUB spine）
        val head = Regex("^/body/DocFragment(?:\\[(\\d+)])?/body(/.*)?$").matchEntire(s)
            ?: return null
        val spine = head.groupValues[1].let { if (it.isEmpty()) 1 else it.toInt() }
        val rest = head.groupValues[2] // 形如 /div/p[3]/text().12 或空串

        // 分离文本定位步（最后一段）
        val (pathPart, textStep) = splitTextStep(rest)

        // 解析元素步
        val steps = ArrayList<XPointerPath.XPointerStep>()
        if (pathPart.isNotEmpty()) {
            // pathPart 形如 /div/p[3]/span，逐段切分
            for (seg in pathPart.trimStart('/').split('/')) {
                if (seg.isEmpty()) continue
                val m = Regex("^([\\w-]+)(?:\\[(\\d+)])?$").matchEntire(seg) ?: return null
                val tag = m.groupValues[1]
                val idx = m.groupValues[2].let { if (it.isEmpty()) 0 else it.toInt() }
                steps.add(XPointerPath.XPointerStep(tag, idx))
            }
        }

        val text = when (textStep) {
            is ParsedTextStep.SingleText ->
                XPointerPath.TextTarget(textNodeIndex = 0, offset = textStep.offset)
            is ParsedTextStep.IndexedText ->
                XPointerPath.TextTarget(textNodeIndex = textStep.index, offset = textStep.offset)
            is ParsedTextStep.ElementOffset ->
                XPointerPath.TextTarget(textNodeIndex = 0, offset = textStep.offset, cumulative = true)
            ParsedTextStep.None -> null
        }

        return XPointerPath(spineIndex = spine - 1, steps = steps, text = text)
    }

    /** 从 body 段后分离出“元素路径”与“文本定位步”。 */
    private fun splitTextStep(rest: String): Pair<String, ParsedTextStep> {
        if (rest.isEmpty()) return "" to ParsedTextStep.None
        // /text()[K].N
        Regex("^(.+)/text\\(\\)\\[(\\d+)]\\.(\\d+)$").matchEntire(rest)?.let {
            return it.groupValues[1] to ParsedTextStep.IndexedText(it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        // /text().N
        Regex("^(.+)/text\\(\\)\\.(\\d+)$").matchEntire(rest)?.let {
            return it.groupValues[1] to ParsedTextStep.SingleText(it.groupValues[2].toInt())
        }
        // /tag[idx].N —— 元素累计偏移（无 text() 步）
        Regex("^(.*/[\\w-]+(?:\\[\\d+])?)\\.(\\d+)$").matchEntire(rest)?.let {
            return it.groupValues[1] to ParsedTextStep.ElementOffset(it.groupValues[2].toInt())
        }
        // 纯元素路径，无偏移
        return rest to ParsedTextStep.None
    }

    /**
     * 构建 XPointer 字符串。
     *
     * @param spineIndex 0-based spine 序号。
     * @param steps 元素步（标签 + 1-based 序号，唯一时序号传 0）。
     * @param text 文本定位，可为 null。
     */
    fun build(
        spineIndex: Int,
        steps: List<XPointerPath.XPointerStep>,
        text: XPointerPath.TextTarget?,
    ): String {
        val sb = StringBuilder("/body/DocFragment")
        if (spineIndex != 0) sb.append('[').append(spineIndex + 1).append(']')
        sb.append("/body")
        for (step in steps) {
            sb.append('/').append(step.tag)
            if (step.index > 1) sb.append('[').append(step.index).append(']')
            // index == 1 或 0 均省略（唯一标签）；仅在 >1 时写序号
        }
        if (text != null) {
            when {
                text.cumulative ->
                    // /tag[idx].N：元素累计偏移（此时最后一个元素步后直接跟 .N）
                    sb.append('.').append(text.offset)
                text.textNodeIndex == 0 ->
                    sb.append("/text().").append(text.offset)
                else ->
                    sb.append("/text()[").append(text.textNodeIndex).append(']').append('.').append(text.offset)
            }
        }
        return sb.toString()
    }

    /** 规范化：剥离末尾 text() 偏移与 .N，供 KOReader 分页兼容（见 Readest normalizeProgressXPointer 的语义）。 */
    fun normalize(xpointer: String): String {
        var s = xpointer
        Regex("/text\\(\\).*$").find(s)?.let { s = s.removeRange(it.range) }
        Regex("\\.\\d+$").find(s)?.let { s = s.removeRange(it.range) }
        return s
    }
}
