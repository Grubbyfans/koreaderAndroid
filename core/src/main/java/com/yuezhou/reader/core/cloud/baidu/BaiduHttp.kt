package com.yuezhou.reader.core.cloud.baidu

import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode
import com.yuezhou.reader.core.cloud.Outcome
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 百度接口请求封装，对齐 BaiduPanSync `modules/baidu_http.lua`。
 *
 * - 所有请求自动附带 `access_token`。
 * - 默认 `User-Agent: pan.baidu.com`、`Content-Type: application/x-www-form-urlencoded`。
 * - 响应须为 HTTP 200 + 可解析 JSON；`errno`/`error_code`/`error` 非 0 即失败。
 * - 写类失败（PUT/POST 语义）标 [Outcome.Uncertain]。
 */
internal class BaiduHttp(
    private val client: OkHttpClient,
    private val accessToken: String,
) {

    /** 发起请求并返回解析后的 JSON 对象。 */
    fun request(
        endpoint: String,
        method: String = "GET",
        params: Map<String, String> = emptyMap(),
        body: String? = null,
        writing: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val fullParams = params + ("access_token" to accessToken)
        val query = BaiduForm.form(fullParams)
        val url = "$endpoint?$query"

        val bodyObj: RequestBody? = if (body != null) {
            RequestBody.create(FORM_MEDIA_TYPE, body)
        } else if (method == "POST") {
            RequestBody.create(FORM_MEDIA_TYPE, "")
        } else null

        val builder = Request.Builder().url(url).method(method, bodyObj)
        builder.header("User-Agent", "pan.baidu.com")
        builder.header("Content-Type", "application/x-www-form-urlencoded")
        for ((k, v) in headers) builder.header(k, v)

        val response = client.newCall(builder.build()).execute()
        response.use { r ->
            val raw = r.body?.string()
                ?: throw CloudException(ErrorCode.NETWORK, "百度请求无响应", if (writing) Outcome.Uncertain else Outcome.Failed)
            if (r.code != 200) {
                throw CloudException(
                    ErrorCode.NETWORK,
                    "百度 HTTP 请求失败：${r.code}",
                    if (writing) Outcome.Uncertain else Outcome.Failed,
                )
            }
            val obj = BaiduJson.parse(raw)
            BaiduErrors.checkError(obj)
            return obj
        }
    }

    companion object {
        private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
