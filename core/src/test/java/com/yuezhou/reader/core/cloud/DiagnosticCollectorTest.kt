package com.yuezhou.reader.core.cloud

import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * DiagnosticCollector 单元测试：验证请求计数、字节统计、阶段耗时、结果确定性。
 *
 * 请求计数通过手动构造的 [Interceptor.Chain] 模拟（不依赖 mockwebserver），
 * 纯逻辑（阶段耗时/缓存命中/结果确定性）直接调用验证。
 */
class DiagnosticCollectorTest {

    /** 手动构造一个拦截器链，让被测拦截器执行并返回指定响应。 */
    private class FakeChain(
        private val request: Request,
        private val response: Response,
    ) : Interceptor.Chain {
        override fun request(): Request = request
        override fun call() = throw UnsupportedOperationException()
        override fun connectTimeoutMillis() = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = this
        override fun readTimeoutMillis() = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit) = this
        override fun writeTimeoutMillis() = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = this
        override fun connection(): Connection? = null
        override fun proceed(request: Request): Response = response
    }

    private fun okResponse(body: String, code: Int = 200): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.com/x").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(body.toResponseBody())
            .build()

    @Test
    fun `统计请求数与下载字节`() {
        val collector = DiagnosticCollector()
        val body = "hello world"
        val chain = FakeChain(
            Request.Builder().url("https://example.com/x").build(),
            okResponse(body),
        )
        collector.interceptor.intercept(chain)

        val snap = collector.snapshot()
        assertEquals(1, snap.totalRequests)
        assertEquals(body.length.toLong(), snap.bytesDown)
        assertEquals(0, snap.bytesUp)
        assertEquals(0, snap.redirects)
    }

    @Test
    fun `统计重定向响应`() {
        val collector = DiagnosticCollector()
        val chain = FakeChain(
            Request.Builder().url("https://example.com/start").build(),
            okResponse("", code = 302),
        )
        collector.interceptor.intercept(chain)

        val snap = collector.snapshot()
        assertEquals(1, snap.redirects)
        assertEquals(1, snap.totalRequests)
    }

    @Test
    fun `阶段耗时累加与结果确定性`() {
        val collector = DiagnosticCollector()
        collector.recordPhase("读列表", 100L)
        collector.recordPhase("读列表", 50L)
        collector.recordPhase("上传正文", 200L)
        collector.markOutcome(Outcome.Uncertain, ErrorCode.NETWORK)

        val snap = collector.snapshot()
        assertEquals(150L, snap.phasesMs["读列表"])
        assertEquals(200L, snap.phasesMs["上传正文"])
        assertEquals(Outcome.Uncertain, snap.outcome)
        assertEquals("NETWORK", snap.errorKind)
    }

    @Test
    fun `缓存命中计数`() {
        val collector = DiagnosticCollector()
        collector.recordCacheHit("列表")
        collector.recordCacheHit("列表")
        collector.recordCacheHit("封面")

        val snap = collector.snapshot()
        assertEquals(3, snap.cacheHits)
        assertEquals(2L, snap.phasesMs["缓存:列表"])
        assertEquals(1L, snap.phasesMs["缓存:封面"])
    }
}
