package com.yuezhou.reader.core.cloud.baidu

import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaiduFormTest {

    @Test
    fun `escape 保留安全字符并编码其余`() {
        assertEquals("a-b.c_d~e", BaiduForm.escape("a-b.c_d~e"))
        assertEquals("%E4%B9%A6%E5%90%8D", BaiduForm.escape("书名"))
        assertEquals("%20", BaiduForm.escape(" "))
    }

    @Test
    fun `form 按键排序拼接`() {
        val result = BaiduForm.form(mapOf("b" to "2", "a" to "1", "c" to "3"))
        assertEquals("a=1&b=2&c=3", result)
    }
}

class BaiduJsonTest {

    @Test
    fun `asString 保留超长整数的原文`() {
        // fs_id 超过 2^53，必须保字符串原文
        val raw = """{"fs_id":9007199254740993,"uk":123456789012345678901234567890}"""
        val obj = BaiduJson.parse(raw)
        assertEquals("9007199254740993", BaiduJson.strField(obj, "fs_id"))
        assertEquals("123456789012345678901234567890", BaiduJson.strField(obj, "uk"))
    }

    @Test
    fun `asLong 读普通整数`() {
        val obj = BaiduJson.parse("""{"size":12345,"isdir":1}""")
        assertEquals(12345L, BaiduJson.longField(obj, "size"))
        assertEquals(1L, BaiduJson.longField(obj, "isdir"))
    }
}

class BaiduErrorsTest {

    @Test
    fun `errno 0 不抛错`() {
        val obj = BaiduJson.parse("""{"errno":0}""")
        BaiduErrors.checkError(obj) // 不应抛
    }

    @Test
    fun `授权错误码映射 AUTH`() {
        val obj = BaiduJson.parse("""{"errno":110}""")
        val e = assertThrows(CloudException::class.java) { BaiduErrors.checkError(obj) }
        assertEquals(ErrorCode.AUTH, e.code)
    }

    @Test
    fun `-9 映射 NOT_FOUND`() {
        val obj = BaiduJson.parse("""{"errno":-9}""")
        val e = assertThrows(CloudException::class.java) { BaiduErrors.checkError(obj) }
        assertEquals(ErrorCode.NOT_FOUND, e.code)
    }

    @Test
    fun `-8 映射 CONFLICT`() {
        val obj = BaiduJson.parse("""{"errno":-8}""")
        val e = assertThrows(CloudException::class.java) { BaiduErrors.checkError(obj) }
        assertEquals(ErrorCode.CONFLICT, e.code)
    }
}
