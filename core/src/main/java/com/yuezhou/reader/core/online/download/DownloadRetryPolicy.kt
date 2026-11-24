package com.yuezhou.reader.core.online.download

import com.yuezhou.reader.core.online.model.ProviderErrorCode

/** 自动重试策略：只对安全可重试的网络错误重试最多两次；额度/认证/限流错误绝不自动重试。 */
object DownloadRetryPolicy {
    const val MAX_AUTOMATIC_RETRIES = 2

    fun shouldRetry(code: ProviderErrorCode?, attempts: Int): Boolean =
        code == ProviderErrorCode.NETWORK && attempts <= MAX_AUTOMATIC_RETRIES
}
