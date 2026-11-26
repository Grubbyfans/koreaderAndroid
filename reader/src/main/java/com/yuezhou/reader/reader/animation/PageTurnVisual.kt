package com.yuezhou.reader.reader.animation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope

/** Accelerate the remaining frames without dropping the visual or cancelling navigation. */
suspend fun awaitPageTurnVisual(
    finishRequested: Deferred<Unit>,
    render: suspend CoroutineScope.(speed: () -> Int) -> Unit,
    showFinalFrame: () -> Unit,
    continuous: Boolean = false,
) = coroutineScope {
    render { if (finishRequested.isCompleted) 3 else if (continuous) 2 else 1 }
    showFinalFrame()
}

/** Shared by the fade and curl renderers; timestamps stay continuous when speed changes. */
class PageTurnFrameTime {
    private var previous: Long? = null
    private var virtual = 0L

    fun frame(time: Long, speed: Int): Long {
        val last = previous
        if (last == null) virtual = time
        else virtual += (time - last).coerceAtLeast(0) * speed
        previous = time
        return virtual
    }
}
