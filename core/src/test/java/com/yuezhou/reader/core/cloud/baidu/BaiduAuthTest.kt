package com.yuezhou.reader.core.cloud.baidu

import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class BaiduAuthTest {

    @Test
    fun `授权URL包含OOB回跳与netdisk权限`() {
        val url = BaiduAuth.authorizationUrl("testAppKey", "state123")
        assertTrue(url.startsWith("https://openapi.baidu.com/oauth/2.0/authorize?"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=oob"))
        assertTrue(url.contains("scope=basic%2Cnetdisk") || url.contains("scope=basic,netdisk"))
        assertTrue(url.contains("client_id=testAppKey"))
        assertTrue(url.contains("display=mobile"))
    }

    @Test
    fun `缺少AppKey时构造授权URL报错`() {
        try {
            BaiduAuth.authorizationUrl("  ", "state")
            fail("应抛 INVALID_INPUT")
        } catch (e: CloudException) {
            assertEquals(ErrorCode.INVALID_INPUT, e.code)
        }
    }

    @Test
    fun `授权码包含非法字符时报INVALID_INPUT且不发网络`() {
        try {
            // 含空格/网址等非法字符，应在发请求前被拦截
            BaiduAuth.exchangeCode("key", "secret", "https://example.com/?code=abc")
            fail("应抛 INVALID_INPUT")
        } catch (e: CloudException) {
            assertEquals(ErrorCode.INVALID_INPUT, e.code)
        }
    }

    @Test
    fun `空授权码报INVALID_INPUT`() {
        try {
            BaiduAuth.exchangeCode("key", "secret", "   ")
            fail("应抛 INVALID_INPUT")
        } catch (e: CloudException) {
            assertEquals(ErrorCode.INVALID_INPUT, e.code)
        }
    }
}
