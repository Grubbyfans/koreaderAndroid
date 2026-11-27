package com.yuezhou.reader

import com.yuezhou.reader.ReaderAutomaticSync.PullResult
import com.yuezhou.reader.ReaderAutomaticSync.Reason
import com.yuezhou.reader.core.protocol.CloudProgress
import com.yuezhou.reader.core.protocol.ProgressRecords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderAutomaticSyncTest {
    private val remote = CloudProgress(
        bookId = "Example",
        title = "Example",
        size = 99,
        xpointer = "/body/DocFragment[1]/body/p[4]/text().0",
        percent = 0.32,
        revision = 2,
        writeId = "write",
        deviceId = "device",
        updatedAt = "2026-11-24T00:00:00+00:00",
    )
    private val sync = ReaderAutomaticSync {
        assertEquals("Example", it)
        ProgressRecords.decode(ProgressRecords.encode(remote))
    }

    @Test
    fun `云端锚点和百分比矛盾时用锚点比较并等待跳转校验`() = runBlocking {
        var appliedAnchor: String? = null
        val result = sync.pull(
            bookId = "Example",
            localCfi = "local",
            isActive = { true },
            hasMoved = { false },
            acceptsAutomaticJump = { true },
            resolve = { "remote-cfi" },
            compare = { r, l ->
                assertEquals("remote-cfi", r)
                assertEquals("local", l)
                1
            },
            apply = { cfi, verifiedRemote ->
                appliedAnchor = cfi
                assertEquals(0.32, verifiedRemote.percent)
                true
            },
        )
        assertEquals(PullResult.Applied, result)
        assertEquals("remote-cfi", appliedAnchor)
    }

    @Test
    fun `桥接比较等待期间翻页后保留本机位置`() = runBlocking {
        val comparing = CompletableDeferred<Unit>()
        val order = CompletableDeferred<Int>()
        var moved = false
        val pull = async(start = CoroutineStart.UNDISPATCHED) {
            sync.pull(
                bookId = "Example",
                localCfi = "local",
                isActive = { true },
                hasMoved = { moved },
                acceptsAutomaticJump = { true },
                resolve = { "remote-cfi" },
                compare = { _, _ -> comparing.complete(Unit); order.await() },
                apply = { _, _ -> error("late result must not jump") },
            )
        }
        comparing.await()
        moved = true
        order.complete(1)
        assertEquals(Reason.LOCAL_MOVED, (pull.await() as PullResult.Offer).reason)
    }

    @Test
    fun `位置一致时即使等待期间翻页也不打扰`() = runBlocking {
        var moved = false
        val result = sync.pull(
            bookId = "Example",
            localCfi = "local",
            isActive = { true },
            hasMoved = { moved },
            acceptsAutomaticJump = { true },
            resolve = { "remote-cfi" },
            compare = { _, _ ->
                // 模拟比较期间 WebView 又发出一次定位
                moved = true
                0
            },
            apply = { _, _ -> error("identical position must not jump") },
        )
        assertEquals(PullResult.NoChange, result)
    }

    @Test
    fun `云端更早或目标校验失败时保留远端信息供手动选择`() = runBlocking {
        for ((order, expected) in listOf(-1 to Reason.REMOTE_BEHIND, 1 to Reason.CONFIRM_POSITION)) {
            val decision = sync.pull(
                bookId = "Example",
                localCfi = "local",
                isActive = { true },
                hasMoved = { false },
                acceptsAutomaticJump = { true },
                resolve = { "remote-cfi" },
                compare = { _, _ -> order },
                apply = { _, _ -> if (order == 1) false else error("older progress must ask") },
            ) as PullResult.Offer
            assertEquals(expected, decision.reason)
            assertEquals("remote-cfi", decision.cfi)
            assertEquals(remote.xpointer, decision.remote.xpointer)
        }
    }

    @Test
    fun `上传只使用仍对应当前位置的转换结果`() = runBlocking {
        val conversion = CompletableDeferred<String?>()
        var samePosition = true
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            sync.uploadPosition("original" to 0.48, conversion) { samePosition }
        }
        samePosition = false
        conversion.complete(remote.xpointer)
        assertNull(pending.await())
        assertEquals(remote.xpointer to 0.48, sync.uploadPosition("original" to 0.48, conversion) { true })
    }
}
