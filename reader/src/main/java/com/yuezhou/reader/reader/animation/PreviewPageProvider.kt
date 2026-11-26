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
        preparation?.cancel(); preparation = null
        revision++
        current = null; adjacent.clear(); pool.close()
        preview?.let(configure)
    }

    fun prepare(cfi: String) {
        val helper = inspector ?: return
        val ready = opened ?: return
        val stamp = ++generation
        preparation?.cancel()
        current = null; adjacent.clear()
        preparation = scope.launch {
            try {
                withTimeout(10000) {
                    ready.await()
                    if (primary.width <= 0 || primary.height <= 0) return@withTimeout
                    val main = WebPageSnapshots(primary)
                    try {
                        val state = main.state()
                        if (state.cfi != cfi || stamp != generation) return@withTimeout
                        pool.close()
                        val origin = key(cfi)
                        // Keep software drawing off the visible WebView: it can blank its window output.
                        // The preview must match the primary page before it supplies the origin image.
                        val alignedOrigin = helper.locate(cfi)
                        if (alignedOrigin.text != state.text) error("Preview pagination differs from current page")
                        val bitmap = helper.capture()
                        if (stamp != generation) { bitmap.recycle(); return@withTimeout }
                        pool.put(origin, bitmap)
                        current = origin
                        // The next page is ready before work on the previous page begins.
                        for (direction in listOf(1, -1)) {
                            val aligned = helper.locate(cfi)
                            if (aligned.text != state.text) error("Preview pagination differs from current page")
                            if ((direction > 0 && aligned.atEnd) || (direction < 0 && aligned.atStart)) continue
                            val target = helper.adjacent(direction)
                            if (target.cfi == cfi) continue
                            val image = helper.capture()
                            if (stamp != generation) { image.recycle(); return@withTimeout }
                            val targetKey = key(target.cfi)
                            pool.put(targetKey, image)
                            adjacent[direction] = targetKey
                        }
                    } finally { main.close() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OutOfMemoryError) {
                if (stamp == generation) { current = null; adjacent.clear(); pool.close(); onFailure(error) }
            } catch (error: Exception) {
                if (stamp == generation) onFailure(error)
            }
        }
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
        preparation?.cancel(); preparation = null
        opened?.cancel(); opened = null
        inspector?.close(); inspector = null
        preview?.let { parent.removeView(it); it.release() }; preview = null
        current = null; adjacent.clear()
        pool.close()
    }
}
