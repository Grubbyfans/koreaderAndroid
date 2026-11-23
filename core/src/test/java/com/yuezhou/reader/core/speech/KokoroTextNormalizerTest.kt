package com.yuezhou.reader.core.speech

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KokoroTextNormalizerTest {
    @Test
    fun `中文标点变为停顿且对白不读引号`() {
        assertEquals("他说:你好,世界!真的吗?", KokoroTextNormalizer.normalize("他说：“你好，世界！”真的吗？"))
        assertEquals("等一下,再说,好吧.", KokoroTextNormalizer.normalize("等一下……再说——好吧。"))
    }

    @Test
    fun `只有标点时不送入模型`() {
        for (text in listOf("……", "。", "？！", "”", "——", "「」", "（ ）", "...", " ")) {
            assertEquals("", KokoroTextNormalizer.normalize(text), text)
        }
    }

    @Test
    fun `保留数字小数金额英文与有意义的符号`() {
        val text = "don't re-enter: 3.14, 2026-11-23, ¥12.50, 50%, 1+2=3."
        assertEquals(text, KokoroTextNormalizer.normalize(text))
        assertEquals("don't stop.", KokoroTextNormalizer.normalize("don’t stop."))
    }

    @Test
    fun `书名号括号与换行不念符号名称`() {
        assertEquals("读悟空传 第三章 你好.", KokoroTextNormalizer.normalize("读《悟空传》（第三章）\n你好。"))
    }

    @Test
    fun `重复规范化保持一致`() {
        val text = KokoroTextNormalizer.normalize("“你好……”（他说）")
        assertEquals(text, KokoroTextNormalizer.normalize(text))
    }
}
