package io.legado.app.help.http

/**
 * 字符串响应。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/http/StrResponse.kt`（GPLv3），字段与方法未改动，
 * 供规则 JS（`java.ajax`、`loginCheckJs` 的 result）按原版方式调用。
 */
import androidx.annotation.Keep
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody

@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
class StrResponse {
    var raw: Response
        private set
    var body: String? = null
        private set
    var errorBody: ResponseBody? = null
        private set
    var callTime = 0

    constructor(rawResponse: Response, body: String?) {
        this.raw = rawResponse
        this.body = body
    }

    constructor(url: String, body: String?) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: Exception) {
            Request.Builder().url("http://localhost/").build()
        }
        raw = Response.Builder()
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(request)
            .build()
        this.body = body
    }

    constructor(rawResponse: Response, errorBody: ResponseBody?) {
        this.raw = rawResponse
        this.errorBody = errorBody
    }

    fun putCallTime(callTime: Int) {
        this.callTime = callTime
    }

    fun raw() = raw
    fun callTime() = callTime

    fun url(): String {
        raw.networkResponse?.let {
            return it.request.url.toString()
        }
        return raw.request.url.toString()
    }

    val url: String get() = url()

    fun body() = body

    fun code(): Int = raw.code

    fun message(): String = raw.message

    fun headers(): Headers = raw.headers

    fun isSuccessful(): Boolean = raw.isSuccessful

    fun errorBody(): ResponseBody? = errorBody

    override fun toString(): String = raw.toString()
}
