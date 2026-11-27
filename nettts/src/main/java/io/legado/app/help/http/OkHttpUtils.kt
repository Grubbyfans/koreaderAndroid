package io.legado.app.help.http

import io.legado.app.utils.GSON
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp 请求构造辅助。
 *
 * 来源：Legado 原版 `app/src/main/java/io/legado/app/help/http/OkHttpUtils.kt`（GPLv3）。
 * 裁剪：去掉 zip 解压响应、BOM/编码探测（静读侧解析文本响应的场景有限，先按 Content-Type 字符集解码）。
 */

suspend fun OkHttpClient.newCallResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): Response {
    val requestBuilder = Request.Builder()
    requestBuilder.apply(builder)
    var retriesRemaining = retry.coerceAtLeast(0)
    while (true) {
        val response = newCall(requestBuilder.build()).await()
        if (response.isSuccessful || response.code in 300..399 || retriesRemaining == 0) {
            return response
        }
        response.close()
        retriesRemaining--
    }
}

suspend fun OkHttpClient.newCallResponseBody(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): ResponseBody = newCallResponse(retry, builder).body

suspend fun OkHttpClient.newCallStrResponse(
    retry: Int = 0,
    builder: Request.Builder.() -> Unit
): StrResponse = newCallResponse(retry, builder).let {
    StrResponse(it, it.body.text())
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { block ->
    block.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            block.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            block.resume(response)
        }
    })
}

fun ResponseBody.text(encode: String? = null): String {
    val bytes = bytes()
    val charset = when {
        !encode.isNullOrEmpty() -> Charset.forName(encode)
        else -> contentType()?.charset() ?: Charsets.UTF_8
    }
    return String(bytes, charset)
}

fun Request.Builder.addHeaders(headers: Map<String, String>) {
    headers.forEach { addHeader(it.key, it.value) }
}

fun Request.Builder.get(url: String, queryMap: Map<String, String>, encoded: Boolean = false) {
    val httpBuilder = url.toHttpUrl().newBuilder()
    queryMap.forEach {
        if (encoded) {
            httpBuilder.addEncodedQueryParameter(it.key, it.value)
        } else {
            httpBuilder.addQueryParameter(it.key, it.value)
        }
    }
    url(httpBuilder.build())
}

fun Request.Builder.get(url: String, encodedQuery: String?) {
    val httpBuilder = url.toHttpUrl().newBuilder()
    httpBuilder.encodedQuery(encodedQuery)
    url(httpBuilder.build())
}

private val formContentType = "application/x-www-form-urlencoded".toMediaType()

fun Request.Builder.postForm(encodedForm: String) {
    post(encodedForm.toRequestBody(formContentType))
}

@Suppress("unused")
fun Request.Builder.postForm(form: Map<String, String>, encoded: Boolean = false) {
    val formBody = FormBody.Builder()
    form.forEach {
        if (encoded) formBody.addEncoded(it.key, it.value) else formBody.add(it.key, it.value)
    }
    post(formBody.build())
}

fun Request.Builder.postMultipart(type: String?, form: Map<String, Any>) {
    val multipartBody = MultipartBody.Builder()
    type?.let { multipartBody.setType(it.toMediaType()) }
    form.forEach {
        when (val value = it.value) {
            is Map<*, *> -> {
                val fileName = value["fileName"] as String
                val file = value["file"]
                val mediaType = (value["contentType"] as? String)?.toMediaType()
                val requestBody = when (file) {
                    is File -> file.asRequestBody(mediaType)
                    is ByteArray -> file.toRequestBody(mediaType)
                    is String -> file.toRequestBody(mediaType)
                    else -> GSON.toJson(file).toRequestBody(mediaType)
                }
                multipartBody.addFormDataPart(it.key, fileName, requestBody)
            }

            else -> multipartBody.addFormDataPart(it.key, it.value.toString())
        }
    }
    post(multipartBody.build())
}

fun Request.Builder.postJson(json: String?) {
    json?.let {
        post(it.toRequestBody("application/json; charset=UTF-8".toMediaType()))
    }
}
