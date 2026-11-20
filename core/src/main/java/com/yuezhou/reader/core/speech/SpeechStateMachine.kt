package com.yuezhou.reader.core.speech

/**
 * 朗读状态机核心：管理朗读生命周期与片段游标，与具体 TTS 引擎/媒体服务解耦。
 *
 * 对齐技术方案 §7.1 / §7.3：
 *   - 状态：Idle → Playing → Paused / Stopped → Idle。
 *   - 暂停/继续、前后句、停止。
 *   - 「云端同步锚点只采用已实际确认的当前朗读点，不能把已排队但未播出的下一句当进度」。
 *
 * 本文件纯 Kotlin，无 Android 依赖；由上层 TTS 服务驱动事件并读取游标。
 *
 * 状态机自身不做线程同步——由调用方（TTS 服务）保证串行访问。
 */
class SpeechStateMachine {

    /** 朗读状态。 */
    enum class State { IDLE, PLAYING, PAUSED, STOPPED }

    data class Position(
        val segmentIndex: Int,
        val charOffset: Int,
    )

    /** 当前状态。 */
    var state: State = State.IDLE
        private set

    /** 当前计划（可为 null，未加载时为空）。 */
    var plan: SpeechPlan? = null
        private set

    /**
     * 当前朗读位置：
     *   - segmentIndex：正在播/已播到的片段序号；
     *   - charOffset：该片段内已确认播出的字符偏移。
     * 只有「已实际确认」的位置才允许被用作云端同步锚点。
     */
    var position: Position = Position(0, 0)
        private set

    /** 当前「已确认播出的」片段序号（即 [position.segmentIndex]，供同步使用）。 */
    val confirmedSegmentIndex: Int get() = position.segmentIndex

    /** 加载新计划并复位到起始。 */
    fun load(plan: SpeechPlan) {
        this.plan = plan
        this.position = Position(0, 0)
        this.state = State.IDLE
    }

    /** 作废当前计划（同名覆盖/切书时调用），停止并清空。 */
    fun invalidate() {
        this.plan = null
        this.position = Position(0, 0)
        this.state = State.STOPPED
    }

    /** 从某片段开始播放。 */
    fun play(fromSegment: Int = position.segmentIndex): Result<SpeechSegment> {
        val p = plan ?: return Result.failure(IllegalStateException("无朗读计划"))
        if (p.segments.isEmpty()) return Result.failure(IllegalStateException("朗读计划为空"))
        val idx = fromSegment.coerceIn(0, p.segments.lastIndex)
        position = Position(idx, 0)
        state = State.PLAYING
        return Result.success(p.segments[idx])
    }

    /** 暂停。 */
    fun pause() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    /** 继续（从暂停恢复）。 */
    fun resume(): Result<SpeechSegment> {
        val p = plan ?: return Result.failure(IllegalStateException("无朗读计划"))
        if (state != State.PAUSED) return Result.failure(IllegalStateException("非暂停状态"))
        state = State.PLAYING
        return Result.success(p.segmentAt(position.segmentIndex) ?: p.segments.first())
    }

    /** 停止。 */
    fun stop() {
        state = State.STOPPED
    }

    /**
     * TTS 引擎回调：确认某片段已播到指定字符偏移。
     * 更新已确认位置，供云端同步读取「已实际确认的当前朗读点」。
     */
    fun onProgress(segmentIndex: Int, charOffset: Int) {
        if (segmentIndex >= 0) {
            position = Position(segmentIndex, charOffset.coerceAtLeast(0))
        }
    }

    /** 当前片段播完，前进到下一片段。返回下一片段（无则 null）。 */
    fun advanceToNext(): SpeechSegment? {
        val p = plan ?: return null
        val next = position.segmentIndex + 1
        if (next >= p.segments.size) return null
        position = Position(next, 0)
        return p.segments[next]
    }

    /** 跳到上一句（允许重播当前句，避免跳过未读内容）。 */
    fun previous(): Result<SpeechSegment> {
        val p = plan ?: return Result.failure(IllegalStateException("无朗读计划"))
        if (p.segments.isEmpty()) return Result.failure(IllegalStateException("朗读计划为空"))
        val idx = (position.segmentIndex - 1).coerceAtLeast(0)
        position = Position(idx, 0)
        return Result.success(p.segments[idx])
    }

    /** 跳到下一句。 */
    fun next(): Result<SpeechSegment> {
        val p = plan ?: return Result.failure(IllegalStateException("无朗读计划"))
        if (p.segments.isEmpty()) return Result.failure(IllegalStateException("朗读计划为空"))
        val idx = (position.segmentIndex + 1).coerceAtMost(p.segments.lastIndex)
        position = Position(idx, 0)
        return Result.success(p.segments[idx])
    }

    /** 当前片段。 */
    fun currentSegment(): SpeechSegment? = plan?.segmentAt(position.segmentIndex)
}
