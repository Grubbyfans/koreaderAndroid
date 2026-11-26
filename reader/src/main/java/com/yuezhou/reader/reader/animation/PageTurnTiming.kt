package com.yuezhou.reader.reader.animation

import android.os.SystemClock
import android.util.Log

/** Opt-in diagnostics: adb shell setprop log.tag.ReaderTurnTiming DEBUG. No book content. */
class PageTurnTiming(private val kind: String) {
    private val enabled = Log.isLoggable(TAG, Log.DEBUG)
    private val id = if (enabled) ++sequence else 0
    private val start = SystemClock.uptimeMillis()
    private var previous = start

    fun mark(stage: String) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        Log.d(TAG, "kind=$kind id=$id stage=$stage total_ms=${now - start} delta_ms=${now - previous}")
        previous = now
    }

    companion object {
        private const val TAG = "ReaderTurnTiming"
        private var sequence = 0L // All measurements originate on the reader main thread.
    }
}
