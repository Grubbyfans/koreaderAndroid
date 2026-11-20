package com.yuezhou.reader.core.speech

/**
 * 分句器：把章节文本流切成朗读片段（句）。
 *
 * 对齐技术方案 §7.2：
 *   - 优先按句切分；过长句子继续分段，遵守 TTS 引擎输入长度上限。
 *   - 对中文、英文及混合文本分别验证分句。
 *   - 数学公式、图片和复杂表格暂按明确的跳过策略处理（由上层 [ChapterTextSource]
 *     在提取阶段剔除，本分句器不识别公式/表格）。
 *
 * 本文件纯 Kotlin，无 Android 依赖，可离线单测。
 */
class SentencePlanner(
    /** 单片段最大字符数；超过则强制切段（默认 256，避免过长句子超 TTS 输入上限）。 */
    private val maxSegmentChars: Int = 256,
    /** 是否把换行也视作句边界（段落边界通常对应语义停顿）。 */
    private val breakOnNewline: Boolean = true,
) {
    /**
     * 把一章文本单元切分为朗读片段。
     *
     * 返回的每个 [SpeechSegment.text] 是纯文本，且每个片段都携带
     * 朗读文本偏移 → 原文锚点 的 [SpeechSegment.charToSource] 映射。
     */
    fun plan(units: List<SpeechTextUnit>): List<SpeechSegment> {
        val segments = mutableListOf<SpeechSegment>()
        // 累积一段文本及其逐字符锚点。
        val buf = StringBuilder()
        val anchors = ArrayList<SourceAnchor>()

        fun flush() {
            if (buf.isEmpty()) return
            // 切出当前累积内容为若干片段（可能因超长被再切）。
            splitAndEmit(buf.toString(), anchors.toList(), segments)
            buf.setLength(0)
            anchors.clear()
        }

        for (unit in units) {
            if (unit.text.isEmpty()) continue
            for ((i, ch) in unit.text.withIndex()) {
                // 片段起始处跳过前导空白（句末标点后的空格等），避免片段以空格开头。
                if (buf.isEmpty() && ch.isWhitespace()) continue
                buf.append(ch)
                anchors.add(SourceAnchor(unit.anchor.nodeId, unit.anchor.offset + i))
                if (isSentenceBoundary(ch)) {
                    flush()
                } else if (buf.length >= maxSegmentChars) {
                    // 达到上限仍无句边界，强制切段（在当前位置切断）。
                    flush()
                }
            }
            // 段落/单元边界必然是语义停顿点：结束当前累积片段。
            flush()
        }
        flush()

        return segments
    }

    /** 判断当前字符是否为句边界（在其之后切分）。 */
    private fun isSentenceBoundary(ch: Char): Boolean {
        // 中英文句末标点。
        if (ch in SENTENCE_ENDERS) return true
        // 换行视作边界。
        if (breakOnNewline && (ch == '\n' || ch == '\r')) return true
        return false
    }

    /**
     * 把一段累积文本按长度切分为若干 [SpeechSegment]，并维护锚点映射。
     */
    private fun splitAndEmit(
        text: String,
        anchors: List<SourceAnchor>,
        out: MutableList<SpeechSegment>,
    ) {
        val len = text.length
        if (len == 0) return
        var start = 0
        while (start < len) {
            var end = (start + maxSegmentChars).coerceAtMost(len)
            // 尽量在句边界处切，避免在单词中间截断（对英文友好）。
            if (end < len) {
                val better = findBreakBefore(text, start, end)
                if (better > start) end = better
            }
            val segText = text.substring(start, end)
            // 锚点映射：本片段对应 anchors[start .. end]（含末位边界，故 end+1 个）。
            val segAnchors = buildList {
                for (i in start..end) {
                    // end == len 时 anchors 只有 len 个，末位边界复用最后一个锚点。
                    val idx = i.coerceAtMost(anchors.lastIndex)
                    add(anchors[idx])
                }
            }
            val startAnchor = anchors.getOrElse(start) { anchors.lastOrNull() ?: SourceAnchor("", 0) }
            val endAnchor = anchors.getOrElse((end - 1).coerceAtLeast(0)) { startAnchor }
            out.add(SpeechSegment(segText, startAnchor, endAnchor, segAnchors))
            start = end
        }
    }

    /** 在 (start, end) 范围内找最近的回退切分点（空白/标点后），找不到返回 -1。 */
    private fun findBreakBefore(text: String, start: Int, end: Int): Int {
        // 从 end 向前找第一个空格或标点。
        for (i in end - 1 downTo start + 1) {
            val c = text[i]
            if (c.isWhitespace() || c in BREAK_CHARS) return i + 1
        }
        return -1
    }

    companion object {
        /** 句末标点（中文 + 英文 + 其它常见）。 */
        private val SENTENCE_ENDERS = setOf(
            '。', '！', '？', '；', '…', // 中文（… 为省略号 U+2026）
            '.', '!', '?', ';', // 英文
        )

        /** 可回退切分的字符（英文分词友好的标点）。 */
        private val BREAK_CHARS = setOf(',', '，', '、', ':', '：', '-', '—', ' ')
    }
}
