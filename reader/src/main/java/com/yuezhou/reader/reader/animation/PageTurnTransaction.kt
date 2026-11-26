package com.yuezhou.reader.reader.animation

/** Owns the visual transaction only; the reader remains responsible for navigation and persistence. */
class PageTurnTransaction {
    enum class Phase { IDLE, DRAGGING, SETTLING, COMMITTING }
    data class Token(val generation: Long, val origin: PageSnapshotKey, val target: PageSnapshotKey, val direction: Int)
    var phase = Phase.IDLE
        private set
    private var generation = 0L
    private var active: Token? = null

    fun begin(origin: PageSnapshotKey, target: PageSnapshotKey, direction: Int): Token? {
        require(direction == -1 || direction == 1)
        if (phase != Phase.IDLE) return null
        require(origin.bookSession == target.bookSession && origin.layoutRevision == target.layoutRevision)
        require(origin.width == target.width && origin.height == target.height)
        if (origin.cfi == target.cfi) return null
        return Token(++generation, origin, target, direction).also { active = it; phase = Phase.DRAGGING }
    }

    fun settle(token: Token): Boolean {
        if (!owns(token) || phase != Phase.DRAGGING) return false
        phase = Phase.SETTLING
        return true
    }

    /** Exactly one navigation may be issued for a completed visual turn. */
    fun commit(token: Token): Boolean {
        if (!owns(token) || phase != Phase.SETTLING) return false
        phase = Phase.COMMITTING
        return true
    }

    fun finish(token: Token): Boolean {
        if (!owns(token)) return false
        invalidate()
        return true
    }

    fun owns(token: Token): Boolean = active == token && token.generation == generation

    /** Navigation/layout/size/session changes invalidate even callbacks already queued on the main thread. */
    fun invalidate() {
        generation++
        active = null
        phase = Phase.IDLE
    }
}
