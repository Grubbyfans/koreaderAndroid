package io.legado.app.nettts

import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import java.io.File
import java.io.InputStream
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

    /** 合成结果：音频文件 + 是否命中缓存。 */
    data class Result(val file: File, val fromCache: Boolean)

    suspend fun synthesize(
        rule: HttpTTS,
        text: String,
        speechRate: Int,
        chapterTitle: String = "",
        forceRefresh: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val key = NetworkTtsAudioCache.buildKey(
            chapterTitle = chapterTitle,
            ruleHash = ruleHash(rule),
            speechRate = speechRate,
            text = text,
        )
        if (!forceRefresh && cache.exists(key)) {
            cache.touch(key)
            return@withContext Result(cache.fileFor(key), true)
        }
        var lastError: Throwable? = null
        for (attempt in 0..maxRetries) {
            try {
                val stream = openStream(rule, text, speechRate)
                try {
                    // 逐块拷贝并在每块前检查取消：既能及时响应"停止试听/切规则"，
                    // 也能在服务器缓慢吐流时立刻退出（阻塞单次 read 仍受 readTimeout 约束）。
                    cache.write(key) { output ->
                        stream.use { input ->
                            val buffer = ByteArray(COPY_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = withContext(Dispatchers.IO) { input.read(buffer) }
                                if (read < 0) break
                                if (read > 0) output.write(buffer, 0, read)
                            }
                        }
                    }
                } finally {
                    runCatching { stream.close() }
                }
                return@withContext Result(cache.fileFor(key), false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                lastError = e
                if (e is AnalyzeUrl.UnsupportedOptionException || isScriptError(e)) {
                    throw SynthesisException(describe(e), e)
                }
                NetworkTtsLog.put("第 ${attempt + 1} 次合成失败：${e.message}", e)
            }
        }
        throw SynthesisException(describe(lastError), lastError)
    }

    /** 打开音频流：规则解析 → 请求 → loginCheckJs → Content-Type 校验 → 首字节校验。 */
    private suspend fun openStream(rule: HttpTTS, text: String, speechRate: Int): InputStream {
        // 阶段 1：URL 解析（含 @js 模板）。AnalyzeUrl 构造时传入本阶段活跃的 Job，
        // Rhino 指令观察器据此在 jsTimeoutMs 超时后中断 URL 模板里的死循环。
        val analyzeUrl = withTimeout(jsTimeoutMs) {
            AnalyzeUrl(
                rule.url,
                speakText = text,
                speakSpeed = requestSpeechRate(speechRate),
                source = rule,
                readTimeout = requestTimeoutMs,
                callTimeout = requestTimeoutMs,
                coroutineContext = currentCoroutineContext(),
            )
        }
        val outerContext = currentCoroutineContext()
        outerContext.ensureActive()
        // 阶段 2：请求 + loginCheckJs，整体受 requestTimeoutMs 约束。
        // 每个执行 JS 的子阶段都把 AnalyzeUrl 的上下文切换为当前活跃 Job——
        // 否则复用已完成（completed）的 Job 会让 Rhino ensureActive 抛"任务已完成"。
        val response = withTimeout(requestTimeoutMs) {
            analyzeUrl.updateCoroutineContext(currentCoroutineContext())
            val raw = try {
                analyzeUrl.getResponseAwait()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val checkJs = rule.loginCheckJs
                if (checkJs.isNullOrBlank()) throw e
                val errResponse = analyzeUrl.getErrResponse(e)
                val checked = evalLoginCheck(analyzeUrl, checkJs, errResponse)
                if (checked == null || checked.code == 500) {
                    runCatching { errResponse.close() }
                    throw e
                }
                return@withTimeout checked
            }
            outerContext.ensureActive()
            val checkJs = rule.loginCheckJs
            if (checkJs.isNullOrBlank()) return@withTimeout raw
            val replaced = evalLoginCheck(analyzeUrl, checkJs, raw)
            // loginCheckJs 返回新响应时，释放被替换的旧响应，避免连接泄漏。
            if (replaced != null && replaced !== raw) runCatching { raw.close() }
            replaced ?: raw
        }
        outerContext.ensureActive()
        // 校验失败/取消时必须关闭 response；成功路径由返回流的 close 负责关闭 body。
        try {
            validateContentType(rule, response)
            val checked = checkedAudioStream(response.body.byteStream())
            outerContext.ensureActive()
            return checked
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
        val errorBody = { runCatching { response.peekBody(4_096).string() }.getOrDefault("") }
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
     * 首字节校验：跳过前导空白后必须还有内容，否则判为失败。
     * 原版用同样的手段拦截"返回 200 但内容为空"的服务端静默错误。
     */
    private fun checkedAudioStream(stream: InputStream): InputStream {
        val head = ByteArray(1)
        var read: Int
        do {
            read = stream.read(head)
            if (read <= 0) throw SynthesisException("TTS 返回空音频")
        } while (head[0].toInt().toChar().isWhitespace())
        return object : InputStream() {
            private var pending: Int = head[0].toInt() and 0xFF

            override fun read(): Int {
                if (pending >= 0) {
                    val value = pending
                    pending = -1
                    return value
                }
                return stream.read()
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (pending >= 0) {
                    b[off] = pending.toByte()
                    pending = -1
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
     * 登录凭证本身（token/cookie）**不**纳入——它们是短期值，纳入会导致缓存永久失效；
     * 若登录信息里的音色体现在最终 URL，则已被 url 覆盖。
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
            ).forEach { append(it.length).append(':').append(it) }
        }
        return NetworkTtsAudioCache.md5(identity).take(16)
    }

    /** 合成失败（含服务端返回非音频内容、空音频、参数不支持）。 */
    class SynthesisException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    companion object {
        private const val COPY_BUFFER_SIZE = 16 * 1024

        /** 内部语速范围与默认值（对应原版 `AppConfig.ttsSpeechRate`，默认 10）。 */
        const val MIN_SPEECH_RATE = 0
        const val MAX_SPEECH_RATE = 20
        const val DEFAULT_SPEECH_RATE = 10

        /**
         * 界面倍速（0.5~2.0，默认 1.0）→ 内部语速（0~20，默认 10）。
         * 试听与正式朗读共用一个入口，保证同一规则两条链路的语速一致。
         */
        fun uiRateToSpeechRate(uiRate: Float): Int =
            (uiRate * 10).roundToInt().coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)

        /** 内部语速 → 实际请求参数 `speakSpeed`：与原版一致 `+5`（默认 10 → 15）。 */
        fun requestSpeechRate(speechRate: Int): Int = speechRate + 5
    }
}
