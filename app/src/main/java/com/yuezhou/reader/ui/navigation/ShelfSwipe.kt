package com.yuezhou.reader.ui.navigation

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 一个手势只拥有起点和首次拖动方向上的相邻页。 */
internal data class ShelfSwipe(val origin: Int, val neighbor: Int, val pageSize: Float) {
    fun clamp(distance: Float): Float =
        if (neighbor < origin) distance.coerceIn(-pageSize, 0f)
        else distance.coerceIn(0f, (neighbor - origin) * pageSize)

    fun destination(distance: Float, velocity: Float, flingThreshold: Float): Int {
        val direction = neighbor - origin
        val fling = direction * velocity >= flingThreshold && abs(distance) >= pageSize * 0.08f
        return if (abs(distance) >= pageSize * 0.5f || fling) neighbor else origin
    }
}

internal fun Modifier.shelfSwipe(
    enabled: Boolean,
    pager: PagerState,
    layoutDirection: LayoutDirection,
    pageKeys: List<String>,
    committedKey: () -> String,
    onDragging: (Boolean) -> Unit,
    onSettle: (String) -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(pager, pageKeys, layoutDirection) {
        val velocity = VelocityTracker()
        val forward = if (layoutDirection == LayoutDirection.Ltr) -1f else 1f
        val flingThreshold = 500.dp.toPx()
        var origin = -1
        var swipe: ShelfSwipe? = null
        var distance = 0f

        fun settle(index: Int) {
            if (origin >= 0) pageKeys.getOrNull(index)?.let(onSettle)
            origin = -1
            onDragging(false)
        }

        try {
            detectHorizontalDragGestures(
                onDragStart = {
                    origin = if (pager.isScrollInProgress) -1 else pageKeys.indexOf(committedKey())
                    distance = 0f
                    swipe = null
                    velocity.resetTracking()
                    onDragging(origin >= 0)
                },
                onDragCancel = { settle(origin) },
                onDragEnd = {
                    val active = swipe
                    val speed = velocity.calculateVelocity().x * forward
                    settle(active?.destination(distance, speed, flingThreshold) ?: origin)
                },
                onHorizontalDrag = { change, delta ->
                    if (origin >= 0) {
                        velocity.addPosition(change.uptimeMillis, change.position)
                        val step = delta * forward
                        if (swipe == null && step != 0f) {
                            val direction = if (step > 0f) 1 else -1
                            val neighbor = (origin + direction).coerceIn(pageKeys.indices)
                            swipe = ShelfSwipe(origin, neighbor, pager.layoutInfo.pageSize.toFloat().coerceAtLeast(1f))
                        }
                        swipe?.let { active ->
                            val target = active.clamp(distance + step)
                            distance += pager.dispatchRawDelta(target - distance)
                        }
                    }
                },
            )
        } finally {
            if (origin >= 0) settle(origin)
        }
    }
}
