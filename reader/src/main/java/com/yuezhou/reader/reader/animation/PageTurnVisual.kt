package com.yuezhou.reader.reader.animation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** A new input may finish the visual, but never cancels or duplicates the navigation that follows it. */
suspend fun awaitPageTurnVisual(
    finishRequested: Deferred<Unit>,
    render: suspend CoroutineScope.() -> Unit,
    showFinalFrame: () -> Unit,
) = coroutineScope {
    val visual = launch(block = render)
    select<Unit> {
        visual.onJoin { }
        finishRequested.onAwait { visual.cancelAndJoin() }
    }
    showFinalFrame()
}
