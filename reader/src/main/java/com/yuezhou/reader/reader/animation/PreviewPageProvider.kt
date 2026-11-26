package com.yuezhou.reader.reader.animation

import android.net.Uri
import android.widget.FrameLayout
import com.yuezhou.reader.reader.ReaderEvent
import com.yuezhou.reader.reader.ReaderWebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** One attached preview, three bounded snapshots, no progress/TTS/sync callbacks. All calls are on main. */
class PreviewPageProvider(
    private val parent: FrameLayout,
    private val primary: ReaderWebView,
    private val scope: CoroutineScope,
    private val configure: (ReaderWebView) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val onPrepared: () -> Unit = {},
) : AutoCloseable {
    data class Pair(val origin: PageSnapshotKey, val target: PageSnapshotKey)
    private val pool = PageSnapshotPool()
    private var preview: ReaderWebView? = null
    private var inspector: WebPageSnapshots? = null
    private var preparation: Job? = null
    private var session = 0L
    private var revision = 0L
    private var generation = 0L
    private var opened: CompletableDeferred<Unit>? = null
    private var current: PageSnapshotKey? = null
    private val adjacent = mutableMapOf<Int, PageSnapshotKey>()
    private val states = mutableMapOf<PageSnapshotKey, WebPageSnapshots.State>()
    private val neighbors = mutableMapOf<PageSnapshotKey, MutableMap<Int, PageSnapshotKey>>()
    private var preparedOrigin: PageSnapshotKey? = null
    var preferredDirection = 1
    var isPreparing = false
        private set

    fun open(fileName: String, uri: Uri?) {
        close()
        session++
        val ready = CompletableDeferred<Unit>()
        opened = ready
        val view = ReaderWebView(parent.context)
        preview = view
        view.eventListener = { event ->
            when (event) {
                is ReaderEvent.BookOpened -> ready.complete(Unit)
                is ReaderEvent.Error -> ready.completeExceptionally(IllegalStateException(event.message))
                else -> Unit
            }
        }
        parent.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        configure(view)
        inspector = WebPageSnapshots(view)
        view.openEpub(fileName, source = uri)
    }

    /** Cancels queued preparation before discarding snapshots borrowed by an idle animation layer. */
    fun invalidateLayout() {
        generation++
        isPreparing = false
        preparation?.cancel(); preparation = null
        revision++
        clearSnapshots()
        preview?.let(configure)
    }

    fun prepare(cfi: String) = prepare(cfi, ahead = false)

    /** The destination is already a verified snapshot; warm its neighbor during the visual. */
    fun prepareAhead(cfi: String) {
        if (pool.get(key(cfi)) != null) prepare(cfi, ahead = true)
    }

    private fun prepare(cfi: String, ahead: Boolean) {
        val helper = inspector ?: return
        val ready = opened ?: return
        val origin = key(cfi)
        if (current == origin && (isPreparing || preparedOrigin == origin)) return
        val timing = PageTurnTiming(if (ahead) "prefetch" else "prepare")
        timing.mark("start")
        val stamp = ++generation
        preparation?.cancel()
        current = origin.takeIf { pool.get(it) != null }
        adjacent.clear()
        neighbors[origin]?.forEach { (direction, target) ->
            if (pool.contains(target)) adjacent[direction] = target
        }
        preparedOrigin = null
        isPreparing = true
        preparation = scope.launch {
            var completed = false
            try {
                withTimeout(10000) {
                    ready.await()
                    if (primary.width <= 0 || primary.height <= 0) return@withTimeout
                    val state = if (ahead) states[origin] ?: return@withTimeout else {
                        val main = WebPageSnapshots(primary)
                        try { main.state() } finally { main.close() }
                    }
                    if (state.cfi != cfi || stamp != generation) return@withTimeout
                    if (pool.get(origin) == null) {
                        val aligned = helper.locate(cfi)
                        if (aligned.text != state.text) error("Preview pagination differs from current page")
                        val image = helper.capture()
                        if (stamp != generation) { image.recycle(); return@withTimeout }
                        pool.put(origin, image)
                    }
                    timing.mark("origin_ready")
                    states[origin] = state
                    current = origin
                    onPrepared()
                    if (stamp != generation) return@withTimeout
                    // Reuse the old current page for reversal; only capture the newly exposed edge.
                    for (direction in listOf(preferredDirection, -preferredDirection)) {
                        if (adjacent[direction]?.let(pool::contains) == true) continue
                        val known = neighbors[origin]?.get(direction)
                        val target = if (known != null) helper.locate(known.cfi) else {
                            val aligned = helper.locate(cfi)
                            if (aligned.text != state.text) error("Preview pagination differs from current page")
                            if ((direction > 0 && aligned.atEnd) || (direction < 0 && aligned.atStart)) continue
                            helper.adjacent(direction)
                        }
                        if (target.cfi == cfi) continue
                        timing.mark("neighbor_navigation_done")
                        val image = helper.capture()
                        timing.mark("neighbor_capture_done")
                        if (stamp != generation) { image.recycle(); return@withTimeout }
                        val targetKey = key(target.cfi)
                        pool.put(targetKey, image)
                        states[targetKey] = target
                        neighbors.getOrPut(origin) { mutableMapOf() }[direction] = targetKey
                        neighbors.getOrPut(targetKey) { mutableMapOf() }[-direction] = origin
                        adjacent[direction] = targetKey
                        onPrepared() // Wake the requested direction as soon as its page is ready.
                        if (stamp != generation) return@withTimeout
                    }
                    completed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OutOfMemoryError) {
                if (stamp == generation) { clearSnapshots(); onFailure(error) }
            } catch (error: Exception) {
                if (stamp == generation) onFailure(error)
            } finally {
                if (stamp == generation) {
                    states.keys.retainAll { pool.contains(it) }
                    neighbors.keys.retainAll { pool.contains(it) }
                    timing.mark(if (completed) "done" else "cancelled_or_failed")
                    if (completed) preparedOrigin = origin
                    isPreparing = false
                    onPrepared()
                }
            }
        }
    }

    private fun clearSnapshots() {
        current = null; adjacent.clear(); pool.close()
        states.clear(); neighbors.clear(); preparedOrigin = null
    }

    private fun key(cfi: String) = PageSnapshotKey(session, revision, cfi, primary.width, primary.height)

    fun ready(cfi: String, direction: Int): Pair? {
        val origin = current?.takeIf { it == key(cfi) } ?: return null
        val target = adjacent[direction] ?: return null
        if (pool.get(origin) == null || pool.get(target) == null) return null
        return Pair(origin, target)
    }

    fun bitmap(key: PageSnapshotKey) = pool.get(key)

    override fun close() {
        generation++
        isPreparing = false
        preparation?.cancel(); preparation = null
        opened?.cancel(); opened = null
        inspector?.close(); inspector = null
        preview?.let { parent.removeView(it); it.release() }; preview = null
        clearSnapshots()
    }
}
