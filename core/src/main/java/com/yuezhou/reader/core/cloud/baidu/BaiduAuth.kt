package com.yuezhou.reader.core.cloud.baidu

import com.yuezhou.reader.core.cloud.CloudException
import com.yuezhou.reader.core.cloud.ErrorCode
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * 百度 OAuth 2.0 OOB 授权逻辑，对齐 BaiduPanSync `modules/baidu_auth.lua`。
 *
 * 流程：
 * 1. [authorizationUrl] 生成授权 URL（redirect_uri=oob），App 内打开外部浏览器。
 * 2. 用户授权后浏览器展示 OOB 授权码，用户回填。
 * 3. [exchangeCode] 用授权码换取 access_token，校验 scope 含 netdisk、expires_in 范围。
 *
 * 注意：换 token 不携带 access_token（此时尚未有 token），因此不走 [BaiduHttp]
 * （它强制附加 access_token），这里单独发一次表单请求。
 */
object BaiduAuth {

    private const val AUTH_URL = "https://openapi.baidu.com/oauth/2.0/authorize"
    private const val TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token"
    private val FORM_MEDIA_TYPE: MediaType =
        MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")!!

    /** 授权码合法字符集（仅字母数字下划线点横线），长度 1..1024。 */
    private val CODE_REGEX = Regex("^[A-Za-z0-9_.-]+$")

    /** 换 token 成功结果。 */
    data class TokenResult(
        val accessToken: String,
        val expiresIn: Long,
        val scope: String,
        val refreshToken: String?,
    )

    /** 生成 OOB 授权 URL。 */
    fun authorizationUrl(appKey: String, state: String): String {
        if (appKey.isBlank()) throw CloudException(ErrorCode.INVALID_INPUT, "缺少百度 AppKey")
        val query = BaiduForm.form(
            mapOf(
                "response_type" to "code",
                "client_id" to appKey,
                "redirect_uri" to "oob",
                "scope" to "basic,netdisk",
                "state" to state,
                "display" to "mobile",
            ),
        )
        return "$AUTH_URL?$query"
    }

    /**
     * 用授权码换取 access_token。校验授权码格式与返回的 scope/expires_in。
     * 失败抛 [CloudException]，错误码为 AUTH / INVALID_INPUT / NETWORK。
     */
    fun exchangeCode(
        appKey: String,
        secretKey: String,
        code: String,
        clientFactory: (() -> OkHttpClient)? = null,
    ): TokenResult {
        val trimmed = code.trim()
        if (trimmed.isEmpty() || trimmed.length > 1024 || !CODE_REGEX.matches(trimmed)) {
            throw CloudException(ErrorCode.INVALID_INPUT, "请只粘贴授权码，不要粘贴完整网址")
        }

        val body = BaiduForm.form(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to trimmed,
                "client_id" to appKey,
                "client_secret" to secretKey,
                "redirect_uri" to "oob",
            ),
        )
        val client = clientFactory?.invoke() ?: defaultClient()
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(RequestBody.create(FORM_MEDIA_TYPE, body))
            .header("User-Agent", "pan.baidu.com")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val obj: JsonObject = client.newCall(request).execute().use { r ->
            val raw = r.body?.string()
                ?: throw CloudException(ErrorCode.NETWORK, "百度授权请求无响应")
            if (r.code != 200) {
                throw CloudException(ErrorCode.NETWORK, "百度授权 HTTP 请求失败：${r.code}")
            }
            val parsed = BaiduJson.parse(raw)
            // OAuth 错误通常带 error 字段；errno 型错误也可能出现
            BaiduErrors.checkError(parsed)
            parsed
        }

        val accessToken = BaiduJson.strField(obj, "access_token")
        val expiresIn = BaiduJson.longField(obj, "expires_in")
        val scope = BaiduJson.strField(obj, "scope") ?: ""
        val refreshToken = BaiduJson.strField(obj, "refresh_token")

        val scopeOk = (",$scope".replace(Regex("\\s+"), ",") + ",").contains(",netdisk,")
        if (accessToken.isNullOrEmpty() || accessToken.length > 4096 ||
            expiresIn == null || expiresIn <= 60 || expiresIn > 315360000 ||
            !scopeOk
        ) {
            throw CloudException(ErrorCode.AUTH, "百度未授予有效的网盘访问权限，请检查应用权限后重新授权")
        }

        return TokenResult(
            accessToken = accessToken,
            expiresIn = expiresIn,
            scope = scope,
            refreshToken = refreshToken?.takeIf { it.length <= 4096 },
        )
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}
