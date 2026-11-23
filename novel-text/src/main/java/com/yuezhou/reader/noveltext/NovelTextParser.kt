package com.yuezhou.reader.noveltext

/** 独立解析模块：没有 Android、EPUB、TTS 或播放器依赖。所有偏移均为 UTF-16。 */
data class TextSource(val text: String, val nodeId: String, val offset: Int = 0, val paragraphEnd: Boolean = true) {
    init { require(offset >= 0) }
}
data class TextPoint(val nodeId: String, val offset: Int)
data class SourceSpan(val nodeId: String, val start: Int, val endExclusive: Int)
enum class SplitReason { SENTENCE, PARAGRAPH, SEMICOLON, COLON, COMMA, WHITESPACE, LENGTH_LIMIT }

data class NovelChunk(
    val originalText: String,
    val spokenText: String,
    /** 原文逐 UTF-16 字符位置和末位边界，不用 spokenText 的长度推导高亮。 */
    val sourcePoints: List<TextPoint>,
    val sourceSpans: List<SourceSpan>,
    val paragraphId: Int,
    val paragraphBoundaryAfter: Boolean,
    val dialogue: Boolean,
    val splitReason: SplitReason,
    val parserVersion: Int = NovelTextParser.VERSION,
)

class NovelTextParser(private val maxChars: Int = 120, private val minChars: Int = 12) {
    init { require(maxChars >= 8); require(minChars in 1..maxChars) }

    fun parse(sources: List<TextSource>): List<NovelChunk> {
        val result = mutableListOf<NovelChunk>()
        val text = StringBuilder()
        val points = mutableListOf<TextPoint>()
        var paragraph = 0
        fun flush() {
            if (text.isNotEmpty()) result += parseParagraph(text.toString(), points.toList(), paragraph)
            text.setLength(0); points.clear(); paragraph++
        }
        for (source in sources) {
            source.text.forEachIndexed { index, ch -> text.append(ch); points += TextPoint(source.nodeId, source.offset + index) }
            if (source.paragraphEnd) flush()
        }
        flush()
        return result
    }

    private fun parseParagraph(text: String, points: List<TextPoint>, paragraph: Int): List<NovelChunk> {
        if (text.isBlank()) return emptyList()
        val natural = mutableListOf<Int>()
        val quoted = BooleanArray(text.length)
        val quotes = ArrayDeque<Char>()
        val brackets = ArrayDeque<Char>()
        var terminalPending = false
        for (i in text.indices) {
            val ch = text[i]
            val wasQuoted = quotes.isNotEmpty()
            val apostrophe = ch in "'’" && text.getOrNull(i - 1)?.isLetterOrDigit() == true && text.getOrNull(i + 1)?.isLetterOrDigit() == true
            if (!apostrophe) {
                when {
                    quotes.lastOrNull() == ch -> quotes.removeLast()
                    ch in QUOTE_PAIRS -> quotes.addLast(QUOTE_PAIRS.getValue(ch))
                    brackets.lastOrNull() == ch -> brackets.removeLast()
                    ch in BRACKET_PAIRS -> brackets.addLast(BRACKET_PAIRS.getValue(ch))
                }
            }
            quoted[i] = wasQuoted || quotes.isNotEmpty()
            val sentenceEnd = isEnd(text, i)
            if (sentenceEnd) terminalPending = true
            else if (!ch.isWhitespace() && ch !in CLOSERS) terminalPending = false
            if (quotes.isEmpty() && brackets.isEmpty() && terminalPending &&
                (sentenceEnd || (wasQuoted && ch in QUOTE_PAIRS.values)) &&
                text.getOrNull(i + 1) !in ENDERS && text.getOrNull(i + 1) !in CLOSERS &&
                !(wasQuoted && hasAttribution(text, i + 1))) {
                natural += extendClosing(text, i + 1)
            }
        }
        val boundaries = (natural + text.length).distinct().sorted()
        val out = mutableListOf<NovelChunk>()
        var start = 0
        var boundaryCursor = 0
        while (start < text.length) {
            while (boundaryCursor < boundaries.size && boundaries[boundaryCursor] <= start) boundaryCursor++
            val limit = minOf(text.length, start + maxChars)
            var candidate = boundaryCursor
            while (candidate < boundaries.size && boundaries[candidate] < start + minChars && boundaries[candidate] != text.length) candidate++
            var end = boundaries.getOrNull(candidate)?.takeIf { it <= limit }
            var reason = if (end == text.length) SplitReason.PARAGRAPH else SplitReason.SENTENCE
            if (end == null) {
                val split = fallback(text, start, limit)
                end = split.first; reason = split.second
            }
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            val raw = text.substring(start, end)
            val charPoints = points.subList(start, end)
            val last = charPoints.last()
            val spans = mutableListOf<SourceSpan>()
            for (point in charPoints) {
                val previous = spans.lastOrNull()
                if (previous?.nodeId == point.nodeId && previous.endExclusive == point.offset) {
                    spans[spans.lastIndex] = previous.copy(endExclusive = point.offset + 1)
                } else spans += SourceSpan(point.nodeId, point.offset, point.offset + 1)
            }
            out += NovelChunk(raw, raw.replace(INVISIBLE, "").replace(SPACES, " ").trim(),
                charPoints + TextPoint(last.nodeId, last.offset + 1), spans, paragraph, end == text.length,
                (start until end).any { quoted[it] }, reason)
            start = end
        }
        return out
    }

    private fun fallback(text: String, start: Int, limit: Int): Pair<Int, SplitReason> {
        val minimum = start + minOf(minChars, (limit - start) / 2)
        for ((marks, reason) in SOFT_BREAKS) {
            for (i in limit - 1 downTo minimum) if (text[i] in marks) return i + 1 to reason
        }
        for (i in limit - 1 downTo minimum) if (text[i].isWhitespace()) return i + 1 to SplitReason.WHITESPACE
        return limit to SplitReason.LENGTH_LIMIT
    }

    private fun isEnd(text: String, i: Int): Boolean {
        val ch = text[i]
        if (ch !in ENDERS) return false
        if (ch != '.') return true
        if (text.getOrNull(i - 1)?.isDigit() == true && text.getOrNull(i + 1)?.isDigit() == true) return false
        if (text.getOrNull(i - 1)?.isLetter() == true && text.getOrNull(i + 1)?.isLetter() == true) return false
        val prefix = text.substring(maxOf(0, i - 12), i)
        val word = prefix.takeLastWhile { it.isLetter() || it == '.' }
        return word.lowercase() !in ABBREVIATIONS && !INITIALISM.matches(word)
    }

    private fun extendClosing(text: String, from: Int): Int {
        var end = from
        while (end < text.length && (text[end] in ENDERS || text[end] in CLOSERS || text[end].isWhitespace())) end++
        return end
    }

    private fun hasAttribution(text: String, from: Int): Boolean = ATTRIBUTION.containsMatchIn(text.substring(from, minOf(text.length, from + 24)))

    companion object {
        const val VERSION = 1001
        private val QUOTE_PAIRS = mapOf('“' to '”', '‘' to '’', '「' to '」', '『' to '』', '"' to '"', '\'' to '\'')
        private val BRACKET_PAIRS = mapOf('（' to '）', '(' to ')', '[' to ']', '【' to '】', '《' to '》', '〈' to '〉')
        private val ENDERS = setOf('。', '！', '？', '!', '?', '.')
        private val CLOSERS = setOf('”', '’', '」', '』', '"', '\'', '）', ')', ']', '】')
        private val SOFT_BREAKS = listOf("；;" to SplitReason.SEMICOLON, "：:" to SplitReason.COLON, "，,、" to SplitReason.COMMA)
        private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "prof", "etc", "vs", "e.g", "i.e", "no", "u.s", "u.k")
        private val INITIALISM = Regex("(?:[A-Za-z]\\.)+[A-Za-z]")
        private val ATTRIBUTION = Regex("^\\s*(?:他|她|它|我|少年|少女|老者|男子|女子)(?:低声|轻声|小声|急忙|缓缓|笑着|哭着|哽咽着|冷冷地|低声地)?(?:说|问|答|道|喊|叫|喝)")
        private val INVISIBLE = Regex("[\\u200B\\u200C\\u200D\\uFEFF]")
        private val SPACES = Regex("\\s+")
    }
}
