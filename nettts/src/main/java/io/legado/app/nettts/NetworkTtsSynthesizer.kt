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
        val analyzeUrl = withTimeout(jsTimeoutMs) {
            AnalyzeUrl(
                rule.url,
                speakText = text,
                speakSpeed = speechRate,
                source = rule,
                readTimeout = requestTimeoutMs,
                callTimeout = requestTimeoutMs,
                // 注入带超时的子协程上下文：Rhino 指令观察器据此中断死循环脚本。
                coroutineContext = currentCoroutineContext(),
            )
        }
        val context = currentCoroutineContext()
        context.ensureActive()
        val response = withTimeout(requestTimeoutMs) {
            try {
                analyzeUrl.getResponseAwait().let { response ->
                    context.ensureActive()
                    val checkJs = rule.loginCheckJs
                    if (!checkJs.isNullOrBlank()) {
                        (analyzeUrl.evalJS(checkJs, response) as? Response) ?: response
                    } else {
                        response
                    }
                }
            } catch (e: Throwable) {
                val checkJs = rule.loginCheckJs
                if (checkJs.isNullOrBlank()) throw e
                val errResponse = analyzeUrl.getErrResponse(e)
                val checked = analyzeUrl.evalJS(checkJs, errResponse) as? Response
                if (checked == null || checked.code == 500) throw e
                checked
            }
        }
        context.ensureActive()
        // 不关闭 response：流由调用方消费完后 close（与原版一致），提前关闭会让后续读取报错。
        validateContentType(rule, response)
        val checked = checkedAudioStream(response.body.byteStream())
        context.ensureActive()
        return checked
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
     * 原版缓存键不含 header/jsLib/loginCheckJs，规则改动会复用旧音频；这里补上，
     * 且不放登录头/凭证，避免凭证变化导致缓存永久失效。
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

        /** 语音速率基准：与原版一致，`AppConfig.ttsSpeechRate + 5`。 */
        fun requestSpeechRate(speechRate: Int): Int = speechRate + 5
    }
}
