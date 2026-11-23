package com.yuezhou.reader.core.speech

/** 仅用于 Kokoro 合成输入；不改动原文片段、索引或高亮锚点。 */
object KokoroTextNormalizer {
    fun normalize(text: String): String {
        val normalized = buildString {
            text.forEachIndexed { index, ch ->
                append(when (ch) {
                    '，', '、', '…', '⋯', '—', '–' -> ","
                    '。', '．' -> "."
                    '！' -> "!"
                    '？' -> "?"
                    '；' -> ";"
                    '：' -> ":"
                    // 单词内的弯引号保留为撇号，例如 don’t。
                    '‘', '’' -> if (text.getOrNull(index - 1)?.isLetter() == true &&
                        text.getOrNull(index + 1)?.isLetter() == true) "'" else ""
                    '“', '”', '「', '」', '『', '』', '《', '》', '〈', '〉', '"' -> ""
                    '（', '）', '(', ')', '[', ']', '【', '】', '{', '}', '〔', '〕' -> " "
                    else -> ch.toString()
                })
            }
        }.replace(Regex("[,.;:!?]{2,}"), ",")
            .replace(Regex("\\s+"), " ")
            .trim()
        // 独立的句末引号、连续省略号等只表示停顿，禁止交给 G2P 念出符号名称。
        return normalized.takeIf { value -> value.any { !it.isWhitespace() && !isPunctuation(it) } }.orEmpty()
    }

    private fun isPunctuation(ch: Char): Boolean = when (Character.getType(ch)) {
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    /** 人工生成的 100 ms 静音，不是模型输出，不参与空音频健康检查。 */
    fun punctuationPause(): SynthesizedAudio = SynthesizedAudio(FloatArray(2400), 24_000)
}
