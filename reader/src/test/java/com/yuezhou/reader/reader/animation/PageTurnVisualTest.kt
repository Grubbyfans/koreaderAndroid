package com.yuezhou.reader.reader.animation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PageTurnVisualTest {
    private suspend fun renderTurn(speed: () -> Int) {
        val timeline = PageTurnFrameTime()
        var time = 10_000_000L
        delay(10)
        val start = timeline.frame(time, speed())
        do {
            delay(10)
            time += 10_000_000L
        } while (timeline.frame(time, speed()) - start < 360_000_000L)
    }
    private val origin = PageSnapshotKey(1, 1, "origin", 1080, 2121)
    private val target = origin.copy(cfi = "target")

    @Test fun nextTouchFinishesEarlyAndCommitsExactlyOnce() = runTest() {
        val state = PageTurnTransaction()
        val token = state.begin(origin, target, 1)!!
        state.settle(token)
        val finish = CompletableDeferred<Unit>()
        var finalFrames = 0
        var navigations = 0
        val motion = launch {
            awaitPageTurnVisual(finish, { speed -> renderTurn(speed) }, { finalFrames++ })
            if (state.commit(token)) navigations++
            delay(20) // Navigation remains alive while its visual has been interrupted.
            state.finish(token)
        }
        runCurrent()
        advanceTimeBy(60)
        assertTrue(finish.complete(Unit))
        assertFalse(finish.complete(Unit))
        runCurrent()
        assertEquals(0, finalFrames) // Input accelerates the remaining frames instead of snapping.
        assertEquals(0, navigations)
        advanceTimeBy(110)
        runCurrent()
        assertEquals(1, finalFrames)
        assertEquals(1, navigations)
        assertEquals(PageTurnTransaction.Phase.COMMITTING, state.phase)
        assertNull(state.begin(origin, target, 1))
        motion.join()
        assertEquals(180L, testScheduler.currentTime)
        assertNotNull(state.begin(target, origin, -1))
    }

    @Test fun ordinaryTapKeepsItsFullAnimation() = runTest() {
        var finalFrames = 0
        awaitPageTurnVisual(CompletableDeferred(), { speed -> renderTurn(speed) }, { finalFrames++ })
        assertEquals(370L, testScheduler.currentTime)
        assertEquals(1, finalFrames)
    }

    @Test fun externalCancellationNeverPublishesOrCommitsAStalePage() = runTest() {
        val finish = CompletableDeferred<Unit>()
        var finalFrames = 0
        var navigations = 0
        val motion = launch {
            awaitPageTurnVisual(finish, { speed -> renderTurn(speed) }, { finalFrames++ })
            navigations++
        }
        runCurrent()
        motion.cancelAndJoin()
        finish.complete(Unit)
        runCurrent()
        assertEquals(0, finalFrames)
        assertEquals(0, navigations)
    }
    @Test fun continuousTapKeepsVisibleFramesAtTwiceTheSpeed() = runTest() {
        var finalFrames = 0
        awaitPageTurnVisual(CompletableDeferred(), { speed -> renderTurn(speed) }, { finalFrames++ }, continuous = true)
        assertEquals(190L, testScheduler.currentTime)
        assertEquals(1, finalFrames)
    }

    @Test fun speedChangesKeepFrameTimeContinuous() {
        val time = PageTurnFrameTime()
        assertEquals(100L, time.frame(100L, 1))
        assertEquals(110L, time.frame(110L, 1))
        assertEquals(140L, time.frame(120L, 3))
        assertEquals(160L, time.frame(130L, 2))
    }
}
