package com.yuezhou.reader.core.online

import com.yuezhou.reader.core.online.api.ZLibraryApi
import com.yuezhou.reader.core.online.config.ZLibraryConfig
import com.yuezhou.reader.core.online.model.BookFormatState
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
        val request = api().buildSearchRequest(
            SearchQuery(keyword = "kotlin", pageSize = 30, language = "chinese", sort = "bestmatch"),
        )
        assertEquals("POST", request.method)
        assertEquals("/eapi/book/search", request.url.encodedPath)
        val form = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(form.contains("message=kotlin"))
        assertTrue(form.contains("limit=30"))
        assertTrue(form.contains("languages%5B0%5D=chinese") || form.contains("languages[0]=chinese"))
        assertTrue(form.contains("order=bestmatch"))
        assertTrue(form.contains("extensions%5B0%5D=EPUB") || form.contains("extensions[0]=EPUB"))
        // 文章模式会忽略格式过滤；第一版只检索 EPUB 图书。
        assertTrue(!form.contains("content_type"))
    }

    @Test
    fun `封面只接受HTTPS图片且拒绝非图片响应`() = runBlocking {
        val html = api(body = "<html>error</html>").fetchCover("https://cover.example/a.jpg")
        assertTrue(html.isFailure)

        val redirected = api(
            code = 302,
            headers = mapOf("Location" to "http://evil.example/a.jpg"),
        ).fetchCover("https://cover.example/a.jpg")
        assertTrue(redirected.isFailure)

        val http = api().fetchCover("http://cover.example/a.jpg")
        assertTrue(http.isFailure)
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
        assertEquals(ProviderErrorCode.SERVER_REJECTED, api.classify(400, "拒绝访问").code)
        assertEquals(ProviderErrorCode.SERVER_REJECTED, api.classify(400, "Access denied").code)
    }

    @Test
    fun `服务端拒绝搜索时归类为服务端拒绝而非认证失败`() = runBlocking {
        val error = api(
            code = 400,
            body = """{"success":0,"error":"拒绝访问"}""",
        ).search(SearchQuery(keyword = "kotlin")).exceptionOrNull()
        assertTrue(error is ProviderException)
        assertEquals(ProviderErrorCode.SERVER_REJECTED, (error as ProviderException).code)
    }

    @Test
    fun `热门列表降级返回书籍并附带提示`() = runBlocking {
        val body = """{"success":1,"books":[{"id":"9","hash":"h","title":"热门书","format":"epub"}]}"""
        val page = api(body = body).getMostPopular(pageSize = 30).getOrThrow()
        assertEquals(1, page.items.size)
        assertEquals("热门书.epub", page.items.first().fileName)
        assertTrue(!page.hasMore)
        assertTrue(page.notice!!.contains("最热门"))
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

    @Test
    fun `详情优先读取 extension 字段`() = runBlocking {
        val body = """{"success":1,"book":{"id":"1","hash":"h","title":"书","extension":".EPUB"}}"""
        val book = api(body = body).getBookDetail("1", "h").getOrThrow()
        assertEquals("epub", book.format)
        assertEquals(BookFormatState.CONFIRMED, book.formatState)
        assertEquals("书.epub", book.fileName)
        assertEquals("EPUB", book.formatLabel)
    }

    @Test
    fun `format 与 extension 缺失时不伪造 EPUB`() = runBlocking {
        val body = """{"success":1,"book":{"id":"1","hash":"h","title":"书"}}"""
        val book = api(body = body).getBookDetail("1", "h").getOrThrow()
        assertEquals("", book.format)
        assertEquals(BookFormatState.MISSING, book.formatState)
        assertEquals("书", book.fileName)
        assertEquals("格式未知", book.formatLabel)
    }

    @Test
    fun `format 与 extension 冲突时标记冲突`() = runBlocking {
        val body = """{"success":1,"book":{"id":"1","hash":"h","title":"书","format":"epub","extension":"pdf"}}"""
        val book = api(body = body).getBookDetail("1", "h").getOrThrow()
        assertEquals("", book.format)
        assertEquals(BookFormatState.CONFLICT, book.formatState)
        assertEquals("格式冲突", book.formatLabel)
    }

    @Test
    fun `非 EPUB 格式标记为暂不支持`() = runBlocking {
        val body = """{"success":1,"book":{"id":"1","hash":"h","title":"书","format":"PDF"}}"""
        val book = api(body = body).getBookDetail("1", "h").getOrThrow()
        assertEquals("pdf", book.format)
        assertEquals(BookFormatState.UNSUPPORTED, book.formatState)
        assertEquals("书.pdf", book.fileName)
        assertEquals("PDF · 暂不支持", book.formatLabel)
    }

    @Test
    fun `HTTP 200 的业务失败仍按错误分类`() = runBlocking {
        val searchError = api(body = """{"success":0,"error":"Access denied"}""")
            .search(SearchQuery(keyword = "kotlin")).exceptionOrNull()
        assertTrue(searchError is ProviderException)
        assertEquals(ProviderErrorCode.SERVER_REJECTED, (searchError as ProviderException).code)

        val quotaError = api(body = """{"success":0,"error":"daily download quota reached"}""")
            .getQuota(ZLibraryApi.AuthSession("remix_userid=1")).exceptionOrNull()
        assertTrue(quotaError is ProviderException)
        assertEquals(ProviderErrorCode.QUOTA, (quotaError as ProviderException).code)

        val detailError = api(body = """{"success":0,"message":"book not found"}""")
            .getBookDetail("1", "h").exceptionOrNull()
        assertTrue(detailError is ProviderException)
    }
}
