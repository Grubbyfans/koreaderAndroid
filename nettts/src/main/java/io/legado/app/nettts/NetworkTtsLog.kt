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
        val safe = sanitize(message)
        val current = sink
        if (current != null) {
            current(safe, throwable)
        } else {
            if (throwable != null) Log.w(TAG, safe, throwable) else Log.i(TAG, safe)
        }
    }

    /** 脱敏：隐藏 cookie/token/签名等敏感字段的值与 Bearer 凭证。 */
    fun sanitize(message: String): String {
        val masked = CREDENTIAL_PATTERN.replace(message) { "${it.groupValues[1]}=***" }
        return BEARER_PATTERN.replace(masked, "Bearer ***")
    }

    private val CREDENTIAL_PATTERN = Regex(
        "(?i)(cookie|authorization|token|access_token|refresh_token|password|secret|sign|api[_-]?key)" +
            "\\s*[=:]\\s*([^\\s,;&\"'}]+)"
    )

    private val BEARER_PATTERN = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{8,}")
}
