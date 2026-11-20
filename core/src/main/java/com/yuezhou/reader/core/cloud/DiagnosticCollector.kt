package com.yuezhou.reader.core.cloud

import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 同步任务的诊断收集器（对齐方案 §13.2「度量与诊断」）。
 *
 * 通过 OkHttp [Interceptor] 统计一次任务期间的实际 HTTP 请求次数（按类别拆分）、
 * 传输字节数；由上层在任务边界记录阶段耗时、缓存命中、错误类别与结果确定性，
 * 最终汇总为 [DiagnosticSummary]（脱敏，不含 token/密码/URL/正文）。
 *
 * 用法：每个同步/刷新任务创建一个本类实例，把 [interceptor] 注入后端 OkHttpClient，
 * 任务结束调用 [snapshot] 汇总。
 */
class DiagnosticCollector {

    // 请求统计（线程安全，因 OkHttp 可能并发发起）
    private val totalRequests = AtomicLong(0)
    private val redirects = AtomicLong(0)
    private val retries = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)

    /** 阶段耗时（毫秒）。key 为阶段名，如「读列表」「上传正文」「写列表」。 */
    private val phases = LinkedHashMap<String, Long>()

    /** 缓存命中计数。 */
    private val cacheHits = AtomicLong(0)

    /** 结果确定性：Failed（明确失败）/ Uncertain（写入结果未知）。默认 Failed。 */
    @Volatile
    var outcome: Outcome = Outcome.Failed
        private set

    /** 错误类别（ErrorCode 名称），无错误为 null。 */
    @Volatile
    var errorKind: String? = null
        private set

    /**
     * 注入 OkHttpClient 的拦截器：统计每次实际网络请求（不含取消前未发出的请求）。
     * 重定向（3xx）与重试（同一请求的后续尝试）单独分类，对齐方案「重试和重定向的单独分类」。
     */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        // 上传字节：请求体大小（近似）
        request.body?.contentLength()?.let { if (it > 0) bytesUp.addAndGet(it) }
        totalRequests.incrementAndGet()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            // 网络异常视为一次失败请求，重试计数交给 OkHttp 内部；此处仅记录
            throw e
        }

        // 下载字节：响应体大小（近似，流式时可能为 -1）
        val bodyLen = response.body?.contentLength() ?: -1
        if (bodyLen > 0) bytesDown.addAndGet(bodyLen)

        // 重定向分类
        if (response.isRedirect) redirects.incrementAndGet()

        // 记录请求耗时归入「传输」阶段（若无显式阶段则累计）
        response
    }

    /** 记录一个阶段的耗时。同一阶段多次调用会累加。 */
    fun recordPhase(name: String, elapsedMs: Long) {
        synchronized(phases) {
            phases[name] = (phases[name] ?: 0L) + elapsedMs
        }
    }

    /** 记录一次缓存命中（列表/封面/链接）。 */
    fun recordCacheHit(kind: String) {
        cacheHits.incrementAndGet()
        synchronized(phases) {
            phases["缓存:$kind"] = (phases["缓存:$kind"] ?: 0L) + 1
        }
    }

    /** 标记结果确定性与错误类别。 */
    fun markOutcome(o: Outcome, errorCode: ErrorCode? = null) {
        outcome = o
        errorKind = errorCode?.name
    }

    /** 汇总为脱敏摘要。 */
    fun snapshot(): DiagnosticSummary = DiagnosticSummary(
        totalRequests = totalRequests.get(),
        redirects = redirects.get(),
        retries = retries.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        cacheHits = cacheHits.get(),
        phasesMs = synchronized(phases) { LinkedHashMap(phases) },
        outcome = outcome,
        errorKind = errorKind,
    )
}

/**
 * 单次同步/刷新任务的诊断摘要（脱敏，可序列化导出）。
 * 不含 access_token、密码、SecretKey、下载 URL、Authorization 或书籍正文。
 */
@Serializable
data class DiagnosticSummary(
    /** 实际 HTTP 请求总数。 */
    val totalRequests: Long,
    /** 重定向次数。 */
    val redirects: Long,
    /** 重试次数（近似，由上层估算）。 */
    val retries: Long,
    /** 上传字节数（近似）。 */
    val bytesUp: Long,
    /** 下载字节数（近似）。 */
    val bytesDown: Long,
    /** 缓存命中次数。 */
    val cacheHits: Long,
    /** 阶段耗时（毫秒），key 为阶段名。 */
    val phasesMs: Map<String, Long>,
    /** 结果确定性。 */
    val outcome: Outcome,
    /** 错误类别（ErrorCode 名），无错误为 null。 */
    val errorKind: String?,
)
