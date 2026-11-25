package com.yuezhou.reader.ui.online

/** 所有使用者在 ViewModel 主线程访问，同一书源的后发请求优先。 */
internal class CloudListRequests {
    private val latest = mutableMapOf<String, Long>()
    private var sequence = 0L

    fun begin(providerId: String): Long {
        val request = ++sequence
        latest[providerId] = request
        return request
    }

    fun isLatest(providerId: String, request: Long): Boolean = latest[providerId] == request
}
