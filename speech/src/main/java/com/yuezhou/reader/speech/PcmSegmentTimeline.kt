package com.yuezhou.reader.speech

/** 由播放头决定句子开始/结束；写入 PCM 仅登记边界，不代表开始播放。调用方负责同步。 */
internal class PcmSegmentTimeline {
    sealed interface Event {
        val index: Int
        data class Started(override val index: Int) : Event
        data class Completed(override val index: Int) : Event
    }

    private data class Boundary(
        val index: Int,
        val start: Long,
        val end: Long,
        var written: Boolean = false,
        var started: Boolean = false,
    )

    private val boundaries = ArrayDeque<Boundary>()
    private var totalFrames = 0L

    val nextMarkerFrame: Long?
        get() = boundaries.firstOrNull()?.takeIf { it.written }?.end

    fun append(index: Int, frames: Int) {
        require(frames > 0)
        boundaries.addLast(Boundary(index, totalFrames, totalFrames + frames))
        totalFrames += frames
    }

    fun markWritten(index: Int) {
        boundaries.firstOrNull { it.index == index }?.written = true
    }

    fun drain(playedFrames: Long): List<Event> = buildList {
        while (boundaries.isNotEmpty()) {
            val boundary = boundaries.first()
            if (!boundary.started && playedFrames > boundary.start) {
                boundary.started = true
                add(Event.Started(boundary.index))
            }
            if (!boundary.written || playedFrames < boundary.end) break
            boundaries.removeFirst()
            add(Event.Completed(boundary.index))
        }
    }

    fun clear() {
        boundaries.clear()
        totalFrames = 0L
    }
}
