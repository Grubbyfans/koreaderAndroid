package io.legado.app.nettts

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地 Mock 服务验证合成链路（不依赖外部网络）。
 *
 * 覆盖：正常音频落盘、JSON 错误响应不走静音兜底、空响应报错、缓存复用、JS 死循环超时。
 */
class NetworkTtsSynthesizerTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private lateinit var cacheDir: File

    private var lastQuery: String? = null
    private val requestCount = AtomicInteger(0)

    @BeforeEach
    fun setUp() {
        cacheDir = Files.createTempDirectory("nettts-test").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/tts") { exchange -> handleTts(exchange) }
        server.createContext("/error") { exchange ->
            lastQuery = exchange.requestURI.rawQuery
            respond(exchange, 200, "application/json", """{"err_no":500,"err_msg":"bad token"}""")
        }
        server.createContext("/empty") { exchange ->
            respond(exchange, 200, "audio/wav", "")
        }
        server.createContext("/slow") { exchange ->
            // 先回响应头，再慢慢吐字节：验证"阻塞读取"在取消时能被关闭流打断。
            exchange.responseHeaders.add("Content-Type", "audio/wav")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { body ->
                body.write(wavHeader())
                body.flush()
                repeat(30) { index ->
                    Thread.sleep(100)
                    body.write(ByteArray(16) { index.toByte() })
                    body.flush()
                }
            }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "mock-tts").apply { isDaemon = true }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        cacheDir.deleteRecursively()
    }

    private fun handleTts(exchange: HttpExchange) {
        requestCount.incrementAndGet()
        lastQuery = exchange.requestURI.rawQuery
        val bytes = ByteArray(64) + wavHeader()
        respond(exchange, 200, "audio/wav", bytes)
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: String) {
        respond(exchange, code, contentType, body.toByteArray(Charsets.UTF_8))
    }

    private fun respond(exchange: HttpExchange, code: Int, contentType: String, body: ByteArray) {
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun wavHeader(): ByteArray {
        val dataSize = 0
        return java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataSize)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(16_000)
            putInt(16_000 * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(dataSize)
        }.array()
    }

    private fun rule(url: String) = HttpTTS(
        id = 1L,
        name = "mock",
        url = url,
        concurrentRate = "0",
    )

    private fun synthesizer() = NetworkTtsSynthesizer(
        cache = NetworkTtsAudioCache(cacheDir),
        maxRetries = 0,
        jsTimeoutMs = 2_000,
        requestTimeoutMs = 5_000,
    )

    @Test
    fun `synthesize writes audio and encodes text via js engine`() = runBlocking {
        val synthesizer = synthesizer()
        val result = synthesizer.synthesize(
            rule = rule("$baseUrl/tts?text={{java.encodeURI(speakText)}}&speed=5"),
            text = "你好，世界",
            speechRate = 5,
        )
        assertFalse(result.fromCache)
        assertTrue(result.file.length() > 44)
        // speakText 经 java.encodeURI 编码后进入 query
        assertTrue(lastQuery.orEmpty().contains("%E4%BD%A0%E5%A5%BD"), "实际 query=$lastQuery")
    }

    @Test
    fun `json response fails without silent fallback`() {
        val synthesizer = synthesizer()
        val error = assertThrows<NetworkTtsSynthesizer.SynthesisException> {
            runBlocking {
                synthesizer.synthesize(rule("$baseUrl/error?text={{speakText}}"), "测试", 5)
            }
        }
        assertTrue(error.message.orEmpty().contains("TTS 服务器返回错误"), "实际=${error.message}")
        assertTrue(cacheDir.listFiles().orEmpty().none { it.extension == "audio" })
    }

    @Test
    fun `empty audio fails`() {
        val synthesizer = synthesizer()
        assertThrows<NetworkTtsSynthesizer.SynthesisException> {
            runBlocking {
                synthesizer.synthesize(rule("$baseUrl/empty?text={{speakText}}"), "测试", 5)
            }
        }
    }

    @Test
    fun `second call hits cache without extra request`() = runBlocking {
        val synthesizer = synthesizer()
        val url = "$baseUrl/tts?text={{speakText}}"
        val first = synthesizer.synthesize(rule(url), "缓存测试", 5)
        lastQuery = null
        val second = synthesizer.synthesize(rule(url), "缓存测试", 5)
        assertTrue(second.fromCache)
        assertEquals(first.file.absolutePath, second.file.absolutePath)
        assertEquals(null, lastQuery)
    }

    @Test
    fun `concurrent same key synthesis is deduplicated across cache instances`() = runBlocking {
        requestCount.set(0)
        val first = synthesizer()
        val second = NetworkTtsSynthesizer(
            cache = NetworkTtsAudioCache(cacheDir),
            maxRetries = 0,
            jsTimeoutMs = 2_000,
            requestTimeoutMs = 5_000,
        )
        val currentRule = rule("$baseUrl/tts?text={{speakText}}")
        val results = listOf(first, second).map { item ->
            async { item.synthesize(currentRule, "同键去重", 5) }
        }.awaitAll()
        try {
            assertEquals(1, requestCount.get())
            assertEquals(results[0].file.absolutePath, results[1].file.absolutePath)
            assertTrue(results.count { it.fromCache } == 1)
        } finally {
            results.forEach { it.close() }
        }
    }

    @Test
    fun `rule change invalidates cache key`() = runBlocking {
        val synthesizer = synthesizer()
        val first = synthesizer.synthesize(rule("$baseUrl/tts?text={{speakText}}"), "同一文本", 5)
        val changed = rule("$baseUrl/tts?text={{speakText}}&voice=other")
        val second = synthesizer.synthesize(changed, "同一文本", 5)
        assertFalse(second.fromCache)
        assertTrue(first.file.absolutePath != second.file.absolutePath)
    }

    @Test
    @org.junit.jupiter.api.Timeout(30, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `js infinite loop is interrupted by timeout`() {
        val start = System.currentTimeMillis()
        val thrown = runCatching {
            runBlocking {
                synthesizer().synthesize(
                    rule("@js:while(true){}"),
                    "测试",
                    5,
                )
            }
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - start
        assertTrue(thrown is NetworkTtsSynthesizer.SynthesisException, "实际异常：$thrown")
        assertTrue(thrown?.message.orEmpty().contains("超时"), "实际异常：$thrown")
        assertTrue(elapsed < 10_000, "超时未生效，耗时 ${elapsed}ms")
    }

    @Test
    fun `unsupported webview option is reported explicitly`() {
        val synthesizer = synthesizer()
        val error = assertThrows<NetworkTtsSynthesizer.SynthesisException> {
            runBlocking {
                synthesizer.synthesize(
                    rule("$baseUrl/tts,{\"webView\":true}"),
                    "测试",
                    5,
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("暂不支持"), "实际=${error.message}")
    }

    @Test
    @org.junit.jupiter.api.Timeout(20, unit = java.util.concurrent.TimeUnit.SECONDS)
    fun `cancelling during blocking download stops quickly and leaves no tmp file`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        val job = scope.launch {
            synthesizer().synthesize(
                rule("$baseUrl/slow?text={{speakText}}"),
                "慢速下载取消",
                5,
            )
        }
        Thread.sleep(600)
        val start = System.currentTimeMillis()
        job.cancel()
        runBlocking { job.join() }
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 5_000, "取消耗时 ${elapsed}ms，阻塞读取未被中断")
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertTrue(cacheDir.listFiles().orEmpty().none { it.extension == "audio" })
    }
}
