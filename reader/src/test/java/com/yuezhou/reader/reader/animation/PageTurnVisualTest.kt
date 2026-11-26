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
    private val origin = PageSnapshotKey(1, 1, "origin", 1080, 2121)
    private val target = origin.copy(cfi = "target")

    @Test fun nextTouchFinishesEarlyAndCommitsExactlyOnce() = runTest {
        val state = PageTurnTransaction()
        val token = state.begin(origin, target, 1)!!
        state.settle(token)
        val finish = CompletableDeferred<Unit>()
        var finalFrames = 0
        var navigations = 0
        val motion = launch {
            awaitPageTurnVisual(finish, { delay(360) }, { finalFrames++ })
            if (state.commit(token)) navigations++
            delay(20) // Navigation remains alive while its visual has been interrupted.
            state.finish(token)
        }
        runCurrent()
        advanceTimeBy(60)
        assertTrue(finish.complete(Unit))
        assertFalse(finish.complete(Unit))
        runCurrent()
        assertEquals(1, finalFrames)
        assertEquals(1, navigations)
        assertEquals(PageTurnTransaction.Phase.COMMITTING, state.phase)
        assertNull(state.begin(origin, target, 1))
        motion.join()
        assertEquals(80L, testScheduler.currentTime)
        assertNotNull(state.begin(target, origin, -1))
    }

    @Test fun ordinaryTapKeepsItsFullAnimation() = runTest {
        var finalFrames = 0
        awaitPageTurnVisual(CompletableDeferred(), { delay(360) }, { finalFrames++ })
        assertEquals(360L, testScheduler.currentTime)
        assertEquals(1, finalFrames)
    }

    @Test fun externalCancellationNeverPublishesOrCommitsAStalePage() = runTest {
        val finish = CompletableDeferred<Unit>()
        var finalFrames = 0
        var navigations = 0
        val motion = launch {
            awaitPageTurnVisual(finish, { delay(360) }, { finalFrames++ })
            navigations++
        }
        runCurrent()
        motion.cancelAndJoin()
        finish.complete(Unit)
        runCurrent()
        assertEquals(0, finalFrames)
        assertEquals(0, navigations)
    }
}
