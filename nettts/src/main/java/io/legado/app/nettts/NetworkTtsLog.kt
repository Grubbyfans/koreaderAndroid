package io.legado.app.nettts

import android.util.Log

/**
 * 网络 TTS 日志出口。
 *
 * 原版使用 `AppLog.put`（落库 + 通知栏）；静读侧改为可替换的轻量出口，
 * 默认写 Logcat，app 层可接管以便在诊断面板展示。
 * 输出前统一脱敏（见 [sanitize]），避免把登录凭证写进日志。
 */
object NetworkTtsLog {

    private const val TAG = "NetworkTts"

    var sink: ((message: String, throwable: Throwable?) -> Unit)? = null

    fun put(message: String, throwable: Throwable? = null) {
        val safe = buildString {
            append(sanitize(message))
            if (throwable != null) {
                val detail = sanitize(throwable.localizedMessage ?: "无错误详情")
                append("（").append(throwable.javaClass.simpleName).append("：").append(detail).append("）")
            }
        }
        val current = sink
        // 不把原始 Throwable 交给 Logcat：异常消息和 cause 链可能包含 URL、Cookie 或 Token。
        if (current != null) {
            current(safe, null)
        } else {
            if (throwable != null) Log.w(TAG, safe) else Log.i(TAG, safe)
        }
    }

    /** 脱敏：隐藏 JSON、header、URL 中的 cookie/token/签名与 Bearer 凭证。 */
    fun sanitize(message: String): String {
        val quoted = QUOTED_CREDENTIAL_PATTERN.replace(message) {
            "${it.groupValues[1]}${it.groupValues[2]}***${it.groupValues[2]}"
        }
        val masked = CREDENTIAL_PATTERN.replace(quoted) { "${it.groupValues[1]}***" }
        return BEARER_PATTERN.replace(masked, "Bearer ***")
    }

    private val CREDENTIAL_PATTERN = Regex(
        "(?i)([\\\"']?(?:cookie|authorization|token|access_token|refresh_token|password|secret|" +
            "sign|signature|api[_-]?key|access[_-]?key(?:id)?)[\\\"']?\\s*[=:]\\s*)" +
            "([^\\s,;&\\\"'}]+)"
    )

    private val QUOTED_CREDENTIAL_PATTERN = Regex(
        "(?i)([\\\"']?(?:cookie|authorization|token|access_token|refresh_token|password|secret|" +
            "sign|signature|api[_-]?key|access[_-]?key(?:id)?)[\\\"']?\\s*[=:]\\s*)" +
            "([\\\"'])(.*?)\\2"
    )

    private val BEARER_PATTERN = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
}
