package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.api.ZLibraryApi
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.model.ProviderErrorCode
import com.yuezhou.reader.core.online.model.ProviderException
import com.yuezhou.reader.core.online.model.SearchQuery
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ZLibraryApiTest {
    private val config = ZLibraryConfig.DEFAULT.copy(
        servers = ZLibraryConfig.DEFAULT.servers.copy(baseUrls = listOf("https://trusted.example")),
    )

    private fun api(
        body: String = "{}",
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
        onRequest: (Request) -> Unit = {},
    ): ZLibraryApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            onRequest(chain.request())
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(ZLibraryApi.fakeBody(body))
            headers.forEach { (key, value) -> builder.header(key, value) }
            builder.build()
        }.build()
        return ZLibraryApi(config, client)
    }

    @Test
    fun `搜索端点与表单符合参考实现`() {
        val request = api().buildSearchRequest(SearchQuery(keyword = "kotlin", pageSize = 30))
        assertEquals("POST", request.method)
        assertEquals("/eapi/book/search", request.url.encodedPath)
        val form = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(form.contains("message=kotlin"))
        assertTrue(form.contains("limit=30"))
        assertTrue(form.contains("order%5B1%5D=relevance") || form.contains("order[1]=relevance"))
        assertTrue(form.contains("extensions%5B0%5D=epub") || form.contains("extensions[0]=epub"))
        assertTrue(form.contains("content_type=article"))
    }

    @Test
    fun `登录成功解析Cookie`() = runBlocking {
        val result = api(
            body = """{"success":1,"user_id":"42"}""",
            headers = mapOf("Set-Cookie" to "remix_userid=42; Path=/"),
        ).login("user@example.com", "secret")
        val session = result.getOrThrow()
        assertTrue(session.cookies.contains("remix_userid=42"))
        assertEquals("42", session.userId)
    }

    @Test
    fun `凭证拒绝才回退备用登录端点`() = runBlocking {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen.add(chain.request().url.encodedPath)
            val body = if (chain.request().url.encodedPath == "/rpc.php") {
                """{"success":0,"message":"invalid credentials"}"""
            } else {
                """{"success":1}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(ZLibraryApi.fakeBody(body)).build()
        }.build()
        val result = ZLibraryApi(config, client).login("u", "p")
        assertTrue(result.isSuccess)
        assertEquals(listOf("/rpc.php", "/eapi/user/login"), seen)
    }

    @Test
    fun `限流错误不回退且归类为限流`() = runBlocking {
        val calls = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.add(chain.request().url.encodedPath)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("test").body(ZLibraryApi.fakeBody("""{"message":"too many requests"}"""))
                .build()
        }.build()
        val error = runCatching { ZLibraryApi(config, client).login("u", "p").getOrThrow() }.exceptionOrNull()
        assertTrue(error is ProviderException)
        assertEquals(ProviderErrorCode.RATE_LIMITED, (error as ProviderException).code)
        assertEquals(1, calls.size)
    }

    @Test
    fun `拒绝重定向到不可信主机`() {
        val response = Response.Builder()
            .request(Request.Builder().url("https://trusted.example/").build())
            .protocol(Protocol.HTTP_1_1).code(302).message("test")
            .header("Location", "http://evil.example/steal")
            .body(ZLibraryApi.fakeBody(""))
            .build()
        val api = api()
        val error = assertThrows<ProviderException> { api.verifyRedirect(response) }
        assertEquals(ProviderErrorCode.NETWORK, error.code)
    }

    @Test
    fun `错误分类区分认证额度与限流`() {
        val api = api()
        assertEquals(ProviderErrorCode.AUTH, api.classify(403, "").code)
        assertEquals(ProviderErrorCode.RATE_LIMITED, api.classify(429, "").code)
        assertEquals(ProviderErrorCode.QUOTA, api.classify(200, "daily download quota reached").code)
    }

    @Test
    fun `搜索结果映射为OnlineBook`() = runBlocking {
        val body = """{"success":1,"books":[{"id":"123","hash":"abc","title":"Kotlin 书","author":["A","B"],"format":"epub","year":"2024","filesize":"1024","language":"zh","identifier":"isbn1","cover":"https://cover.example/a.jpg"}]}"""
        val page = api(body = body).search(SearchQuery(keyword = "kotlin")).getOrThrow()
        assertEquals(1, page.items.size)
        val book = page.items.first()
        assertEquals("123:abc", book.remoteId)
        assertEquals("Kotlin 书.epub", book.fileName)
        assertEquals(listOf("A", "B"), book.authors)
        assertEquals(2024, book.publishYear)
        assertEquals(1024L, book.fileSize)
    }

    @Test
    fun `额度查询读取嵌套字段`() = runBlocking {
        val body = """{"success":1,"data":{"downloads_today":3,"downloads_limit":10}}"""
        val quota = api(body = body).getQuota(ZLibraryApi.AuthSession("remix_userid=1")).getOrThrow()
        assertEquals(3, quota.downloadsToday)
        assertEquals(10, quota.downloadsLimit)
    }
}
