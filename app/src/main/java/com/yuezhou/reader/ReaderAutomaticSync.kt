package com.yuezhou.reader

import com.yuezhou.reader.core.protocol.CloudProgress
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

internal class ReaderAutomaticSync(
    private val fetch: suspend (bookId: String) -> CloudProgress?,
) {
    sealed interface PullResult {
        data object NoChange : PullResult
        data object Applied : PullResult
        data class Offer(val remote: CloudProgress, val cfi: String?, val reason: Reason) : PullResult
    }

    enum class Reason { LOCAL_MOVED, REMOTE_BEHIND, CONFIRM_POSITION }

    suspend fun pull(
        bookId: String,
        localCfi: String,
        isActive: () -> Boolean,
        hasMoved: () -> Boolean,
        acceptsAutomaticJump: () -> Boolean,
        resolve: suspend (String) -> String?,
        compare: suspend (remote: String, local: String) -> Int?,
        apply: suspend (cfi: String, remote: CloudProgress) -> Boolean,
    ): PullResult {
        val remote = fetch(bookId) ?: return PullResult.NoChange
        if (!isActive()) return PullResult.NoChange
        val cfi = resolve(remote.xpointer)
        val order = cfi?.let { compare(it, localCfi) }
        if (!isActive()) return PullResult.NoChange
        // 云端与本机一致：没有可应用的内容，即使比较期间又翻页也不打扰用户。
        if (order == 0) return PullResult.NoChange
        val moved = hasMoved()
        if (!moved && acceptsAutomaticJump() && order == 1 && cfi != null && apply(cfi, remote)) {
            return PullResult.Applied
        }
        if (!isActive()) return PullResult.NoChange
        val reason = when {
            moved || hasMoved() -> Reason.LOCAL_MOVED
            order == -1 -> Reason.REMOTE_BEHIND
            else -> Reason.CONFIRM_POSITION
        }
        return PullResult.Offer(remote, cfi, reason)
    }

    suspend fun uploadPosition(
        position: Pair<String, Double?>,
        conversion: Deferred<String?>,
        canUpload: () -> Boolean,
    ): Pair<String, Double?>? = withTimeoutOrNull(750) {
        conversion.join()
        if (conversion.isCancelled || !canUpload()) return@withTimeoutOrNull null
        conversion.await()?.let { it to position.second }
    }
}
