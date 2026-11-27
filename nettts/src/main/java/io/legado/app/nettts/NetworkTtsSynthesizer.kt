package io.legado.app.nettts

import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * 网络 TTS 合成（原版 `HttpReadAloudService.getSpeakStream` 的精简移植，GPLv3 来源）。
 *
 * 阶段 A 只做"单段合成 + 校验 + 落盘"，不做播放队列。
 * 关键约束：
 *  - 合成失败**不使用空白音频占位**，有限重试后抛出 [SynthesisException]，由上层暂停并提示；
 *  - JS 执行、HTTP 请求、落盘都受超时与协程取消约束；
 *  - 错误信息经 [NetworkTtsLog.sanitize] 脱敏后才进入异常消息。
 */
class NetworkTtsSynthesizer(
    private val cache: NetworkTtsAudioCache,
    private val maxRetries: Int = 2,
    private val jsTimeoutMs: Long = 8_000L,
    private val requestTimeoutMs: Long = 300_000L,
) {

    /**
     * 合成结果持有缓存引用，调用方在播放/排队结束时 [close]；关闭前 LRU 不会删除该文件。
     */
    class Result internal constructor(
        val file: File,
        val fromCache: Boolean,
        private val lease: NetworkTtsAudioCache.Lease,
    ) : Closeable {
        override fun close() = lease.close()
    }

    suspend fun synthesize(
        rule: HttpTTS,
        text: String,
        speechRate: Int,
        chapterTitle: String = "",
        forceRefresh: Boolean = false,
    ): Result {
        // Fix 2 防泄漏：leakedResult 接住 lambda 内部创建的 Result，
        // 确保 withContext 切回原 dispatcher 过程中被取消时 lease 不会泄漏。
        var leakedResult: Result? = null
        return try {
            withContext(Dispatchers.IO) {
                val key = NetworkTtsAudioCache.buildKey(
                    chapterTitle = chapterTitle,
                    ruleHash = ruleHash(rule),
                    speechRate = speechRate,
                    text = text,
                )
                cache.exclusive(key) {
                    val lease = cache.retain(key)
                    try {
                        if (!forceRefresh && cache.exists(key)) {
                            cache.touch(key)
                            val result = Result(cache.fileFor(key), true, lease)
                            leakedResult = result
                            return@exclusive result
                        }
                        var lastError: Throwable? = null
                        for (attempt in 0..maxRetries) {
                            var stream: InputStream? = null
                            try {
                                stream = openStream(rule, text, speechRate)
                                downloadToCache(key, stream)
                            } catch (e: TimeoutCancellationException) {
                                runCatching { stream?.close() }
                                // 内部超时必须转成 SynthesisException 让上层知道是服务不可用，
                                // 而不是用户取消。ensureActive 检查外部 Job 是否已被取消，
                                // 若已取消则保留取消语义，不再包一层。
                                currentCoroutineContext().ensureActive()
                                throw SynthesisException("TTS 合成超时", e)
                            } catch (e: CancellationException) {
                                runCatching { stream?.close() }
                                throw e
                            } catch (e: IOException) {
                                runCatching { stream?.close() }
                                currentCoroutineContext().ensureActive()
                                lastError = e
                                NetworkTtsLog.put("第 ${attempt + 1} 次合成下载失败：${e.message}", e)
                                continue
                            } catch (e: Throwable) {
                                runCatching { stream?.close() }
                                currentCoroutineContext().ensureActive()
                                // 致命错误不重试
                                if (e is SynthesisException ||
                                    e is NetworkTtsAudioCache.SizeLimitException ||
                                    e is AnalyzeUrl.UnsupportedOptionException ||
                                    isScriptError(e)
                                ) {
                                    if (e is SynthesisException) throw e
                                    throw SynthesisException(describe(e), e)
                                }
                                lastError = e
                                NetworkTtsLog.put("第 ${attempt + 1} 次合成失败：${e.message}", e)
                                continue
                            }
                            runCatching { stream?.close() }
                            // 成功落盘，返回带 lease 的 Result
                            val result = Result(cache.fileFor(key), false, lease)
                            leakedResult = result
                            return@exclusive result
                        }
                        throw SynthesisException(describe(lastError), lastError)
                    } catch (e: Throwable) {
                        lease.close()
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            // withContext 可能在 lambda 已 return 但切线程时被取消，
            // 此时 Result 已经被创建（持有 lease）但 withContext 不会把它返回给调用方。
            runCatching { leakedResult?.close() }
            throw e
        }
    }

    /**
     * 阻塞下载并落盘，同时保证协程取消时立即中断底层 read。
     *
     * [invokeOnCancellation] 与 [invokeOnCompletion] 的关键区别：
     * 前者在 Job 被标记取消的**瞬间**立即执行回调（不等待协程到达取消点），
     * 后者在协程**实际返回/抛出**后才调度。本场景中协程正阻塞在
     * `InputStream.read()` 上，永远到不了取消点，必须用前者让 OkHttp
     * Response 的底层 socket 被强制关闭，从而让 `read()` 抛 IOException。
     */
    private suspend fun downloadToCache(key: String, stream: InputStream) {
        try {
            withTimeout(requestTimeoutMs) {
                cache.write(key) { output ->
                    suspendCancellableCoroutine { cont ->
                        // 取消发生时立即关闭 stream → OkHttp socket 关闭 → 阻塞 read 抛 IOException
                        cont.invokeOnCancellation { runCatching { stream.close() } }
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        try {
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                if (read > 0) output.write(buffer, 0, read)
                            }
                            cont.resume(Unit, onCancellation = {})
                        } catch (e: IOException) {
                            // 可能是 invokeOnCancellation 触发 stream.close() 导致的。
                            // 若是用户取消，上层 ensureActive 会在 catch 块里正确传播 CancellationException。
                            cont.resumeWithException(e)
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw SynthesisException("TTS 合成超时", e)
        }
    }

    /** 打开音频流：规则解析 → 请求 → loginCheckJs → Content-Type 校验 → 首字节校验。
     *
     * Response 所有权规则（严格执行，避免取消时泄漏连接）：
     *  - 从 OkHttp enqueue 拿到 Response 的瞬间起，**必须**立即纳入 try/finally 保护。
     *  - 阻塞的首字节读取不在本函数内做，而是由 [checkedAudioStream] 延迟到调用方
     *    （downloadToCache 的 suspendCancellableCoroutine）触发。这样整个阻塞下载
     *    过程都在 invokeOnCancellation 保护下，取消瞬间立即关闭底层 socket。
     */
    private suspend fun openStream(rule: HttpTTS, text: String, speechRate: Int): InputStream {
        // 阶段 1：URL 解析（含 @js 模板）。AnalyzeUrl 构造时传入本阶段活跃的 Job，
        // Rhino 指令观察器据此在 jsTimeoutMs 超时后中断 URL 模板里的死循环；
        // header 脚本及其首次初始化的 jsLib 同样纳入取消检查。
        val analyzeUrl = withTimeout(jsTimeoutMs) {
            AnalyzeUrl(
                rule.url,
                speakText = text,
                speakSpeed = speechRate,
                source = rule,
                readTimeout = requestTimeoutMs,
                callTimeout = requestTimeoutMs,
                coroutineContext = currentCoroutineContext(),
            )
        }
        // 阶段 2：请求 + loginCheckJs，整体受 requestTimeoutMs 约束。
        // 每个执行 JS 的子阶段都把 AnalyzeUrl 的上下文切换为当前活跃 Job——
        // 否则复用已完成（completed）的 Job 会让 Rhino ensureActive 抛"任务已完成"。
        val response: Response = withTimeout(requestTimeoutMs) {
            analyzeUrl.updateCoroutineContext(currentCoroutineContext())
            // errResponse 分支：原始请求抛异常，loginCheckJs 在合成的错误响应上执行
            val errRaw = try {
                analyzeUrl.getResponseAwait()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val checkJs = rule.loginCheckJs
                if (checkJs.isNullOrBlank()) throw e
                val errResponse = analyzeUrl.getErrResponse(e)
                val checked = try {
                    evalLoginCheck(analyzeUrl, checkJs, errResponse)
                } catch (e2: Throwable) {
                    runCatching { errResponse.close() }
                    throw e2
                }
                if (checked == null || checked.code == 500) {
                    runCatching { errResponse.close() }
                    throw e
                }
                return@withTimeout checked
            }
            // OK 响应分支：从这里起 errRaw 归本 try/finally 所有。
            // cancelRaw 在本路径结束时根据所有权决定是否关闭。
            val cancelRaw = AtomicBoolean(true)
            try {
                val coroutineContext = currentCoroutineContext()
                coroutineContext.ensureActive()
                val checkJs = rule.loginCheckJs
                if (checkJs.isNullOrBlank()) {
                    cancelRaw.set(false)  // 所有权转移给外层
                    return@withTimeout errRaw
                }
                val replaced = try {
                    evalLoginCheck(analyzeUrl, checkJs, errRaw)
                } catch (e: Throwable) {
                    runCatching { errRaw.close() }
                    throw e
                }
                if (replaced != null && replaced !== errRaw) {
                    // 新响应由外层接管；原响应仍由 finally 释放。
                    return@withTimeout replaced
                }
                // loginCheckJs 返回 null 或同一个 errRaw → 所有权转移给外层
                cancelRaw.set(false)
                return@withTimeout errRaw
            } finally {
                if (cancelRaw.get()) runCatching { errRaw.close() }
            }
        }
        // response 现在归外层所有。类型校验只查看已缓冲的错误内容，不读取网络。
        // 首字节读取由 checkedAudioStream 延迟到 downloadToCache。
        // 整个 post-response 阶段都在这个 try/finally 保护下，任何异常都会关闭 response。
        try {
            val coroutineContext = currentCoroutineContext()
            coroutineContext.ensureActive()
            validateContentType(rule, response)
            return checkedAudioStream(response.body.byteStream())
        } catch (e: Throwable) {
            runCatching { response.close() }
            throw e
        }
    }

    /** 在独立的 jsTimeoutMs 活跃 Job 下执行 loginCheckJs：既让死循环可被超时中断，也避免误用已完成 Job。 */
    private suspend fun evalLoginCheck(analyzeUrl: AnalyzeUrl, checkJs: String, response: Response): Response? =
        withTimeout(jsTimeoutMs) {
            analyzeUrl.updateCoroutineContext(currentCoroutineContext())
            analyzeUrl.evalJS(checkJs, response) as? Response
        }

    private fun validateContentType(rule: HttpTTS, response: Response) {
        // peekBody 会主动等待网络数据；错误类型已确定时只取现有缓冲，避免停止朗读被阻塞。
        val errorBody = {
            runCatching {
                val buffer = response.body.source().buffer
                buffer.snapshot(minOf(buffer.size, 4_096L).toInt()).string(
                    response.body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                )
            }.getOrDefault("")
        }
        val contentType = response.headers["Content-Type"] ?: return
        val mime = contentType.substringBefore(";").trim()
        val expected = rule.contentType
        if (mime == "application/json" || mime.startsWith("text/")) {
            throw SynthesisException(
                NetworkTtsLog.sanitize("TTS 服务器返回错误（$mime）：${errorBody()}")
            )
        }
        if (!expected.isNullOrBlank() && !mime.matches(expected.toRegex())) {
            throw SynthesisException(
                NetworkTtsLog.sanitize("TTS 返回类型不匹配（期望 $expected，实际 $mime）：${errorBody()}")
            )
        }
    }

    /**
     * 构建"挂起首字节"流包装器——首字节的阻塞读取**延迟到第一次 read() 调用时**。
     *
     * openStream 成功返回后，调用方（downloadToCache）才真正开始消费 stream。
     * downloadToCache 的 suspendCancellableCoroutine 已在 invokeOnCancellation
     * 里注册了 stream.close()——因此首字节的阻塞读取发生在 invokeOnCancellation 保护下：
     * 取消瞬间关闭底层 socket → read() 抛 IOException → 立即返回。
     *
     * 如果服务器在响应头后迟迟不发送音频体（最常见的卡顿场景），本机制能让停止朗读
     * 在 1–2 秒内终止，而不是等待 300s readTimeout。
     */
    private fun checkedAudioStream(stream: InputStream): InputStream {
        var validatedFirstByte: Int? = null  // null=未读，非负=已读的有效首字节

        fun ensureFirstByte(): Int {
            validatedFirstByte?.let { return it }
            val head = ByteArray(1)
            var read: Int
            do {
                read = stream.read(head)
                if (read <= 0) throw SynthesisException("TTS 返回空音频")
            } while (head[0].toInt().toChar().isWhitespace())
            validatedFirstByte = head[0].toInt() and 0xFF
            return validatedFirstByte!!
        }

        return object : InputStream() {
            private var firstByteConsumed = false

            override fun read(): Int {
                if (!firstByteConsumed) {
                    firstByteConsumed = true
                    return ensureFirstByte()
                }
                return stream.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len <= 0) return 0
                if (!firstByteConsumed) {
                    firstByteConsumed = true
                    b[off] = ensureFirstByte().toByte()
                    return 1
                }
                return stream.read(b, off, len)
            }

            override fun close() = stream.close()
        }
    }

    private fun isScriptError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is com.script.ScriptException ||
                current is org.htmlunit.corejs.javascript.RhinoException ||
                current is org.htmlunit.corejs.javascript.WrappedException
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun describe(throwable: Throwable?): String {
        if (throwable == null) return "TTS 合成失败"
        val prefix = when {
            isScriptError(throwable) -> "规则脚本执行出错"
            throwable is AnalyzeUrl.UnsupportedOptionException -> "规则包含暂不支持的参数"
            throwable is java.net.SocketTimeoutException -> "TTS 请求超时"
            throwable is java.net.ConnectException -> "TTS 服务器连接失败"
            throwable is java.net.UnknownHostException -> "无法解析 TTS 服务器域名"
            throwable is SynthesisException -> "TTS 合成失败"
            else -> "TTS 合成失败"
        }
        val detail = NetworkTtsLog.sanitize(throwable.localizedMessage ?: throwable.javaClass.simpleName)
        return "$prefix：$detail"
    }

    /**
     * 规则特征哈希：缓存键的一部分。
     *
     * 除 url/header/jsLib/loginCheckJs 外，还纳入 `sourceVariable`（规则自定义变量，
     * 常用于切换音色/发音人等）。这样修改影响音频结果的配置后不会复用旧缓存。
     * 登录短期凭证本身（token/cookie/sign/expire 等）**不**纳入——它们是动态值，
     * 纳入会导致缓存永久失效；但登录信息里影响音色的任意字段（无论中英文键名）
     * 都纳入，覆盖"发言人""风格""voiceId"等自定义键。
     */
    fun ruleHash(rule: HttpTTS): String {
        val identity = buildString {
            arrayOf(
                rule.url,
                rule.contentType.orEmpty(),
                rule.header.orEmpty(),
                rule.jsLib.orEmpty(),
                rule.loginCheckJs.orEmpty(),
                rule.concurrentRate.orEmpty(),
                rule.getVariable(),
                loginAudioConfig(rule),
            ).forEach { append(it.length).append(':').append(it) }
        }
        return NetworkTtsAudioCache.md5(identity).take(16)
    }

    /**
     * 纳入登录配置中所有**非短期凭证**的字段，覆盖任意中英文键名。
     *
     * 与原版的规则兼容性考虑：原版规则可能在用户登录后把音色/风格信息存入登录表单
     * （如"发言人"、"speaker"、"voiceId"等），这些字段值直接影响最终合成内容，
     * 必须纳入缓存身份。token/cookie/sign/expire 等短期值排除在外，避免每次鉴权
     * 刷新导致缓存全部失效。
     */
    private fun loginAudioConfig(rule: HttpTTS): String {
        val values = mutableListOf<String>()
        listOf(rule.getLoginInfo(), rule.getLoginHeader()).forEach { raw ->
            if (raw.isNullOrBlank()) return@forEach
            val obj = runCatching { LOGIN_JSON.parseToJsonElement(raw) }.getOrNull() as? JsonObject
                ?: return@forEach
            obj.forEach { (key, value) ->
                val normalizedKey = key.lowercase().filter { it.isLetterOrDigit() }
                if (normalizedKey in SHORT_TERM_KEYS) return@forEach
                values.add("$key=$value")
            }
        }
        return values.sorted().joinToString("|")
    }

    /** 合成失败（含服务端返回非音频内容、空音频、参数不支持）。 */
    class SynthesisException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    companion object {
        private const val COPY_BUFFER_SIZE = 16 * 1024
        private val LOGIN_JSON = Json { ignoreUnknownKeys = true }

        /** 短期凭证键名（大小写不敏感，仅保留字母数字后匹配），不纳入缓存身份。 */
        private val SHORT_TERM_KEYS = setOf(
            "token", "accesstoken", "refreshtoken", "cookie", "secret",
            "accesskey", "sign", "signature", "bearer", "auth",
            "authorization", "sessionid", "session", "nonce", "timestamp",
            "expire", "expired", "expirytime", "jwt", "hdrtoken",
        )

        /**
         * 最终请求参数 `speakSpeed` 的范围与默认值。
         *
         * 原版规则 JS 里通过 `speakSpeed` 变量取值（由 AnalyzeUrl 绑定），范围 5–20，
         * 默认 10。静读把界面倍速（0.5–2.0）直接映射到这个范围，不再额外 +5。
         */
        const val MIN_SPEECH_RATE = 5
        const val MAX_SPEECH_RATE = 20
        const val DEFAULT_SPEECH_RATE = 10

        /**
         * 界面倍速（0.5–2.0，默认 1.0）→ 最终请求参数 speakSpeed（5–20，默认 10）。
         * 试听与正式朗读共用一个入口，保证同一规则两条链路的语速一致。
         *
         * 映射公式 `round(rate × 10)` 直接得到 speakSpeed 值：
         *   0.5 → 5,  1.0 → 10,  1.5 → 15,  2.0 → 20
         */
        fun uiRateToSpeechRate(uiRate: Float): Int =
            (uiRate * 10).roundToInt().coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)
    }
}
