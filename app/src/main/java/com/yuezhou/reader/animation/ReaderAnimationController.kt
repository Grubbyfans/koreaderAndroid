package com.yuezhou.reader.animation

import android.graphics.Color
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.withFrameNanos
import com.yuezhou.reader.reader.animation.PageTurnFrameTime
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import com.yuezhou.reader.reader.ReaderWebView
import com.yuezhou.reader.reader.animation.PageTurnTiming
import com.yuezhou.reader.reader.animation.PageTurnTransaction
import com.yuezhou.reader.reader.animation.PreviewPageProvider
import com.yuezhou.reader.reader.animation.WebPageSnapshots
import com.yuezhou.reader.reader.animation.awaitPageTurnVisual
import io.legado.app.feature.reader.components.ioscurl.IosCurlTurn
import io.legado.app.feature.reader.core.transition.ReaderTurnDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The owning Compose reader supplies its frame clock through scope. */
internal class ReaderAnimationController(
    private val parent: FrameLayout,
    private val primary: ReaderWebView,
    private val scope: CoroutineScope,
    configurePreview: (ReaderWebView) -> Unit,
    private val reportError: (Throwable) -> Unit,
) : AutoCloseable {
    private val overlay = PageTurnOverlay(parent.context)
    private val pages = PreviewPageProvider(parent, primary, scope, configurePreview, reportError, ::drainPendingTurn)
    private val transaction = PageTurnTransaction()
    private val curl = IosCurlTurn()
    private var bookSource: Pair<String, android.net.Uri?>? = null
    private var suspended = false
    private val sizeListener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (oldRight > oldLeft && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)) {
            cancel(); pages.invalidateLayout()
            parent.post { if (!suspended) location?.let(pages::prepare) }
        }
    }
    private var motion: Job? = null
    private var finishVisual: CompletableDeferred<Unit>? = null
    private var publishingCommit = false
    private var touchTiming: PageTurnTiming? = null
    private var pendingTiming: PageTurnTiming? = null
    private var lastTapTime = 0L
    private var continuousTurn = false
    private var renderAvailable = true
    private var pendingFallbackDirection: Int? = null
    private var dragToken: PageTurnTransaction.Token? = null
    private var downX = 0f
    private var downY = 0f
    private var skipGesture = false
    private var swallowGesture = false
    private var deferredDragDirection: Int? = null
    private var pendingTurn: Int? = null
    private var location: String? = null
    var mode: String = "slide"
        private set
    var background: Int = Color.WHITE

    init {
        parent.addOnLayoutChangeListener(sizeListener)
        parent.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.onRenderFailure = { error ->
            val fallback = pendingFallbackDirection.takeIf { transaction.phase == PageTurnTransaction.Phase.SETTLING }
            cancel(); pages.close(); renderAvailable = false; reportError(error)
            if (fallback != null) { if (fallback > 0) primary.nextPage() else primary.prevPage() }
        }
    }

    fun disable() { cancel(); pages.close(); bookSource = null; mode = "slide" }

    fun suspendPreview() { suspended = true; cancel(); pages.close() }
    fun resumePreview() {
        if (!suspended) return
        suspended = false
        bookSource?.let { open(it.first, it.second); location?.let(pages::prepare) }
    }

    fun open(fileName: String, uri: android.net.Uri?) { bookSource = fileName to uri; renderAvailable = true; cancel(); if (!suspended) pages.open(fileName, uri) }

    fun settings(animation: String, layoutChanged: Boolean) {
        if (mode != animation || layoutChanged) {
            cancel()
            mode = animation
            pages.invalidateLayout()
            location?.let(pages::prepare)
        }
    }

    fun relocated(cfi: String) {
        location = cfi
        // A committing turn holds its final frame until the primary WebView is visually ready.
        if (transaction.phase != PageTurnTransaction.Phase.COMMITTING) {
            if (!publishingCommit) cancel(clearPending = false)
            pages.prepare(cfi)
        }
    }

    /** Native modes own navigation even while snapshots are still being prepared. */
    fun tap(direction: Int): Boolean {
        if (mode != "fade" && mode != "ios_curl") return false
        if (!renderAvailable) {
            // The bridge disables slide for native modes, so a renderer failure falls back instantly.
            return false
        }
        val timing = touchTiming ?: PageTurnTiming("tap")
        timing.mark("tap_${transaction.phase}")
        pendingTiming = timing
        touchTiming = null
        val now = android.os.SystemClock.uptimeMillis()
        continuousTurn = lastTapTime != 0L && now - lastTapTime < 300L
        lastTapTime = now
        pages.preferredDirection = direction
        pendingTurn = direction // Bound the backlog to the latest requested direction.
        finishVisual?.complete(Unit)
        drainPendingTurn()
        return true
    }

    private fun drainPendingTurn() {
        if (transaction.phase != PageTurnTransaction.Phase.IDLE) return
        val direction = pendingTurn ?: return
        val cfi = location ?: return
        val pair = pages.ready(cfi, direction)
        if (pair == null) {
            // A completed preparation with no neighbor is a book boundary or preparation failure.
            if (!pages.isPreparing) pendingTurn = null
            return
        }
        pendingTiming?.mark("snapshots_ready")
        val first = pages.bitmap(pair.origin) ?: return
        val second = pages.bitmap(pair.target) ?: return
        val token = transaction.begin(pair.origin, pair.target, direction) ?: return
        pendingTurn = null
        overlay.prepare(first, second, background)
        animate(token, cfi, direction, commit = true, manual = false)
    }

    private fun animate(token: PageTurnTransaction.Token, cfi: String, direction: Int, commit: Boolean,
        manual: Boolean, velocity: Double = 0.0) {
        motion?.cancel()
        pendingFallbackDirection = direction.takeIf { commit }
        transaction.settle(token)
        val finishRequested = CompletableDeferred<Unit>()
        finishVisual = finishRequested
        val timing = if (manual) touchTiming ?: PageTurnTiming("drag") else pendingTiming ?: PageTurnTiming("turn")
        pendingTiming = null
        timing.mark("animation_start")
        if (!manual) overlay.onFirstMotionDraw = { timing.mark("first_motion_draw"); overlay.onFirstMotionDraw = null }
        val continuous = !manual && continuousTurn
        if (commit) pages.prepareAhead(token.target.cfi)
        motion = scope.launch {
            try {
                awaitPageTurnVisual(finishRequested, render = { speed ->
                    val parentClock = coroutineContext[MonotonicFrameClock]!!
                    val timeline = PageTurnFrameTime()
                    val clock = object : MonotonicFrameClock {
                        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
                            parentClock.withFrameNanos { onFrame(timeline.frame(it, speed())) }
                    }
                    withContext(clock) {
                        if (mode == "ios_curl") {
                            if (!manual) curl.begin(if (direction > 0) ReaderTurnDirection.NEXT else ReaderTurnDirection.PREVIOUS,
                                token.origin.width, token.origin.height, parent.resources.displayMetrics.density, this, automatic = true)
                            curl.rendererReady()
                            val settle = launch { curl.settle(commit, velocity) }
                            while (settle.isActive) {
                                withFrameNanos { }
                                curl.config?.let { overlay.curl(it, curl.values) }
                            }
                            settle.join()
                            curl.config?.let { overlay.curl(it, curl.values) }
                        } else {
                            Animatable(if (manual) overlay.fadeProgress else 0f).animateTo(if (commit) 1f else 0f, tween(360, easing = FastOutSlowInEasing)) {
                                overlay.fadeProgress = value
                            }
                        }
                    }
                }, showFinalFrame = {
                    timing.mark("animation_end")
                    overlay.showPage(commit)
                    curl.reset()
                }, continuous = continuous)
                if (finishVisual === finishRequested) finishVisual = null
                if (!commit) {
                    transaction.finish(token); overlay.clear(); curl.reset()
                    pages.prepare(cfi)
                    drainPendingTurn()
                    return@launch
                }
                if (!transaction.commit(token)) return@launch
                // Navigation occurs once, after the visual completion; preview operations never persist progress.
                timing.mark("commit_start")
                primary.commitAnimatedTurn(direction)
                val inspection = WebPageSnapshots(primary)
                try {
                    kotlinx.coroutines.withTimeout(5000) {
                        inspection.committedState()
                        timing.mark("commit_js_done")
                        while (inspection.state().cfi == cfi) kotlinx.coroutines.delay(10)
                        inspection.awaitVisualState()
                        timing.mark("commit_visual_ready")
                    }
                } finally { inspection.close() }
                if (transaction.finish(token)) {
                    overlay.clear(); curl.reset()
                    publishingCommit = true
                    try { primary.finishAnimatedCommit() } finally { publishingCommit = false }
                    timing.mark("turn_done")
                    drainPendingTurn()
                }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                // An internal deadline must release the final frame and the navigation gate.
                cancel(); reportError(timeout)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                cancel(); reportError(error)
            } finally {
                if (finishVisual === finishRequested) finishVisual = null
            }
        }
    }

    fun touch(event: android.view.MotionEvent): Boolean {
        if (!renderAvailable || (mode != "fade" && mode != "ios_curl")) return false
        // Once a DOWN is consumed, keep the rest of that sequence out of the WebView,
        // even if the animation finishes or preview preparation restarts in between.
        if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN && swallowGesture) {
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        val cfi = location ?: return false
        val slop = android.view.ViewConfiguration.get(parent.context).scaledTouchSlop.toFloat()
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchTiming = PageTurnTiming("touch").also { it.mark("down_${transaction.phase}") }
                downX = event.x; downY = event.y; skipGesture = false
                deferredDragDirection = null
                swallowGesture = false
                // Keep this new sequence alive for taps/drags while the previous page commits.
                finishVisual?.complete(Unit)
                curl.down(primary.width, primary.height, parent.resources.displayMetrics.density,
                    event.x, event.y, event.eventTime, pages.ready(cfi, -1) != null, pages.ready(cfi, 1) != null, slop)
                return false
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1) {
                    val owned = dragToken != null || deferredDragDirection != null
                    cancel(); skipGesture = true
                    return owned
                }
                if (deferredDragDirection != null) return true
                if (skipGesture) return false
                val dx = event.x - downX; val dy = event.y - downY
                if (dragToken == null) {
                    if (kotlin.math.abs(dx) < slop && kotlin.math.abs(dy) < slop) return false
                    if (kotlin.math.abs(dy) >= kotlin.math.abs(dx)) { skipGesture = true; return false }
                    val direction = if (dx < 0) 1 else -1
                    pages.preferredDirection = direction
                    val pair = if (transaction.phase == PageTurnTransaction.Phase.IDLE)
                        pages.ready(cfi, direction) else null
                    if (pair == null) {
                        deferredDragDirection = direction
                        val cancelEvent = android.view.MotionEvent.obtain(event)
                        cancelEvent.action = android.view.MotionEvent.ACTION_CANCEL
                        primary.onTouchEvent(cancelEvent); cancelEvent.recycle()
                        return true
                    }
                    pendingTurn = null
                    val first = pages.bitmap(pair.origin) ?: return false
                    val second = pages.bitmap(pair.target) ?: return false
                    val token = transaction.begin(pair.origin, pair.target, direction) ?: return true
                    dragToken = token
                    val cancelEvent = android.view.MotionEvent.obtain(event)
                    cancelEvent.action = android.view.MotionEvent.ACTION_CANCEL
                    primary.onTouchEvent(cancelEvent); cancelEvent.recycle()
                    val timing = touchTiming ?: PageTurnTiming("drag")
                    timing.mark("drag_start")
                    overlay.onFirstMotionDraw = { timing.mark("first_drag_draw"); overlay.onFirstMotionDraw = null }
                    overlay.prepare(first, second, background)
                    curl.event(io.legado.app.feature.reader.core.transition.ioscurl.gesture.CurlTouchKind.Move, event.x, event.y, event.eventTime)
                    if (mode == "ios_curl") {
                        curl.begin(if (direction > 0) ReaderTurnDirection.NEXT else ReaderTurnDirection.PREVIOUS,
                            first.width, first.height, parent.resources.displayMetrics.density, scope, automatic = false)
                        curl.rendererReady()
                        motion = scope.launch {
                            while (transaction.phase == PageTurnTransaction.Phase.DRAGGING) {
                                withFrameNanos { }; curl.config?.let { overlay.curl(it, curl.values) }
                            }
                        }
                    }
                }
                val token = dragToken ?: return false
                if (mode == "ios_curl") {
                    curl.event(io.legado.app.feature.reader.core.transition.ioscurl.gesture.CurlTouchKind.Move, event.x, event.y, event.eventTime)
                    curl.drag()
                } else overlay.fadeProgress = (dx * -token.direction / primary.width).coerceIn(0f, 1f)
                return true
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                deferredDragDirection?.let { direction ->
                    deferredDragDirection = null
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) tap(direction)
                    return true
                }
                val token = dragToken ?: return false
                dragToken = null
                val cancelled = event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                curl.event(if (cancelled) io.legado.app.feature.reader.core.transition.ioscurl.gesture.CurlTouchKind.Cancel else io.legado.app.feature.reader.core.transition.ioscurl.gesture.CurlTouchKind.Up, event.x, event.y, event.eventTime)
                val release = curl.release(if (token.direction > 0) ReaderTurnDirection.NEXT else ReaderTurnDirection.PREVIOUS, cancelled)
                animate(token, cfi, token.direction,
                    if (mode == "ios_curl") release.first else !cancelled && overlay.fadeProgress >= .1f,
                    manual = true, velocity = release.second)
                return true
            }
        }
        return dragToken != null
    }

    fun cancel(clearPending: Boolean = true) {
        if (clearPending) {
            pendingTurn = null
            lastTapTime = 0L
            continuousTurn = false
        }
        if (dragToken != null || deferredDragDirection != null) swallowGesture = true
        deferredDragDirection = null
        pendingFallbackDirection = null
        dragToken = null
        finishVisual = null
        motion?.cancel(); motion = null
        transaction.invalidate(); curl.reset(); overlay.clear()
        primary.finishAnimatedCommit()
    }

    override fun close() {
        parent.removeOnLayoutChangeListener(sizeListener)
        bookSource = null
        cancel(); pages.close(); overlay.onRenderFailure = null
        parent.removeView(overlay)
    }
}
