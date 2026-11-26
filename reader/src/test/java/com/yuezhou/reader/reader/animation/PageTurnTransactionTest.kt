package com.yuezhou.reader.reader.animation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PageTurnTransactionTest {
    private val origin = PageSnapshotKey(1, 1, "origin", 1080, 2121)
    private val target = origin.copy(cfi = "target")

    @Test fun cancellationCannotCommitAndStaleCallbacksCannotOwnANewTurn() {
        val state = PageTurnTransaction()
        val cancelled = state.begin(origin, target, 1)!!
        state.invalidate()
        val current = state.begin(origin, target, -1)!!
        assertFalse(state.commit(cancelled))
        assertFalse(state.finish(cancelled))
        assertTrue(state.owns(current))
        assertEquals(PageTurnTransaction.Phase.DRAGGING, state.phase)
    }

    @Test fun continuousInputAndRepeatedCompletionIssueAtMostOneNavigation() {
        val state = PageTurnTransaction()
        val token = state.begin(origin, target, 1)!!
        assertNull(state.begin(origin, target, 1))
        assertFalse(state.commit(token))
        assertTrue(state.settle(token))
        assertTrue(state.commit(token))
        assertFalse(state.commit(token))
        assertNull(state.begin(origin, target, -1))
        assertTrue(state.finish(token))
        assertNotNull(state.begin(target, origin, -1))
    }

    @Test fun boundaryAndMismatchedLayoutCannotStartAPreview() {
        val state = PageTurnTransaction()
        assertNull(state.begin(origin, origin, 1))
        assertThrows(IllegalArgumentException::class.java) { state.begin(origin, target.copy(layoutRevision = 2), 1) }
        assertThrows(IllegalArgumentException::class.java) { state.begin(origin, target.copy(bookSession = 2), 1) }
    }
}
